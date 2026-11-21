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
    private static final java.util.concurrent.ExecutorService REQUESTS = java.util.concurrent.Executors.newFixedThreadPool(2);

    private NeaRepository() {}

    // Published NEA reporting-region coordinates, usable even when metadata requests fail.
    static AirQualityReading target(double latitude, double longitude, String locationName) {
        String[] names={"central","east","north","south","west"};
        double[] lats={1.35735,1.35735,1.41803,1.29587,1.35735};
        double[] lons={103.82,103.94,103.82,103.82,103.7};
        int best=0; double distance=Double.MAX_VALUE;
        for(int i=0;i<5;i++) {
            double d=distanceKm(latitude,longitude,lats[i],lons[i]);
            if(d<distance) { distance=d; best=i; }
        }
        AirQualityReading r=new AirQualityReading();
        r.stationId="nea:"+names[best]; r.stationName=titleCase(names[best])+" reporting region";
        r.locationName=locationName; r.latitude=lats[best]; r.longitude=lons[best]; r.distanceKm=distance;
        r.countryCode="SG"; r.countryName="Singapore"; r.sourceName="Singapore National Environment Agency";
        return r;
    }

    static AirQualityReading fetch(double latitude, double longitude, String locationName) throws Exception {
        return latestOfficial(() -> fetchFrom(PSI_URL,PM25_URL,latitude,longitude,locationName),
                () -> fetchFrom(PSI_FALLBACK_URL,PM25_FALLBACK_URL,latitude,longitude,locationName),System.currentTimeMillis());
    }
    static AirQualityReading latestOfficial(BackupPolicy.Fetch first, BackupPolicy.Fetch alternate, long now) throws Exception {
        AirQualityReading primary=null;
        Exception error=null;
        try {
            primary=first.get();
            if(BackupPolicy.fresh(primary.measuredAtMillis,Math.max(now,primary.fetchedAtMillis))) return primary;
        } catch (Exception primaryError) {
            if(primaryError instanceof InterruptedException) Thread.currentThread().interrupt();
            if(Thread.currentThread().isInterrupted() || primaryError instanceof java.util.concurrent.CancellationException)
                throw new java.util.concurrent.CancellationException("Refresh cancelled");
            error=primaryError;
        }
        try {
            var fallback=alternate.get();
            return primary==null || fallback.measuredAtMillis>primary.measuredAtMillis ? fallback : primary;
        } catch(Exception fallbackError) {
            if(fallbackError instanceof InterruptedException) Thread.currentThread().interrupt();
            if(Thread.currentThread().isInterrupted() || fallbackError instanceof java.util.concurrent.CancellationException)
                throw new java.util.concurrent.CancellationException("Refresh cancelled");
            if(primary!=null) return primary;
            if(error!=null) { error.addSuppressed(fallbackError); throw error; }
            throw fallbackError;
        }
    }

    private static AirQualityReading fetchFrom(
            String psiUrl,
            String pm25Url,
            double latitude,
            double longitude,
            String locationName
    ) throws Exception {
        var psiRequest=REQUESTS.submit(() -> Network.getJsonFast(psiUrl,null));
        var pmRequest=REQUESTS.submit(() -> Network.getJsonFast(pm25Url,null));
        JSONObject psiData, pmData;
        try { psiData=payload(psiRequest.get()); pmData=payload(pmRequest.get()); }
        catch(java.util.concurrent.ExecutionException error) {
            if(error.getCause() instanceof Exception cause) throw cause;
            throw error;
        } finally { psiRequest.cancel(true); pmRequest.cancel(true); }

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

        JSONObject psiItem = latestItem(psiData.getJSONArray("items"));
        JSONObject readings = psiItem.getJSONObject("readings");
        JSONObject pmItem = latestItem(pmData.getJSONArray("items"));
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
    static JSONObject latestItem(JSONArray items) throws Exception {
        JSONObject latest=null; long time=Long.MIN_VALUE;
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.getJSONObject(i);
            long candidate=OffsetDateTime.parse(item.getString("timestamp")).toInstant().toEpochMilli();
            if(candidate>time) { latest=item; time=candidate; }
        }
        if(latest==null) throw new Exception("Official readings unavailable");
        return latest;
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



