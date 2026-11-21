package com.localairquality.app.background;

import android.content.Context;
import android.util.Log;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AirQualityRepository;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.notification.AirQualityNotification;
import com.localairquality.app.widget.AirQualityWidgetProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class RefreshCoordinator {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static long generation;
    private static Future<?> current;
    // Outcome of the newest request, and the callers of requests it replaced while they ran.
    private static long settled;
    private static AirQualityReading settledReading;
    private static Exception settledFailure;
    private static final List<Callback> followers = new ArrayList<>();

    private RefreshCoordinator() {}

    /** Re-evaluate rolling alerts after expiry/catch-up, even without a newer live reading. */
    public static synchronized void updateDisplays(Context context) {
        AirQualityReading cached = ReadingStore.loadReading(context);
        try {
            if (cached == null) AirQualityNotification.clear(context);
            else AirQualityNotification.show(context, cached);
        } catch (RuntimeException error) {
            Log.w("LocalAirQuality", "Notification update failed", error);
        }
        try {
            AirQualityWidgetProvider.updateAll(context, cached);
        } catch (RuntimeException error) {
            Log.w("LocalAirQuality", "Widget update failed", error);
        }
    }

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
            AirQualityReading reading = null;
            Exception failure = null;
            try {
                reading = AirQualityRepository.fetch(appContext, latitude, longitude, locationName);
            } catch (Exception error) {
                failure = error;
            }
            List<Callback> listeners = new ArrayList<>();
            synchronized (RefreshCoordinator.class) {
                if (request != generation) {
                    // A newer request replaced this one (for example the periodic job starting while
                    // the screen was updating). Its caller receives the newer outcome, not an error.
                    if (callback == null) return;
                    if (settled == generation) {
                        listeners.add(callback);
                        reading = settledReading;
                        failure = settledFailure;
                    } else {
                        followers.add(callback);
                        return;
                    }
                } else {
                    if (failure == null && Thread.currentThread().isInterrupted()) {
                        failure = new java.util.concurrent.CancellationException("Refresh cancelled");
                    }
                    if (failure == null) {
                        // A previous location's slow response must not replace newer data.
                        ReadingStore.saveLocation(appContext, latitude, longitude, locationName);
                        ReadingStore.saveReading(appContext, reading);
                        AirQualityReading reconciled = ReadingStore.loadReading(appContext);
                        if (reconciled != null) reading = reconciled;
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
                    }
                    settled = request;
                    settledReading = failure == null ? reading : null;
                    settledFailure = failure;
                    if (callback != null) listeners.add(callback);
                    listeners.addAll(followers);
                    followers.clear();
                    // Enqueue before notifying the Activity: closing it must not cancel recovery.
                    RefreshScheduler.scheduleRecovery(appContext);
                    if (failure == null) HistoryRecoveryCoordinator.recover(appContext);
                }
            }
            for (Callback listener : listeners) {
                try {
                    if (failure == null) listener.onSuccess(reading);
                    else listener.onError(failure);
                } catch (RuntimeException error) {
                    Log.w("LocalAirQuality", "Refresh listener failed", error);
                }
            }
        });
        return current;
    }
}
