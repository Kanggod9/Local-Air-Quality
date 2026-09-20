package com.localairquality.app.background;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.HistoryRecoveryRepository;
import com.localairquality.app.data.HistoryStore;
import com.localairquality.app.data.PollutantHistory;
import com.localairquality.app.data.ReadingStore;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Durable catch-up, independent of Activity downloads and live-data availability. */
public final class HistoryRecoveryJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters active;
    private Future<?> worker;

    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        worker = executor.submit(() -> {
            boolean retry = false;
            try {
                AirQualityReading reading = ReadingStore.loadReading(this);
                PollutantHistory.Recovery request = HistoryStore.beginRecovery(this);
                if (request != null) {
                    if (reading == null || !reading.stationId.equals(request.stationId())) {
                        throw new CancellationException("Station changed before recovery");
                    }
                    HistoryRecoveryRepository.recover(reading, request, ReadingStore.openAqKey(this), reports -> {
                        checkInterrupted();
                        if (!HistoryStore.acceptRecovery(this, request, reports)) {
                            throw new CancellationException("History changed or could not be saved");
                        }
                    });
                    checkInterrupted();
                    if (!HistoryStore.completeRecovery(this, request)) {
                        throw new CancellationException("History changed before recovery completed");
                    }
                }
            } catch (Exception error) {
                retry = !(error instanceof com.localairquality.app.data.AirQualityRepository.ApiKeyRequiredException);
                Log.w("LocalAirQuality", retry ? "History catch-up will retry" : "History requires a valid API key", error);
            }
            boolean needsRetry = retry;
            main.post(() -> {
                // A stopped job's late callback cannot finish its replacement.
                if (active != params) return;
                active = null;
                jobFinished(params, needsRetry);
            });
        });
        return true;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Recovery stopped");
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active != null && active.getJobId() == params.getJobId()) {
            active = null;
            if (worker != null) worker.cancel(true);
        }
        return true;
    }

    @Override public void onDestroy() {
        active = null;
        executor.shutdownNow();
        super.onDestroy();
    }
}
