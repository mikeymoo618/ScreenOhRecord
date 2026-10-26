package com.screenohrecord.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.TimeUnit;

final class UpdateScheduler {
    static final int JOB_ID = 120;
    private UpdateScheduler() { }
    static boolean enabled(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("weekly", false);
    }
    static boolean setEnabled(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!enabled) {
            scheduler.cancel(JOB_ID);
            context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("weekly", false).apply();
            return true;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(1))
                .setBackoffCriteria(TimeUnit.HOURS.toMillis(6), JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true).build();
        boolean scheduled = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("weekly", scheduled).apply();
        return scheduled;
    }
}
