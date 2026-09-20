package com.localairquality.app.background;

import android.Manifest;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.ReadingStore;

import java.util.Locale;

public final class RefreshJobService extends JobService {
    private java.util.concurrent.Future<?> refresh;
    private JobParameters active;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    @Override
    public boolean onStartJob(JobParameters params) {
        com.localairquality.app.data.HistoryStore.load(this);
        if (params.getJobId() == RefreshScheduler.CLEANUP_JOB_ID) return false;
        ReadingStore.SavedLocation saved = ReadingStore.loadLocation(this);
        if (saved == null) return false;
        ReadingStore.SavedLocation target = newestAvailableLocation(saved);
        active = params;
        refresh = RefreshCoordinator.refresh(this, target.latitude(), target.longitude(), target.name(),
                new RefreshCoordinator.Callback() {
            @Override
            public void onSuccess(AirQualityReading reading) { finish(params, false); }

            @Override
            public void onError(Exception error) {
                finish(params, true);
            }
        });
        return true;
    }

    private void finish(JobParameters params, boolean retry) {
        main.post(() -> {
            if (active != params) return;
            active = null;
            jobFinished(params, retry);
        });
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (active != null && active.getJobId() == params.getJobId()) {
            active = null;
            if (refresh != null) refresh.cancel(true);
        }
        return true;
    }

    private ReadingStore.SavedLocation newestAvailableLocation(ReadingStore.SavedLocation saved) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return saved;
        try {
            LocationManager manager = getSystemService(LocationManager.class);
            if (manager == null) return saved;
            Location newest = null;
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (newest == null || candidate.getTime() > newest.getTime())) {
                    newest = candidate;
                }
            }
            if (newest == null) return saved;
            Location old = new Location("saved");
            old.setLatitude(saved.latitude());
            old.setLongitude(saved.longitude());
            String name = newest.distanceTo(old) < 1000 ? saved.name()
                    : String.format(Locale.getDefault(), "%.3f, %.3f",
                    newest.getLatitude(), newest.getLongitude());
            return new ReadingStore.SavedLocation(newest.getLatitude(), newest.getLongitude(), name);
        } catch (SecurityException ignored) {
            return saved;
        }
    }
}


