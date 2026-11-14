package com.screenohrecord.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

final class TransferNotifications {
    static final int ID = 13;
    private static final String CHANNEL = "screenohrecord_transfers";
    private TransferNotifications() { }

    static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Video transfers", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static Notification build(Context context, String message, long bytes, long total) {
        PendingIntent open = PendingIntent.getActivity(context, ID, new Intent(context, TransfersActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_record)
                .setContentTitle("ScreenOhRecord video transfers").setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(1000, total > 0 ? (int) Math.min(1000, bytes * 1000L / total) : 0, total <= 0).build();
    }

    static void update(Context context, String message, long bytes, long total) {
        try { context.getSystemService(NotificationManager.class).notify(ID, build(context, message, bytes, total)); }
        catch (SecurityException ignored) { /* Progress remains visible in the app when notifications are denied. */ }
    }

    static void clear(Context context) { context.getSystemService(NotificationManager.class).cancel(ID); }
}
