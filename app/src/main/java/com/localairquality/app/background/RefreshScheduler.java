package com.localairquality.app.background;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class RefreshScheduler {
    static final int CLEANUP_JOB_ID = 40252;
    private static final int JOB_ID = 40251;
    private static final int RECOVERY_JOB_ID = 40253;
    private static final long POLL_INTERVAL = 15L * 60L * 1000L;

    private RefreshScheduler() {}

    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, RefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(POLL_INTERVAL)
                .setPersisted(true)
                .build();
        JobInfo existing = scheduler.getPendingJob(JOB_ID);
        if (existing == null || existing.getIntervalMillis() != POLL_INTERVAL) scheduler.schedule(job);
        if (scheduler.getPendingJob(CLEANUP_JOB_ID) == null) scheduler.schedule(new JobInfo.Builder(CLEANUP_JOB_ID,
                new ComponentName(context, RefreshJobService.class))
                .setPeriodic(15L * 60 * 1000).setPersisted(true).build());
        scheduleRecovery(context);
    }

    public static synchronized void scheduleRecovery(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(RECOVERY_JOB_ID) != null) return;
        com.localairquality.app.data.AirQualityReading reading =
                com.localairquality.app.data.ReadingStore.loadReading(context);
        if (reading == null || reading.stationId.isEmpty()) return;
        if (reading.stationId.startsWith("openaq:")
                && !com.localairquality.app.data.ReadingStore.hasOpenAqKey(context)) return;
        if (!com.localairquality.app.data.HistoryStore.load(context).needsRecovery(System.currentTimeMillis())) return;
        scheduler.schedule(new JobInfo.Builder(RECOVERY_JOB_ID,
                new ComponentName(context, HistoryRecoveryJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build());
    }
}



