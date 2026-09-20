package com.localairquality.app.background;

import android.content.Context;
import android.util.Log;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AirQualityRepository;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.notification.AirQualityNotification;
import com.localairquality.app.widget.AirQualityWidgetProvider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class RefreshCoordinator {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static long generation;
    private static Future<?> current;

    private RefreshCoordinator() {}

    public interface Callback {
        void onSuccess(AirQualityReading reading);
        void onError(Exception error);
    }

    public static synchronized void refreshIfStale(Context context) {
        // History retries must not depend on the live cache being stale.
        RefreshScheduler.scheduleRecovery(context);
        if (current != null && !current.isDone()) return;
        AirQualityReading cached = ReadingStore.loadReading(context);
        long now = System.currentTimeMillis();
        if (cached != null && now >= cached.fetchedAtMillis && now - cached.fetchedAtMillis < 15L * 60 * 1000) return;
        if (ReadingStore.loadLocation(context) != null) refreshSavedLocation(context, null);
    }
    public static void refreshSavedLocation(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        ReadingStore.SavedLocation location = ReadingStore.loadLocation(appContext);
        if (location == null) {
            if (callback != null) callback.onError(new IllegalStateException("Location is not available"));
            return;
        }
        refresh(appContext, location.latitude(), location.longitude(), location.name(), callback);
    }

    public static synchronized Future<?> refresh(
            Context context,
            double latitude,
            double longitude,
            String locationName,
            Callback callback
    ) {
        Context appContext = context.getApplicationContext();
        long request = ++generation;
        ReadingStore.saveLocation(appContext, latitude, longitude, locationName);
        current = EXECUTOR.submit(() -> {
            AirQualityReading reading;
            try {
                reading = AirQualityRepository.fetch(
                        appContext, latitude, longitude, locationName);
            } catch (Exception error) {
                RefreshScheduler.scheduleRecovery(appContext);
                if (callback != null) callback.onError(error);
                return;
            }
            synchronized (RefreshCoordinator.class) {
                // A previous location's slow response must not replace newer data.
                if (request == generation && !Thread.currentThread().isInterrupted()) {
                    ReadingStore.saveLocation(appContext, latitude, longitude, locationName);
                    ReadingStore.saveReading(appContext, reading);
                    try {
                        AirQualityNotification.show(appContext, reading);
                    } catch (RuntimeException error) {
                        Log.w("LocalAirQuality", "Notification update failed", error);
                    }
                    try {
                        AirQualityWidgetProvider.updateAll(appContext, reading);
                    } catch (RuntimeException error) {
                        Log.w("LocalAirQuality", "Widget update failed", error);
                    }
                } else {
                    if (callback != null) callback.onError(
                            new java.util.concurrent.CancellationException("Refresh superseded"));
                    return;
                }
            }
            // Enqueue before notifying the Activity: closing it must not cancel recovery.
            RefreshScheduler.scheduleRecovery(appContext);
            if (callback != null) callback.onSuccess(reading);
        });
        return current;
    }
}




