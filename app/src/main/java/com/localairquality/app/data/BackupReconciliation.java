package com.localairquality.app.data;

/** Background catch-up can replace the visible cached backup without another live fetch. */
public final class BackupReconciliation {
    private BackupReconciliation() {}
    public static AirQualityReading current(AirQualityReading reading, PollutantHistory history) {
        if(!reading.isBackup()) return reading;
        long hour=Math.floorDiv(reading.measuredAtMillis,NowCast.HOUR);
        var report=history.samples.stream().filter(s -> s.stationId().equals(reading.historyStationId())
                && Math.floorDiv(s.measuredAt(),NowCast.HOUR)==hour)
                .max(java.util.Comparator.comparingLong(PollutantHistory.Sample::measuredAt)).orElse(null);
        if(report==null) return reading;
        try {
            var result=AirQualityReading.fromJson(reading.primary.toJson().toString());
            result.backupStatus=""; result.backupKeyRequired=false;
            result.locationName=reading.locationName; result.fetchedAtMillis=reading.fetchedAtMillis;
            result.measuredAtMillis=report.measuredAt();
            for(int p=0;p<6;p++) {
                result.setValue(p,report.values()[p]);
                result.pollutantMeasuredAtMillis[p]=AirQualityReading.isPresent(report.values()[p])?report.measuredAt():0;
            }
            result.calculateIndices(); return result;
        } catch(Exception invalidCache) { return reading; }
    }
}
