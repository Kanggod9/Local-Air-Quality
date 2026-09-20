package com.localairquality.app.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class AirQualityReading {
    public static final double MISSING = Double.NaN;

    public String locationName = "Current location";
    public String stationName = "";
    public String stationId = "";
    public String sourceName = "";
    public String countryName = "";
    public String countryCode = "";
    public double latitude;
    public double longitude;
    public double distanceKm;
    public long measuredAtMillis;
    public long fetchedAtMillis;
    // Same order as PollutantHistory.LABELS; zero means no reported timestamp.
    public final long[] pollutantMeasuredAtMillis = new long[6];
    // Persisted so recovery can run after process death without another successful live fetch.
    public final int[] pollutantSensorIds = new int[6];

    // Concentrations are normalized to micrograms per cubic metre.
    public double pm25 = MISSING;
    public double pm10 = MISSING;
    public double o3 = MISSING;
    public double no2 = MISSING;
    public double co = MISSING;
    public double so2 = MISSING;

    // Legacy cache fields. AQI+ uses the displayed concentrations, not these values.
    public double pm25NowCast = MISSING;
    public double pm10NowCast = MISSING;
    public double o3EightHour = MISSING;
    public double coEightHour = MISSING;

    public int usAqi;
    public String usLevel = "No data";
    public String mainPollutant = "—";
    public int euBand;
    public String euLevel = "No data";

    public int availablePollutants() {
        int count = 0;
        if (isPresent(pm25)) count++;
        if (isPresent(pm10)) count++;
        if (isPresent(o3)) count++;
        if (isPresent(no2)) count++;
        if (isPresent(co)) count++;
        if (isPresent(so2)) count++;
        return count;
    }

    public void calculateIndices() {
        AqiCalculator.UsResult us = AqiCalculator.usAqi(this);
        usAqi = us.aqi();
        usLevel = us.level();
        mainPollutant = us.pollutant();
        AqiCalculator.EuResult eu = AqiCalculator.europeanAqi(this);
        euBand = eu.band();
        euLevel = eu.level();
    }

    public String pollutantValue(String pollutant) {
        double value = switch (pollutant) {
            case "PM2.5" -> pm25;
            case "PM10" -> pm10;
            case "O3" -> o3;
            case "NO2" -> no2;
            case "CO" -> co;
            case "SO2" -> so2;
            default -> MISSING;
        };
        if (!isPresent(value)) return "—";
        if ("CO".equals(pollutant)) {
            return String.format(Locale.getDefault(), "%.1f mg/m³", value / 1000.0);
        }
        return String.format(Locale.getDefault(), value >= 100 ? "%.0f µg/m³" : "%.1f µg/m³", value);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("locationName", locationName);
        json.put("stationName", stationName);
        json.put("stationId", stationId);
        json.put("sourceName", sourceName);
        json.put("countryName", countryName);
        json.put("countryCode", countryCode);
        json.put("latitude", latitude);
        json.put("longitude", longitude);
        json.put("distanceKm", distanceKm);
        json.put("measuredAtMillis", measuredAtMillis);
        json.put("fetchedAtMillis", fetchedAtMillis);
        org.json.JSONArray times = new org.json.JSONArray();
        for (long time : pollutantMeasuredAtMillis) times.put(time);
        json.put("pollutantMeasuredAtMillis", times);
        org.json.JSONArray sensors = new org.json.JSONArray();
        for (int sensor : pollutantSensorIds) sensors.put(sensor);
        json.put("pollutantSensorIds", sensors);
        putDouble(json, "pm25", pm25);
        putDouble(json, "pm10", pm10);
        putDouble(json, "o3", o3);
        putDouble(json, "no2", no2);
        putDouble(json, "co", co);
        putDouble(json, "so2", so2);
        putDouble(json, "pm25NowCast", pm25NowCast);
        putDouble(json, "pm10NowCast", pm10NowCast);
        putDouble(json, "o3EightHour", o3EightHour);
        putDouble(json, "coEightHour", coEightHour);
        json.put("usAqi", usAqi);
        json.put("usLevel", usLevel);
        json.put("mainPollutant", mainPollutant);
        json.put("euBand", euBand);
        json.put("euLevel", euLevel);
        return json;
    }

    public static AirQualityReading fromJson(String encoded) throws JSONException {
        JSONObject json = new JSONObject(encoded);
        AirQualityReading reading = new AirQualityReading();
        reading.locationName = json.optString("locationName", "Current location");
        reading.stationName = json.optString("stationName", "");
        reading.stationId = json.optString("stationId", "");
        reading.sourceName = json.optString("sourceName", "");
        reading.countryName = json.optString("countryName", "");
        reading.countryCode = json.optString("countryCode", "");
        reading.latitude = json.optDouble("latitude", 0);
        reading.longitude = json.optDouble("longitude", 0);
        reading.distanceKm = json.optDouble("distanceKm", 0);
        reading.measuredAtMillis = json.optLong("measuredAtMillis", 0);
        reading.fetchedAtMillis = json.optLong("fetchedAtMillis", 0);
        org.json.JSONArray times = json.optJSONArray("pollutantMeasuredAtMillis");
        if (times != null) for (int p = 0; p < 6; p++) {
            reading.pollutantMeasuredAtMillis[p] = times.optLong(p, 0);
        }
        org.json.JSONArray sensors = json.optJSONArray("pollutantSensorIds");
        if (sensors != null) for (int p = 0; p < 6; p++) {
            reading.pollutantSensorIds[p] = sensors.optInt(p, 0);
        }
        reading.pm25 = optDouble(json, "pm25");
        reading.pm10 = optDouble(json, "pm10");
        reading.o3 = optDouble(json, "o3");
        reading.no2 = optDouble(json, "no2");
        reading.co = optDouble(json, "co");
        reading.so2 = optDouble(json, "so2");
        reading.pm25NowCast = optDouble(json, "pm25NowCast");
        reading.pm10NowCast = optDouble(json, "pm10NowCast");
        reading.o3EightHour = optDouble(json, "o3EightHour");
        reading.coEightHour = optDouble(json, "coEightHour");
        reading.usAqi = json.optInt("usAqi", 0);
        reading.usLevel = json.optString("usLevel", "No data");
        reading.mainPollutant = json.optString("mainPollutant", "—");
        reading.euBand = json.optInt("euBand", 0);
        reading.euLevel = json.optString("euLevel", "No data");
        // Recompute derived values so an app update also corrects older cached readings.
        reading.calculateIndices();
        return reading;
    }

    public static boolean isPresent(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0;
    }

    private static void putDouble(JSONObject json, String key, double value) throws JSONException {
        if (isPresent(value)) json.put(key, value);
    }

    private static double optDouble(JSONObject json, String key) {
        return json.has(key) ? json.optDouble(key, MISSING) : MISSING;
    }
}




