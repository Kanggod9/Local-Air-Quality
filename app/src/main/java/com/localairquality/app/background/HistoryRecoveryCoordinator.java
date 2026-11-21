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
                    if (reading == null || !reading.historyStationId().equals(request.stationId())) continue;
                    try {
                        boolean complete=true;
                        try {
                            HistoryRecoveryRepository.recover(reading.isBackup()?reading.primary:reading, request, ReadingStore.openAqKey(app), reports -> {
                                if (!HistoryStore.acceptRecovery(app, request, reports))
                                    throw new CancellationException("History session changed or save failed");
                            });
                        } catch(CancellationException changed) { throw changed; }
                        catch(Exception primaryOffline) { complete=false; }
                        if(reading.isBackup()) {
                            PollutantHistory history=HistoryStore.load(app);
                            long from=history.backups.stream().filter(b -> b.primaryStationId().equals(request.stationId())
                                    && b.reading().stationId().equals(reading.stationId))
                                    .mapToLong(b -> Math.floorDiv(b.reading().measuredAt(),NowCast.HOUR)*NowCast.HOUR)
                                    .min().orElse(request.to());
                            long lastOzone=history.ozoneCalibration.stream().filter(o -> o.stationId().equals(reading.stationId))
                                    .mapToLong(PollutantHistory.Ozone::measuredAt).max().orElse(0);
                            long ozoneFrom=lastOzone==0 ? request.to()-PollutantHistory.OZONE_WINDOW
                                    : Math.max(request.to()-PollutantHistory.OZONE_WINDOW,lastOzone-2*NowCast.HOUR);
                            var backupRequest=new PollutantHistory.Recovery(request.countryId(),request.sessionStart(),
                                    reading.stationId,request.stationSince(),Math.max(request.from(),from),request.to(),ozoneFrom);
                            var acceptanceRequest=new PollutantHistory.Recovery(request.countryId(),request.sessionStart(),
                                    request.stationId(),request.stationSince(),backupRequest.from(),request.to(),ozoneFrom);
                            try {
                                HistoryRecoveryRepository.recover(reading,backupRequest,ReadingStore.openAqKey(app),reports -> {
                                    if(!HistoryStore.acceptBackupRecovery(app,acceptanceRequest,reading.stationId,reports))
                                        throw new CancellationException("History session changed or save failed");
                                });
                            } catch(CancellationException changed) { throw changed; }
                            catch(Exception backupOffline) { complete=false; }
                        }
                        if(complete && HistoryStore.completeRecovery(app, request)) return true;
                        return false;
                    } catch (CancellationException changed) {
                        // Never merge a previous station response into the current session.
                    }
                }
                return false;
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
