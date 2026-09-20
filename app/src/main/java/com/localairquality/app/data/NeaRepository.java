package com.localairquality.app.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class NeaRepository {
    private static final String PSI_URL = "https://api.data.gov.sg/v1/environment/psi";
    private static final String PM25_URL = "https://api.data.gov.sg/v1/environment/pm25";
    private static final String PSI_FALLBACK_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi";
    private static final String PM25_FALLBACK_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25";

    private NeaRepository() {}

    static AirQualityReading fetch(double latitude, double longitude, String locationName) throws Exception {
        try {
            return fetchFrom(PSI_URL, PM25_URL, latitude, longitude, locationName);
        } catch (Exception primaryError) {
            try {
                return fetchFrom(PSI_FALLBACK_URL, PM25_FALLBACK_URL,
                        latitude, longitude, locationName);
            } catch (Exception fallbackError) {
                primaryError.addSuppressed(fallbackError);
                throw primaryError;
            }
        }
    }

    private static AirQualityReading fetchFrom(
            String psiUrl,
            String pm25Url,
            double latitude,
            double longitude,
            String locationName
    ) throws Exception {
        JSONObject psiData = payload(Network.getJson(psiUrl, null));
        JSONObject pmData = payload(Network.getJson(pm25Url, null));

        Map<String, Region> regions = parseRegions(metadata(psiData));
        Region nearest = null;
        double distance = Double.MAX_VALUE;
        for (Region region : regions.values()) {
            double candidate = distanceKm(latitude, longitude, region.latitude, region.longitude);
            if (candidate < distance) {
                distance = candidate;
                nearest = region;
            }
        }
        if (nearest == null) throw new AirQualityRepository.NoStationException("No official station data nearby");

        JSONObject psiItem = psiData.getJSONArray("items").getJSONObject(0);
        JSONObject readings = psiItem.getJSONObject("readings");
        JSONObject pmItem = pmData.getJSONArray("items").getJSONObject(0);
        JSONObject currentPm = pmItem.getJSONObject("readings").getJSONObject("pm25_one_hourly");
        String region = nearest.name;

        AirQualityReading result = new AirQualityReading();
        result.locationName = locationName;
        result.stationId = "nea:" + region;
        result.stationName = titleCase(region) + " reporting region";
        result.sourceName = "Singapore National Environment Agency";
        result.countryName = "Singapore";
        result.countryCode = "SG";
        result.latitude = nearest.latitude;
        result.longitude = nearest.longitude;
        result.distanceKm = distance;
        result.pm25 = currentPm.optDouble(region, Double.NaN);
        result.pm10 = read(readings, "pm10_twenty_four_hourly", region);
        result.o3 = read(readings, "o3_eight_hour_max", region);
        result.no2 = read(readings, "no2_one_hour_max", region);
        double coMilligrams = read(readings, "co_eight_hour_max", region);
        result.co = AirQualityReading.isPresent(coMilligrams) ? coMilligrams * 1000.0 : Double.NaN;
        result.so2 = read(readings, "so2_twenty_four_hourly", region);
        result.pm25NowCast = read(readings, "pm25_twenty_four_hourly", region);
        result.pm10NowCast = result.pm10;
        result.o3EightHour = result.o3;
        result.coEightHour = result.co;
        result.measuredAtMillis = OffsetDateTime.parse(pmItem.getString("timestamp")).toInstant().toEpochMilli();
        long psiTime = OffsetDateTime.parse(psiItem.getString("timestamp")).toInstant().toEpochMilli();
        java.util.Arrays.fill(result.pollutantMeasuredAtMillis, psiTime);
        result.pollutantMeasuredAtMillis[0] = result.measuredAtMillis;
        result.fetchedAtMillis = System.currentTimeMillis();
        result.calculateIndices();
        return result;
    }

    private static JSONObject payload(JSONObject root) {
        JSONObject data = root.optJSONObject("data");
        return data == null ? root : data;
    }

    private static JSONArray metadata(JSONObject data) throws Exception {
        JSONArray result = data.optJSONArray("regionMetadata");
        if (result == null) result = data.optJSONArray("region_metadata");
        if (result == null) throw new Exception("Official station metadata is unavailable");
        return result;
    }

    private static Map<String, Region> parseRegions(JSONArray array) throws Exception {
        Map<String, Region> result = new HashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            JSONObject point = item.optJSONObject("labelLocation");
            if (point == null) point = item.getJSONObject("label_location");
            Region region = new Region(item.getString("name"),
                    point.getDouble("latitude"), point.getDouble("longitude"));
            result.put(region.name, region);
        }
        return result;
    }

    private static double read(JSONObject readings, String key, String region) {
        JSONObject values = readings.optJSONObject(key);
        return values == null ? Double.NaN : values.optDouble(region, Double.NaN);
    }

    private static String titleCase(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record Region(String name, double latitude, double longitude) {}
}



