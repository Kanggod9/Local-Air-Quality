package com.localairquality.app.data;

import java.util.concurrent.CancellationException;

/** Provider checks are repeatable on every refresh; HTTP success alone does not prove freshness. */
public final class BackupPolicy {
    public static final long MAX_AGE = 3 * NowCast.HOUR;
    @FunctionalInterface public interface Fetch { AirQualityReading get() throws Exception; }
    private BackupPolicy() {}

    public static boolean fresh(long time, long now) {
        return time > 0 && time <= now && time >= now-MAX_AGE;
    }
    static AirQualityReading freshValues(AirQualityReading reading, long now) {
        long latest=0;
        double[] values=reading.values();
        for(int p=0;p<6;p++) {
            long time=reading.pollutantMeasuredAtMillis[p];
            if (!fresh(time,now) || !AirQualityReading.isPresent(values[p])) {
                reading.setValue(p,Double.NaN); reading.pollutantMeasuredAtMillis[p]=0;
            } else latest=Math.max(latest,time);
        }
        reading.measuredAtMillis=latest; reading.calculateIndices();
        return reading;
    }
    private static void cancellation(Exception error) {
        if (error instanceof CancellationException || Thread.currentThread().isInterrupted())
            throw new CancellationException("Refresh cancelled");
    }
    public static AirQualityReading fetch(AirQualityReading target, AirQualityReading previous,
                                         boolean hasKey, Fetch official, Fetch backup, long now) throws Exception {
        AirQualityReading primary=null;
        Exception failure=null;
        try {
            primary=official.get();
            now=Math.max(now,primary.fetchedAtMillis);
            if (fresh(primary.measuredAtMillis,now) && primary.availablePollutants()>0) {
                freshValues(primary,now);
                if(primary.availablePollutants()>0) return primary;
            }
        } catch(Exception error) { cancellation(error); failure=error; }
        if(primary!=null) target=primary;
        String reason=failure==null ? "Official readings delayed" : "Official source unavailable";
        if(hasKey) {
            try {
                AirQualityReading replacement=backup.get();
                now=Math.max(now,replacement.fetchedAtMillis);
                freshValues(replacement,now);
                if(replacement.availablePollutants()==0) throw new AirQualityRepository.NoStationException("No fresh backup nearby");
                replacement.primary=target; replacement.backupStatus=reason;
                return replacement;
            } catch(Exception error) {
                cancellation(error);
                reason += error instanceof AirQualityRepository.ApiKeyRequiredException
                        ? " · Check OpenAQ key" : " · No fresh backup available";
                if(error instanceof AirQualityRepository.ApiKeyRequiredException) hasKey=false;
                if(failure==null) failure=error;
            }
        } else reason += " · Add OpenAQ key for backup";
        AirQualityReading retained=primary!=null && primary.availablePollutants()>0 ? primary : previous;
        if(retained!=null && retained.historyStationId().equals(target.stationId)
                && retained.measuredAtMillis>0 && retained.measuredAtMillis>=now-PollutantHistory.WINDOW
                && retained.measuredAtMillis<=now) {
            retained.backupStatus=reason; retained.backupKeyRequired=!hasKey;
            // Keep measurement time unchanged; this is an explicit old-reading result.
            retained.fetchedAtMillis=now;
            return retained;
        }
        if(!hasKey) throw new AirQualityRepository.ApiKeyRequiredException("OpenAQ key required for backup");
        throw failure==null ? new AirQualityRepository.NoStationException("No fresh official or backup readings") : failure;
    }
}
