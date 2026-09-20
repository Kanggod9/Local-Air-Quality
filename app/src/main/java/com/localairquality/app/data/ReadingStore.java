package com.localairquality.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class ReadingStore {
    private static final String PREFS = "air_quality";
    private static final String KEY_READING = "reading";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_HAS_LOCATION = "has_location";
    private static final String KEY_LOCATION_NAME = "location_name";
    private static final String KEY_OPENAQ = "openaq_key";

    private ReadingStore() {}

    public static void saveReading(Context context, AirQualityReading reading) {
        HistoryStore.record(context, reading);
        try {
            prefs(context).edit().putString(KEY_READING, reading.toJson().toString()).apply();
        } catch (Exception ignored) {
            // A failed cache write must not hide a successful live result.
        }
    }

    public static AirQualityReading loadReading(Context context) {
        String encoded = prefs(context).getString(KEY_READING, null);
        if (encoded == null) return null;
        try {
            return AirQualityReading.fromJson(encoded);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void saveLocation(Context context, double latitude, double longitude, String name) {

        prefs(context).edit()
                .putBoolean(KEY_HAS_LOCATION, true)
                .putLong(KEY_LATITUDE, Double.doubleToRawLongBits(latitude))
                .putLong(KEY_LONGITUDE, Double.doubleToRawLongBits(longitude))
                .putString(KEY_LOCATION_NAME, name == null ? "Current location" : name)
                .apply();
    }

    public static SavedLocation loadLocation(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.getBoolean(KEY_HAS_LOCATION, false)) return null;
        double latitude = Double.longBitsToDouble(preferences.getLong(KEY_LATITUDE, 0));
        double longitude = Double.longBitsToDouble(preferences.getLong(KEY_LONGITUDE, 0));
        return new SavedLocation(latitude, longitude,
                preferences.getString(KEY_LOCATION_NAME, "Current location"));
    }

    public static void saveOpenAqKey(Context context, String key) {
        prefs(context).edit().putString(KEY_OPENAQ, key == null ? "" : key.trim()).apply();
    }

    public static String openAqKey(Context context) {
        return prefs(context).getString(KEY_OPENAQ, "").trim();
    }

    public static boolean hasOpenAqKey(Context context) {
        return !openAqKey(context).isEmpty();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public record SavedLocation(double latitude, double longitude, String name) {}
}



