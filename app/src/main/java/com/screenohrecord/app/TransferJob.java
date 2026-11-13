package com.screenohrecord.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferJob extends JobService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AtomicBoolean cancelled = new AtomicBoolean();

    @Override public boolean onStartJob(JobParameters params) {
        if (!TransferSettings.preferences(this).getBoolean("enabled", false)) return false;
        if (TransferConnection.wifi(this) == null) {
            TransferEngine.status(this, "Waiting for Wi-Fi. Local recordings retained.");
            return false;
        }
        if (!TransferEngine.begin()) return false;
        AtomicBoolean token = new AtomicBoolean();
        cancelled = token;
        new Thread(() -> {
            try { TransferEngine.run(this, token::get); }
            catch (Exception error) {
                TransferEngine.status(this, "Transfer could not finish. Local files retained for retry. Check connection and folder access.");
                android.util.Log.w("ScreenOhRecord", "Automatic video transfer did not finish", error);
            } finally {
                TransferEngine.end();
                handler.post(() -> { if (!token.get()) jobFinished(params, false); });
            }
        }, "ScreenOhRecord-transfer").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelled.set(true);
        return TransferSettings.preferences(this).getBoolean("enabled", false);
    }

    @Override public void onDestroy() { cancelled.set(true); super.onDestroy(); }
}
