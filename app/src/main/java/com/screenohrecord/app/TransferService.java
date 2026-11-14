package com.screenohrecord.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-visible continuous queue execution on Android versions preceding UIDT jobs. */
@android.annotation.SuppressLint("WakelockTimeout") // Worker finally releases the lock and service destruction cancels it.
public final class TransferService extends Service {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private static volatile boolean running;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean working;
    private int latestStartId;

    static boolean running() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        TransferNotifications.createChannel(this);
        startForeground(TransferNotifications.ID, TransferNotifications.build(this, "Checking video queue", 0, 0));
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(TransferNotifications.ID, TransferNotifications.build(this, "Checking video queue", 0, 0));
        latestStartId = startId;
        if (working) return START_STICKY;
        if (!TransferSettings.preferences(this).getBoolean("enabled", false)) {
            stopSelf(startId); return START_NOT_STICKY;
        }
        startWorker(startId);
        return START_STICKY;
    }

    private void startWorker(int startId) {
        working = true;
        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenOhRecord:VideoTransfers");
        new Thread(() -> {
            // Every exit releases this lock. Android's service lifecycle cancels the worker on interruption.
            boolean retry = false;
            boolean acquired = false;
            try {
                while (!cancelled.get() && TransferSettings.preferences(this).getBoolean("enabled", false)) {
                    if (TransferEngine.begin()) { acquired = true; break; }
                    android.os.SystemClock.sleep(100);
                }
                if (!acquired) return;
                wake.acquire();
                TransferEngine.run(this, cancelled::get,
                        (message, bytes, total) -> {
                            if (!cancelled.get()) TransferNotifications.update(this, message, bytes, total);
                        });
                retry = TransferSettings.preferences(this).getBoolean("retry_needed", false);
            } catch (Exception error) {
                retry = true;
                TransferEngine.status(this, "Transfer paused. " + error.getMessage());
                android.util.Log.w("ScreenOhRecord", "Continuous transfer interrupted", error);
            } finally {
                if (wake.isHeld()) wake.release();
                if (acquired) TransferEngine.end();
                boolean needsRetry = retry;
                handler.post(() -> {
                    working = false;
                    if (!cancelled.get() && latestStartId != startId
                            && TransferSettings.preferences(this).getBoolean("enabled", false)) startWorker(latestStartId);
                    else {
                        if (needsRetry && !cancelled.get()) TransferScheduler.retry(this);
                        stopSelf(startId);
                    }
                });
            }
        }, "ScreenOhRecord-continuous-transfers").start();
    }

    @Override public void onDestroy() {
        cancelled.set(true);
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
