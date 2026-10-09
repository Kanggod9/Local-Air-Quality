package com.localairquality.app.background;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.widget.WidgetRefreshStatus;

import java.util.concurrent.Future;

/** A widget/notification tap owns a job, not a short-lived broadcast download. */
public final class ManualRefreshJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters active;
    private Future<?> refresh;

    @Override
    public boolean onStartJob(JobParameters params) {
        ReadingStore.SavedLocation saved = ReadingStore.loadLocation(this);
        if (saved == null) {
            WidgetRefreshStatus.set(this, WidgetRefreshStatus.OPEN_APP);
            return false;
        }
        active = params;
        WidgetRefreshStatus.set(this, WidgetRefreshStatus.UPDATING);
        ReadingStore.SavedLocation target = RefreshJobService.newestAvailableLocation(this, saved);
        refresh = RefreshCoordinator.refresh(this, target.latitude(), target.longitude(), target.name(),
                new RefreshCoordinator.Callback() {
                    @Override
                    public void onSuccess(AirQualityReading reading) {
                        finish(params, WidgetRefreshStatus.CHECKED);
                    }

                    @Override
                    public void onError(Exception error) {
                        finish(params, WidgetRefreshStatus.FAILED);
                    }
                });
        return true;
    }

    private void finish(JobParameters params, String status) {
        main.post(() -> {
            if (active != params) return;
            active = null;
            refresh = null;
            // Keep the reported time/readings intact, including on network failure.
            WidgetRefreshStatus.set(this, status);
            jobFinished(params, false);
        });
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (active != null && active.getJobId() == params.getJobId()) {
            active = null;
            if (refresh != null) refresh.cancel(true);
            refresh = null;
            WidgetRefreshStatus.set(this, WidgetRefreshStatus.QUEUED);
        }
        // Connection/system interruptions resume as a job; explicit failures allow a new tap.
        return true;
    }
}
