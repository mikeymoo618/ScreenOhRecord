package com.screenohrecord.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** Live scheduler state stays separate from connection tests and historical interruptions. */
final class TransferStatus {
    private TransferStatus() { }

    static String current(Context context) {
        SharedPreferences preferences = TransferSettings.preferences(context);
        if (!preferences.getBoolean("enabled", false)) return "Automatic moves are disabled.";
        if (TransferEngine.busy() || TransferService.running() || TransferJob.active(TransferScheduler.CONTINUOUS_JOB_ID))
            return preferences.getString("status", "Starting video transfers.");
        if (TransferConnection.wifi(context) == null) return "Waiting for an active Wi-Fi connection.";
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        int id = TransferScheduler.CONTINUOUS_JOB_ID;
        JobInfo job = scheduler.getPendingJob(id);
        if (job == null) {
            id = TransferScheduler.RETRY_JOB_ID;
            job = scheduler.getPendingJob(id);
        }
        if (job != null) return "Wi-Fi connected. " + waiting(context, id);
        String previous = preferences.getString("status", "");
        String message = scheduler.getPendingJob(TransferScheduler.JOB_ID) != null
                ? "Wi-Fi connected. Next automatic check is scheduled. Move videos now starts a fresh check."
                : "Wi-Fi connected. Ready to start. Tap Move videos now.";
        if (previous.startsWith("Moved ") || previous.startsWith("Checked recordings.")) message += "\n" + previous;
        return message;
    }

    private static String waiting(Context context, int id) {
        if (Build.VERSION.SDK_INT < 34) return "An automatic retry is scheduled. Move videos now starts immediately.";
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            int[] reasons = Build.VERSION.SDK_INT >= 36 ? scheduler.getPendingJobReasons(id)
                    : new int[]{scheduler.getPendingJobReason(id)};
            return describeReasons(reasons);
        } catch (RuntimeException error) {
            android.util.Log.i("ScreenOhRecord", "Could not read pending transfer reasons", error);
            return "Waiting for Android to start the queued transfer. Scheduling details are unavailable.";
        }
    }

    static String describeReasons(int[] reasons) {
        List<String> labels = new ArrayList<>();
        for (int reason : reasons) {
            String label;
            switch (reason) {
                case JobScheduler.PENDING_JOB_REASON_EXECUTING: label = "Android is starting or running the transfer"; break;
                case JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY: label = "Android retry delay"; break;
                case JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY: label = "Android's Wi-Fi network constraint"; break;
                case JobScheduler.PENDING_JOB_REASON_QUOTA: label = "Android scheduling quota"; break;
                case JobScheduler.PENDING_JOB_REASON_APP_STANDBY: label = "Android app standby"; break;
                case JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION: label = "Android background restriction"; break;
                case JobScheduler.PENDING_JOB_REASON_DEVICE_STATE: label = "Android device-state restriction"; break;
                case JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE: label = "Android device-idle constraint"; break;
                case JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW: label = "Android battery constraint"; break;
                case JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING: label = "Android charging constraint"; break;
                case JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW: label = "Android storage constraint"; break;
                case JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION: label = "Android scheduling optimization"; break;
                case JobScheduler.PENDING_JOB_REASON_USER: label = "Android user restriction"; break;
                case JobScheduler.PENDING_JOB_REASON_APP: label = "Android app-state scheduling"; break;
                case JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID: label = "Queued transfer has ended"; break;
                default: label = "Android scheduling (reason " + reason + ")";
            }
            if (!labels.contains(label)) labels.add(label);
        }
        if (labels.isEmpty()) return "Waiting for Android to start the queued transfer.";
        return String.join(", ", labels) + ".";
    }

    static String lastIssue(Context context) {
        SharedPreferences preferences = TransferSettings.preferences(context);
        String message = preferences.getString("last_issue", "");
        if (message.isEmpty()) return "";
        long time = preferences.getLong("last_issue_at", 0);
        String date = time > 0 ? " · " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(time)) : "";
        return "Last interruption" + date + "\n" + message;
    }
}
