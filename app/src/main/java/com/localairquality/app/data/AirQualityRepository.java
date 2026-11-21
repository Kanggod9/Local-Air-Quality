package com.localairquality.app.data;

import android.content.Context;

public final class AirQualityRepository {
    private AirQualityRepository() {}

    public static AirQualityReading fetch(
            Context context,
            double latitude,
            double longitude,
            String locationName
    ) throws Exception {
        String key = ReadingStore.openAqKey(context);
        AirQualityReading previous=ReadingStore.loadReading(context);
        if (isSingapore(latitude, longitude)) {
            AirQualityReading target=NeaRepository.target(latitude,longitude,locationName);
            if(previous!=null && previous.historyStationId().equals(target.stationId))
                target=previous.isBackup()?previous.primary:previous;
            AirQualityReading primary=target;
            return BackupPolicy.fetch(primary,previous,!key.isEmpty(),
                    () -> NeaRepository.fetch(latitude,longitude,locationName),
                    () -> OpenAqRepository.fetchAvailable(latitude,longitude,locationName,key,"",System::currentTimeMillis,OpenAqRepository.LIVE),
                    System.currentTimeMillis());
        }
        if (key.isEmpty()) throw new ApiKeyRequiredException("OpenAQ API key required");
        AirQualityReading known=previous==null?null:previous.isBackup()?previous.primary:previous;
        if(known!=null && (!known.stationId.startsWith("openaq:")
                || NeaRepository.distanceKm(latitude,longitude,known.latitude,known.longitude)>25)) known=null;
        AirQualityReading target=known;
        try {
            var nearest=OpenAqRepository.nearestTarget(latitude,longitude,locationName,key);
            double knownDistance=known==null?Double.MAX_VALUE:NeaRepository.distanceKm(latitude,longitude,known.latitude,known.longitude);
            if(known==null || (!known.stationId.equals(nearest.stationId)
                    && (previous==null || !previous.isBackup() || nearest.distanceKm<knownDistance))) target=nearest;
        } catch(java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch(Exception unavailable) { if(target==null) throw unavailable; }
        if(target!=null) {
            AirQualityReading primary=target;
            return BackupPolicy.fetch(primary,previous,true,
                    () -> OpenAqRepository.fetchKnown(primary,latitude,longitude,locationName,key),
                    () -> OpenAqRepository.fetchAvailable(latitude,longitude,locationName,key,primary.stationId,System::currentTimeMillis,OpenAqRepository.LIVE),
                    System.currentTimeMillis());
        }
        return OpenAqRepository.fetch(latitude, longitude, locationName, key);
    }

    private static boolean isSingapore(double latitude, double longitude) {
        return latitude >= 1.12 && latitude <= 1.50 && longitude >= 103.55 && longitude <= 104.10;
    }

    public static class ApiKeyRequiredException extends Exception {
        public ApiKeyRequiredException(String message) { super(message); }
    }

    public static class NoStationException extends Exception {
        public NoStationException(String message) { super(message); }
    }
}

