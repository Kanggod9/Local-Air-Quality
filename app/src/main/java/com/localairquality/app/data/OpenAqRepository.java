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

    private static final long LOCATIONS_TTL = 20L * 60 * 1000;
    private static String listUrl;
    private static JSONObject listResponse;
    private static long listFetchedAt;

    private OpenAqRepository() {}

    /** Live requests. Station metadata rarely changes, yet one refresh could download the same
     *  nearby-station list twice (target lookup, then backup search) and the station again by id. */
    static final HistoryRecoveryRepository.JsonFetcher LIVE = OpenAqRepository::liveGet;

    private static JSONObject liveGet(String url, Map<String, String> headers) throws Exception {
        boolean stationList = url.startsWith(BASE + "/locations?");
        if (stationList) synchronized (OpenAqRepository.class) {
            if (url.equals(listUrl) && Math.abs(System.currentTimeMillis() - listFetchedAt) < LOCATIONS_TTL) {
                return listResponse;
            }
        }
        JSONObject response = Network.getJsonFast(url, headers);
        if (stationList) synchronized (OpenAqRepository.class) {
            listUrl = url; listResponse = response; listFetchedAt = System.currentTimeMillis();
        }
        return response;
    }

    /** The remembered list entry for one station, in the same shape as /locations/{id}. */
    private static synchronized JSONArray rememberedStation(String id) {
        if (listResponse == null || Math.abs(System.currentTimeMillis() - listFetchedAt) >= LOCATIONS_TTL) return null;
        JSONArray results = listResponse.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null && id.equals(Integer.toString(item.optInt("id", -1)))) return new JSONArray().put(item);
        }
        return null;
    }

    static AirQualityReading fetch(
            double latitude,
            double longitude,
            String locationName,
            String apiKey
    ) throws Exception {
        return fetchAvailable(latitude,longitude,locationName,apiKey,"",System::currentTimeMillis,LIVE);
    }

    static AirQualityReading fetchAvailable(double latitude, double longitude, String locationName,
            String apiKey, String excluded, long now, HistoryRecoveryRepository.JsonFetcher fetcher) throws Exception {
        return fetchAvailable(latitude,longitude,locationName,apiKey,excluded,()->now,fetcher);
    }
    static AirQualityReading fetchAvailable(double latitude, double longitude, String locationName,
            String apiKey, String excluded, java.util.function.LongSupplier clock, HistoryRecoveryRepository.JsonFetcher fetcher) throws Exception {
        Map<String, String> headers = Map.of("X-API-Key", apiKey);
        String coordinates = String.format(Locale.US, "%.4f,%.4f", latitude, longitude);
        String locationsUrl = BASE + "/locations?coordinates=" + coordinates
                + "&radius=25000&limit=1000&mobile=false&monitor=true";
        long deadline=System.nanoTime()+20_000_000_000L;
        JSONArray locations=new JSONArray();
        for(int page=1; ; page++) {
            if(System.nanoTime()>deadline) throw new AirQualityRepository.NoStationException("Backup search timed out");
            JSONArray batch=fetcher.get(locationsUrl+"&page="+page,headers).getJSONArray("results");
            for(int i=0;i<batch.length();i++) locations.put(batch.get(i));
            if(batch.length()<1000) break;
        }
        for(Station station : candidates(locations,latitude,longitude,clock.getAsLong())) {
            if(("openaq:"+station.id).equals(excluded)) continue;
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Refresh cancelled");
            if(System.nanoTime()>deadline) break;
            try {
                var reading=BackupPolicy.freshValues(readStation(station,locationName,fetcher,headers,clock),clock.getAsLong());
                if(reading.availablePollutants()>0) return reading;
            } catch(AirQualityRepository.ApiKeyRequiredException | java.util.concurrent.CancellationException error) { throw error; }
            catch(Exception unavailable) { /* Continue to the next nearest monitor. */ }
        }
        throw new AirQualityRepository.NoStationException("No fresh government monitor within 25 km");
    }

    static AirQualityReading fetchKnown(AirQualityReading target, double latitude, double longitude,
                                       String locationName, String key) throws Exception {
        String id=target.stationId.substring("openaq:".length());
        Map<String,String> headers=Map.of("X-API-Key",key);
        JSONArray metadata=rememberedStation(id);
        if(metadata==null) metadata=Network.getJsonFast(BASE+"/locations/"+id,headers).getJSONArray("results");
        var candidates=candidates(metadata,latitude,longitude,System.currentTimeMillis());
        if(candidates.isEmpty() || candidates.get(0).id!=Integer.parseInt(id))
            throw new AirQualityRepository.NoStationException("Official monitor unavailable");
        return readStation(candidates.get(0),locationName,Network::getJsonFast,headers,System::currentTimeMillis);
    }

    static AirQualityReading nearestTarget(double latitude,double longitude,String locationName,String key) throws Exception {
        String coordinates=String.format(Locale.US,"%.4f,%.4f",latitude,longitude);
        JSONArray locations=new JSONArray();
        long deadline=System.nanoTime()+20_000_000_000L;
        for(int page=1; ; page++) {
            if(System.nanoTime()>deadline) throw new AirQualityRepository.NoStationException("Station search timed out");
            JSONArray batch=LIVE.get(BASE+"/locations?coordinates="+coordinates
                    +"&radius=25000&limit=1000&mobile=false&monitor=true&page="+page,Map.of("X-API-Key",key)).getJSONArray("results");
            for(int i=0;i<batch.length();i++) locations.put(batch.get(i));
            if(batch.length()<1000) break;
        }
        var choices=candidates(locations,latitude,longitude,System.currentTimeMillis());
        if(choices.isEmpty()) throw new AirQualityRepository.NoStationException("No government monitor within 25 km");
        Station s=choices.get(0); AirQualityReading r=new AirQualityReading();
        r.stationId="openaq:"+s.id; r.stationName=s.name; r.sourceName=s.source;
        r.countryCode=s.countryCode; r.countryName=s.country; r.latitude=s.latitude; r.longitude=s.longitude;
        r.distanceKm=s.distanceKm; r.locationName=locationName;
        return r;
    }

    private static AirQualityReading readStation(Station station, String locationName,
            HistoryRecoveryRepository.JsonFetcher fetcher, Map<String,String> headers, java.util.function.LongSupplier clock) throws Exception {
        JSONObject latestRoot = fetcher.get(
                BASE + "/locations/" + station.id + "/latest?limit=100", headers);
        JSONArray latest = latestRoot.getJSONArray("results");
        long now=clock.getAsLong();
        Map<String, Measurement> measurements = parseLatest(latest, station.sensors,now);
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
        result.fetchedAtMillis = now;
        result.calculateIndices();
        return result;
    }

    private static List<Station> candidates(JSONArray locations, double latitude, double longitude, long now) throws Exception {
        List<Station> candidates = new ArrayList<>();
        long freshAfter = now - 48L * 60L * 60L * 1000L;
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
            if(candidate.distanceKm<=25) candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble(item -> item.distanceKm));
        return candidates;
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

    private static Map<String, Measurement> parseLatest(JSONArray latest, Map<Integer, Parameter> sensors, long now) {
        Map<String, Measurement> result = new HashMap<>();
        for (int i = 0; i < latest.length(); i++) {
            JSONObject item = latest.optJSONObject(i);
            if (item == null) continue;
            int sensorId = item.optInt("sensorsId", -1);
            Parameter parameter = sensors.get(sensorId);
            if (parameter == null) continue;
            long timestamp = parseTime(item.optJSONObject("datetime"));
            if(timestamp<=0 || timestamp>now) continue;
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





