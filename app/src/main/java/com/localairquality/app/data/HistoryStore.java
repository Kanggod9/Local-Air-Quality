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
    // Exact bytes of the file on disk. The screens, widget, notification and jobs load the history
    // many times a minute; they decode this image instead of re-reading and re-writing the file.
    private static byte[] image;

    public static synchronized PollutantHistory load(Context context) {
        if (image == null) {
            try {
                image = file(context).readFully();
            } catch (IOException missing) {
                image = new byte[0];
            }
        }
        PollutantHistory history;
        try {
            history = image.length == 0 ? new PollutantHistory()
                    : PollutantHistory.read(new ByteArrayInputStream(image));
        } catch (IOException error) {
            history = new PollutantHistory();
        }
        history.prune(System.currentTimeMillis());
        save(context, history);
        return history;
    }
    /** Stores a complete history in place of the current one. */
    public static synchronized boolean replace(Context context, PollutantHistory history) {
        return save(context, history);
    }
    public static synchronized void record(Context context, AirQualityReading reading) {
        PollutantHistory history = load(context);
        AirQualityReading primary=reading.isBackup()?reading.primary:reading;
        String country = primary.countryCode.isBlank() ? primary.countryName.trim().toLowerCase(java.util.Locale.ROOT)
                : primary.countryCode.trim().toUpperCase(java.util.Locale.ROOT);
        // Do not merge unidentifiable countries into a shared history.
        if (country.isEmpty()) return;
        long now=System.currentTimeMillis();
        history.add(country, primary.countryName, primary.stationId, primary.stationName, reading.locationName,
                primary.pollutantMeasuredAtMillis, primary.values(), now);
        if(reading.isBackup()) for(int p=0;p<6;p++) {
            double[] values=new double[6]; java.util.Arrays.fill(values,Double.NaN); values[p]=reading.values()[p];
            history.addBackup(primary.stationId,new PollutantHistory.Sample(reading.pollutantMeasuredAtMillis[p],
                    reading.stationId,reading.stationName,values,0),now);
        }
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
    public static synchronized boolean acceptBackupRecovery(Context context, PollutantHistory.Recovery request,
                                                            String source, java.util.List<PollutantHistory.Sample> reports) {
        PollutantHistory history=load(context);
        return history.acceptBackupRecovery(request,source,reports,System.currentTimeMillis()) && save(context,history);
    }
    private static boolean save(Context context, PollutantHistory history) {
        history.updateNowcasts(System.currentTimeMillis());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(image == null ? 4096 : image.length + 512);
        try {
            history.write(encoded);
        } catch (IOException error) {
            return false;
        }
        byte[] bytes = encoded.toByteArray();
        // Unchanged content needs no fsync; pruning alone rarely changes anything.
        if (java.util.Arrays.equals(bytes, image)) return true;
        AtomicFile file = file(context);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
            image = bytes;
            return true;
        } catch (IOException error) {
            file.failWrite(out);
            Log.w("LocalAirQuality", "Unable to save pollutant history", error);
            return false;
        }
    }
}




