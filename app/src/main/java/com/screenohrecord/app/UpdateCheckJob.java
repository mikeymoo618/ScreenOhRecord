package com.screenohrecord.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional metadata-only weekly check. Never downloads or installs an APK. */
public final class UpdateCheckJob extends JobService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AtomicBoolean cancelled = new AtomicBoolean();
    @Override public boolean onStartJob(JobParameters params) {
        if (!UpdateScheduler.enabled(this) || !UpdateClient.unmeteredWifi(this)) return false;
        if (!UpdateClient.begin()) { handler.post(() -> jobFinished(params, true)); return true; }
        AtomicBoolean token = new AtomicBoolean(); cancelled = token;
        new Thread(() -> {
            try {
                UpdateClient.Release release = UpdateClient.check(this, token::get, true);
                if (!token.get() && release.versionCode > BuildConfig.VERSION_CODE) notifyAvailable(release.versionName);
            } catch (Exception error) {
                if (!token.get()) getSharedPreferences("updates", MODE_PRIVATE).edit()
                        .putString("last_result", "Weekly check could not finish. Open Updates to try again.").apply();
                android.util.Log.w("ScreenOhRecord", "Weekly update metadata check did not finish", error);
            } finally {
                UpdateClient.end();
                handler.post(() -> { if (!token.get()) jobFinished(params, false); });
            }
        }, "ScreenOhRecord-weekly-update").start();
        return true;
    }
    private void notifyAvailable(String version) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 120, new Intent(this, UpdatesActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(120, new Notification.Builder(this, "updates").setSmallIcon(R.drawable.ic_record)
                .setContentTitle("ScreenOhRecord " + version + " is available")
                .setContentText("Open Updates to review and download. Nothing was installed.")
                .setContentIntent(open).setAutoCancel(true).build());
    }
    @Override public boolean onStopJob(JobParameters params) { cancelled.set(true); return UpdateScheduler.enabled(this); }
}
