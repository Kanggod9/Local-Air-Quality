package com.localairquality.app.background;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** Durable catch-up, independent of Activity downloads and live-data availability. */
public final class HistoryRecoveryJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters active;

    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        HistoryRecoveryCoordinator.recover(this).whenComplete((complete, error) -> {
            main.post(() -> {
                // A stopped job's late callback cannot finish its replacement.
                if (active != params) return;
                active = null;
                jobFinished(params, error != null || !Boolean.TRUE.equals(complete));
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active != null && active.getJobId() == params.getJobId()) {
            active = null;
            // The same request can serve a visible Activity. Do not cancel its catch-up.
        }
        return true;
    }

    @Override public void onDestroy() {
        active = null;
        super.onDestroy();
    }
}
