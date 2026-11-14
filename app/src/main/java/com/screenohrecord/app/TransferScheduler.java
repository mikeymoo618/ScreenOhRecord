package com.screenohrecord.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import java.util.concurrent.TimeUnit;

final class TransferScheduler {
    static final int JOB_ID = 130;
    static final int CONTINUOUS_JOB_ID = 131;
    static final int RETRY_JOB_ID = 132;
    private TransferScheduler() { }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID);
        if (!TransferSettings.preferences(context).getBoolean("enabled", false)) {
            scheduler.cancel(CONTINUOUS_JOB_ID);
            scheduler.cancel(RETRY_JOB_ID);
            // A pending foreground-service start must promote itself before stopping.
            // Its worker observes enabled=false and closes its connections immediately.
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, TransferJob.class))
                .setRequiredNetwork(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setBackoffCriteria(TimeUnit.MINUTES.toMillis(15), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            TransferSettings.preferences(context).edit().putBoolean("enabled", false)
                    .putString("status", "Android could not schedule transfers. Enable again to retry.").apply();
        }
    }

    /** Called from a visible user action, or opportunistically by the automatic discovery job. */
    static synchronized boolean startContinuous(Context context) {
        if (!TransferSettings.preferences(context).getBoolean("enabled", false)) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (android.os.Build.VERSION.SDK_INT >= 34 && scheduler.getPendingJob(CONTINUOUS_JOB_ID) != null) return true;
        boolean handingOff = TransferEngine.busy();
        if (handingOff && !(context instanceof android.app.Activity)) return true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                JobInfo job = new JobInfo.Builder(CONTINUOUS_JOB_ID, new ComponentName(context, TransferJob.class))
                        .setUserInitiated(true).setRequiredNetwork(new NetworkRequest.Builder()
                                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) return false;
            } else {
                context.startForegroundService(new android.content.Intent(context, TransferService.class));
            }
            if (handingOff) {
                scheduler.cancel(JOB_ID);
                scheduler.cancel(RETRY_JOB_ID);
                schedule(context);
            }
            return true;
        } catch (IllegalStateException | SecurityException error) {
            android.util.Log.i("ScreenOhRecord", "Continuous transfer start deferred by Android", error);
            return false;
        }
    }

    static void retry(Context context) {
        if (!TransferSettings.preferences(context).getBoolean("enabled", false)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(RETRY_JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(RETRY_JOB_ID, new ComponentName(context, TransferJob.class))
                .setMinimumLatency(30_000).setRequiredNetwork(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        scheduler.schedule(job);
    }
}
