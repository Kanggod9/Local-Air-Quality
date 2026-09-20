package com.localairquality.app.data;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import java.io.*;

/** All read/modify/write operations share a lock across foreground and background refreshes. */
public final class HistoryStore {
    private HistoryStore() {}
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "pollutant-history.bin"));
    }
    public static synchronized PollutantHistory load(Context context) {
        PollutantHistory history;
        try (InputStream in = file(context).openRead()) {
            history = PollutantHistory.read(in);
        } catch (IOException error) {
            history = new PollutantHistory();
        }
        history.prune(System.currentTimeMillis());
        save(context, history);
        return history;
    }
    public static synchronized void record(Context context, AirQualityReading reading) {
        PollutantHistory history = load(context);
        String country = reading.countryCode.isBlank() ? reading.countryName.trim().toLowerCase(java.util.Locale.ROOT)
                : reading.countryCode.trim().toUpperCase(java.util.Locale.ROOT);
        // Do not merge unidentifiable countries into a shared history.
        if (country.isEmpty()) return;
        history.add(country, reading.countryName, reading.stationId, reading.stationName, reading.locationName,
                reading.pollutantMeasuredAtMillis, new double[]{reading.pm25, reading.pm10, reading.o3,
                        reading.no2, reading.co, reading.so2}, System.currentTimeMillis());
        save(context, history);
    }
    public static synchronized PollutantHistory.Recovery beginRecovery(Context context) {
        PollutantHistory history = load(context);
        PollutantHistory.Recovery request = history.beginRecovery(System.currentTimeMillis());
        if (request != null) save(context, history);
        return request;
    }
    public static synchronized boolean acceptRecovery(Context context, PollutantHistory.Recovery request,
                                                   java.util.List<PollutantHistory.Sample> reports) {
        PollutantHistory history = load(context);
        return history.acceptRecovery(request, reports, System.currentTimeMillis()) && save(context, history);
    }
    public static synchronized boolean completeRecovery(Context context, PollutantHistory.Recovery request) {
        PollutantHistory history = load(context);
        return history.completeRecovery(request) && save(context, history);
    }
    private static boolean save(Context context, PollutantHistory history) {
        AtomicFile file = file(context);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            history.write(out);
            file.finishWrite(out);
            return true;
        } catch (IOException error) {
            file.failWrite(out);
            Log.w("LocalAirQuality", "Unable to save pollutant history", error);
            return false;
        }
    }
}




