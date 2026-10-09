package com.localairquality.app.background;

import android.content.Context;
import android.util.Log;
import com.localairquality.app.data.*;
import java.util.concurrent.*;

/** Foreground catch-up must not wait for Android's deferred JobScheduler queue.
 * The durable job shares this operation, so simultaneous triggers cannot download twice.
 */
public final class HistoryRecoveryCoordinator {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final SingleFlight<Boolean> FLIGHT = new SingleFlight<>(EXECUTOR);
    private HistoryRecoveryCoordinator() {}

    /** True means complete/no work; false keeps the durable retry pending. */
    public static CompletableFuture<Boolean> recover(Context context) {
        Context app = context.getApplicationContext();
        return FLIGHT.run(() -> {
            try {
                // A location change during a request must promptly catch up the new station too.
                for (int attempt = 0; attempt < 2; attempt++) {
                    AirQualityReading reading = ReadingStore.loadReading(app);
                    PollutantHistory.Recovery request = HistoryStore.beginRecovery(app);
                    if (request == null) return true;
                    if (reading == null || !reading.stationId.equals(request.stationId())) continue;
                    try {
                        HistoryRecoveryRepository.recover(reading, request, ReadingStore.openAqKey(app), reports -> {
                            if (!HistoryStore.acceptRecovery(app, request, reports))
                                throw new CancellationException("History session changed or save failed");
                        });
                        if (HistoryStore.completeRecovery(app, request)) return true;
                    } catch (CancellationException changed) {
                        // Never merge a previous station response into the current session.
                    }
                }
                return false;
            } catch (AirQualityRepository.ApiKeyRequiredException missingKey) {
                return true; // Retry after the user supplies a key, not in a background loop.
            } catch (Exception error) {
                Log.w("LocalAirQuality", "History catch-up will retry", error);
                return false;
            } finally {
                // Recovered hours can raise/lower the sustained alert without a new live sample.
                RefreshCoordinator.updateDisplays(app);
            }
        });
    }
}
