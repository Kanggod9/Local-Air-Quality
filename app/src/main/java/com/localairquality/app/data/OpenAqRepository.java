package com.localairquality.app.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OpenAqRepository {
    private static final String BASE = "https://api.openaq.org/v3";
    private static final List<String> POLLUTANTS = List.of("pm25", "pm10", "o3", "no2", "co", "so2");

    private OpenAqRepository() {}

    static AirQualityReading fetch(
            double latitude,
            double longitude,
            String locationName,
            String apiKey
    ) throws Exception {
        Map<String, String> headers = Map.of("X-API-Key", apiKey);
        String coordinates = String.format(Locale.US, "%.4f,%.4f", latitude, longitude);
        String locationsUrl = BASE + "/locations?coordinates=" + coordinates
                + "&radius=25000&limit=100&mobile=false";
        JSONArray locations = Network.getJson(locationsUrl, headers).getJSONArray("results");
        Station station = selectStation(locations, latitude, longitude);
        if (station == null) {
            throw new AirQualityRepository.NoStationException(
                    "No current government monitor within 25 km");
        }

        JSONObject latestRoot = Network.getJson(
                BASE + "/locations/" + station.id + "/latest?limit=100", headers);
        JSONArray latest = latestRoot.getJSONArray("results");
        Map<String, Measurement> measurements = parseLatest(latest, station.sensors);
        if (measurements.isEmpty()) {
            throw new AirQualityRepository.NoStationException("The nearest government monitor has no current data");
        }

        AirQualityReading result = new AirQualityReading();
        result.locationName = locationName;
        result.stationId = "openaq:" + station.id;
        result.stationName = station.name;
        result.sourceName = station.source;
        result.countryName = station.country;
        result.countryCode = station.countryCode;
        result.latitude = station.latitude;
        result.longitude = station.longitude;
        result.distanceKm = station.distanceKm;
        result.pm25 = value(measurements, "pm25");
        result.pm10 = value(measurements, "pm10");
        result.o3 = value(measurements, "o3");
        result.no2 = value(measurements, "no2");
        result.co = value(measurements, "co");
        result.so2 = value(measurements, "so2");
        result.pm25NowCast = result.pm25;
        result.pm10NowCast = result.pm10;
        result.o3EightHour = result.o3;
        result.coEightHour = result.co;
        result.measuredAtMillis = measurements.values().stream()
                .mapToLong(item -> item.timestampMillis).max().orElse(System.currentTimeMillis());
        for (int p = 0; p < POLLUTANTS.size(); p++) {
            Measurement measurement = measurements.get(POLLUTANTS.get(p));
            result.pollutantMeasuredAtMillis[p] = measurement == null ? 0 : measurement.timestampMillis;
            result.pollutantSensorIds[p] = measurement == null ? 0 : measurement.sensorId;
            if (measurement == null) {
                String parameterName = POLLUTANTS.get(p);
                result.pollutantSensorIds[p] = station.sensors.entrySet().stream()
                        .filter(entry -> entry.getValue().name.equals(parameterName))
                        .mapToInt(Map.Entry::getKey).min().orElse(0);
            }
        }
        result.fetchedAtMillis = System.currentTimeMillis();
        result.calculateIndices();
        return result;
    }

    private static Station selectStation(JSONArray locations, double latitude, double longitude) throws Exception {
        List<Station> candidates = new ArrayList<>();
        long freshAfter = System.currentTimeMillis() - 48L * 60L * 60L * 1000L;
        for (int i = 0; i < locations.length(); i++) {
            JSONObject item = locations.getJSONObject(i);
            if (item.optBoolean("isMobile", true) || !item.optBoolean("isMonitor", false)) continue;
            if (!isGovernmentMonitor(item.optJSONArray("instruments"))) continue;
            JSONObject coordinates = item.optJSONObject("coordinates");
            if (coordinates == null || coordinates.isNull("latitude") || coordinates.isNull("longitude")) continue;
            long lastMeasurement = parseTime(item.optJSONObject("datetimeLast"));
            if (lastMeasurement > 0 && lastMeasurement < freshAfter) continue;

            Map<Integer, Parameter> sensors = parseSensors(item.optJSONArray("sensors"));
            if (sensors.isEmpty()) continue;
            double stationLat = coordinates.getDouble("latitude");
            double stationLon = coordinates.getDouble("longitude");
            String owner = nestedName(item, "owner");
            String provider = nestedName(item, "provider");
            String source = isUsefulOwner(owner) ? owner : provider;
            Station candidate = new Station(
                    item.getInt("id"),
                    item.optString("name", "Government monitor"),
                    "OpenAQ • " + source,
                    nestedName(item, "country"),
                    item.optJSONObject("country") == null ? "" : item.getJSONObject("country").optString("code", ""),
                    stationLat,
                    stationLon,
                    NeaRepository.distanceKm(latitude, longitude, stationLat, stationLon),
                    sensors
            );
            candidates.add(candidate);
        }
        return candidates.stream().min(Comparator.comparingDouble(item -> item.distanceKm)).orElse(null);
    }

    private static boolean isGovernmentMonitor(JSONArray instruments) {
        if (instruments == null) return false;
        for (int i = 0; i < instruments.length(); i++) {
            String name = instruments.optJSONObject(i) == null ? ""
                    : instruments.optJSONObject(i).optString("name", "").toLowerCase(Locale.ROOT);
            if (name.contains("government monitor") || name.contains("reference monitor")) return true;
        }
        return false;
    }

    private static Map<Integer, Parameter> parseSensors(JSONArray sensors) {
        Map<Integer, Parameter> result = new HashMap<>();
        if (sensors == null) return result;
        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;
            JSONObject parameter = sensor.optJSONObject("parameter");
            if (parameter == null) continue;
            String name = normalizeName(parameter.optString("name", ""));
            if (!POLLUTANTS.contains(name)) continue;
            result.put(sensor.optInt("id"), new Parameter(name, parameter.optString("units", "µg/m³")));
        }
        return result;
    }

    static int[] recoverySensorIds(JSONArray sensors) {
        Map<Integer, Parameter> available = parseSensors(sensors);
        int[] result = new int[6];
        for (int p = 0; p < result.length; p++) {
            String name = POLLUTANTS.get(p);
            result[p] = available.entrySet().stream()
                    .filter(entry -> entry.getValue().name.equals(name) && entry.getKey() > 0)
                    .mapToInt(Map.Entry::getKey).min().orElse(0);
        }
        return result;
    }

    private static Map<String, Measurement> parseLatest(JSONArray latest, Map<Integer, Parameter> sensors) {
        Map<String, Measurement> result = new HashMap<>();
        for (int i = 0; i < latest.length(); i++) {
            JSONObject item = latest.optJSONObject(i);
            if (item == null) continue;
            int sensorId = item.optInt("sensorsId", -1);
            Parameter parameter = sensors.get(sensorId);
            if (parameter == null) continue;
            long timestamp = parseTime(item.optJSONObject("datetime"));
            double value = toMicrograms(item.optDouble("value", Double.NaN), parameter.units, parameter.name);
            if (!AirQualityReading.isPresent(value)) continue;
            Measurement existing = result.get(parameter.name);
            if (existing == null || timestamp > existing.timestampMillis) {
                result.put(parameter.name, new Measurement(sensorId, parameter, value, timestamp));
            }
        }
        return result;
    }

    static double toMicrograms(double value, String units, String pollutant) {
        if (!AirQualityReading.isPresent(value)) return Double.NaN;
        String normalized = units.toLowerCase(Locale.ROOT)
                .replace("μ", "u").replace("µ", "u").replace("³", "3").replace(" ", "");
        if (normalized.contains("mg/m3")) return value * 1000.0;
        if (normalized.contains("ng/m3")) return value / 1000.0;
        if (normalized.equals("ppm")) return value * molecularWeight(pollutant) / 24.45 * 1000.0;
        if (normalized.equals("ppb")) return value * molecularWeight(pollutant) / 24.45;
        return value;
    }

    private static double molecularWeight(String pollutant) {
        return switch (pollutant) {
            case "o3" -> 48.00;
            case "no2" -> 46.0055;
            case "co" -> 28.01;
            case "so2" -> 64.066;
            default -> 1.0;
        };
    }

    private static String normalizeName(String value) {
        return value.toLowerCase(Locale.ROOT).replace(".", "").replace("_", "").replace("-", "");
    }

    static long parseTime(JSONObject datetime) {
        if (datetime == null) return 0;
        String value = datetime.optString("utc", datetime.optString("local", ""));
        if (value.isEmpty()) return 0;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignoredAgain) {
                return 0;
            }
        }
    }

    private static String nestedName(JSONObject item, String key) {
        JSONObject nested = item.optJSONObject(key);
        return nested == null ? "" : nested.optString("name", "");
    }

    private static boolean isUsefulOwner(String owner) {
        String normalized = owner.toLowerCase(Locale.ROOT);
        return !owner.isBlank() && !normalized.contains("unknown") && !normalized.equals("n/a");
    }

    private static double value(Map<String, Measurement> values, String key) {
        Measurement item = values.get(key);
        return item == null ? Double.NaN : item.value;
    }

    private record Parameter(String name, String units) {}
    private record Station(int id, String name, String source, String country, String countryCode,
                           double latitude, double longitude, double distanceKm,
                           Map<Integer, Parameter> sensors) {}
    private static final class Measurement {
        final int sensorId;
        final Parameter parameter;
        final double value;
        final long timestampMillis;
        Measurement(int sensorId, Parameter parameter, double value, long timestampMillis) {
            this.sensorId = sensorId;
            this.parameter = parameter;
            this.value = value;
            this.timestampMillis = timestampMillis;
        }
    }
}





