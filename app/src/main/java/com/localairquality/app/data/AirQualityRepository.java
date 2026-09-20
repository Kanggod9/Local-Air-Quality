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
        if (isSingapore(latitude, longitude)) {
            return NeaRepository.fetch(latitude, longitude, locationName);
        }
        String key = ReadingStore.openAqKey(context);
        if (key.isEmpty()) throw new ApiKeyRequiredException("OpenAQ API key required");
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

