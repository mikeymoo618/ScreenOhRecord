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
    private TransferScheduler() { }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID);
        if (!TransferSettings.preferences(context).getBoolean("enabled", false)) return;
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
}
