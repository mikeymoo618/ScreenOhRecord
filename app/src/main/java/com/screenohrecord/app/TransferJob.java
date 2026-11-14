package com.screenohrecord.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferJob extends JobService {
    private static final ConcurrentHashMap<Integer, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Integer, AtomicBoolean> runs = new ConcurrentHashMap<>();

    static boolean active(int jobId) {
        AtomicBoolean token = activeRuns.get(jobId);
        return token != null && !token.get();
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!TransferSettings.preferences(this).getBoolean("enabled", false)) return false;
        if (params.getJobId() != TransferScheduler.CONTINUOUS_JOB_ID && TransferScheduler.startContinuous(this)) return false;
        if (TransferConnection.wifi(this) == null) {
            TransferEngine.status(this, "Waiting for Wi-Fi. Local recordings retained.");
            return false;
        }
        AtomicBoolean token = new AtomicBoolean();
        runs.put(params.getJobId(), token);
        activeRuns.put(params.getJobId(), token);
        TransferNotifications.createChannel(this);
        if (android.os.Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob()) {
            setNotification(params, TransferNotifications.ID,
                    TransferNotifications.build(this, "Waiting for the current operation to finish", 0, 0), JOB_END_NOTIFICATION_POLICY_REMOVE);
        }
        new Thread(() -> {
            boolean acquired = false;
            boolean retry = false;
            try {
                while (!token.get() && TransferSettings.preferences(this).getBoolean("enabled", false)) {
                    if (TransferEngine.begin()) { acquired = true; break; }
                    android.os.SystemClock.sleep(100);
                }
                if (!acquired) return;
                TransferEngine.run(this, token::get, (message, bytes, total) -> {
                    if (!token.get() && runs.get(params.getJobId()) == token)
                        TransferNotifications.update(this, message, bytes, total);
                });
                retry = TransferSettings.preferences(this).getBoolean("retry_needed", false);
            } catch (Exception error) {
                retry = true;
                TransferEngine.issue(this, error.getMessage() == null
                        ? "Transfer could not finish. Check connection and folder access." : error.getMessage());
                android.util.Log.w("ScreenOhRecord", "Automatic video transfer did not finish", error);
            } finally {
                if (acquired) TransferEngine.end();
                activeRuns.remove(params.getJobId(), token);
                boolean needsRetry = retry;
                handler.post(() -> {
                    if (runs.remove(params.getJobId(), token)) {
                        if (!runs.containsKey(TransferScheduler.CONTINUOUS_JOB_ID) && !TransferService.running())
                            TransferNotifications.clear(this);
                        if (!token.get()) jobFinished(params, needsRetry);
                    }
                });
            }
        }, "ScreenOhRecord-transfer-" + params.getJobId()).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean token = runs.get(params.getJobId());
        if (token != null) token.set(true);
        String reason = android.os.Build.VERSION.SDK_INT >= 31 ? " (reason " + params.getStopReason() + ")" : "";
        TransferEngine.issue(this, "Android paused transfers" + reason + ". Saved progress will resume when permitted.");
        if (params.getJobId() == TransferScheduler.CONTINUOUS_JOB_ID) TransferNotifications.clear(this);
        return TransferSettings.preferences(this).getBoolean("enabled", false);
    }

    @Override public void onDestroy() {
        for (AtomicBoolean token : runs.values()) token.set(true);
        super.onDestroy();
    }
}
