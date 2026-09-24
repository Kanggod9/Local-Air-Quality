package com.localairquality.app.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Recovers provider reports for the known station; only ozone calibration can extend to 14 days. */
public final class HistoryRecoveryRepository {
    private HistoryRecoveryRepository() {}
    @FunctionalInterface interface JsonFetcher {
        JSONObject get(String url, Map<String, String> headers) throws Exception;
    }
    public static void recover(AirQualityReading reading, PollutantHistory.Recovery request,
                               String apiKey, Consumer<List<PollutantHistory.Sample>> publish) throws Exception {
        recover(reading, request, apiKey, publish, Network::getJson);
    }
    static void recover(AirQualityReading reading, PollutantHistory.Recovery request, String apiKey,
                        Consumer<List<PollutantHistory.Sample>> publish, JsonFetcher fetcher) throws Exception {
        Exception failure = null;
        if (request.stationId().startsWith("nea:")) {
            LocalDate first = Instant.ofEpochMilli(request.from()).atZone(ZoneId.of("Asia/Singapore")).toLocalDate();
            LocalDate last = Instant.ofEpochMilli(request.to()).atZone(ZoneId.of("Asia/Singapore")).toLocalDate();
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                for (String feed : List.of("pm25", "psi")) {
                    try {
                        String token = "";
                        for (int page = 0; page < 10; page++) {
                            String url = "https://api-open.data.gov.sg/v2/real-time/api/" + feed + "?date=" + date
                                    + (token.isEmpty() ? "" : "&paginationToken=" + encode(token));
                            JSONObject data = fetcher.get(url, null).getJSONObject("data");
                            publish.accept(parseNea(data.getJSONArray("items"), feed.equals("pm25"), reading));
                            token = data.optString("paginationToken", "");
                            if (token.isEmpty()) break;
                        }
                    } catch (Exception error) { failure = keepFailure(failure, error); }
                }
            }
        } else if (request.stationId().startsWith("openaq:")) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new AirQualityRepository.ApiKeyRequiredException("OpenAQ API key is required for history");
            }
            Map<String, String> headers = Map.of("X-API-Key", apiKey);
            int[] sensors = reading.pollutantSensorIds;
            if (Arrays.stream(sensors).noneMatch(id -> id > 0)) {
                // Older caches did not persist sensor IDs. Resolve the known station, without
                // requiring /latest to be online or selecting a different station/country.
                String id = request.stationId().substring("openaq:".length());
                JSONObject station = fetcher.get("https://api.openaq.org/v3/locations/" + id, headers)
                        .getJSONArray("results").getJSONObject(0);
                if (!id.equals(Integer.toString(station.getInt("id")))) {
                    throw new java.io.IOException("History station metadata did not match");
                }
                sensors = OpenAqRepository.recoverySensorIds(station.getJSONArray("sensors"));
                if (Arrays.stream(sensors).noneMatch(sensor -> sensor > 0)) {
                    throw new java.io.IOException("History sensors are not available yet");
                }
            }
            for (int p = 0; p < 6; p++) {
                int sensor = sensors[p];
                if (sensor <= 0) continue;
                try {
                    // A failed sensor must not prevent recovery of other pollutants.
                    for (int page = 1; page <= (p == 2 ? 4 : 3); page++) {
                        String url = "https://api.openaq.org/v3/sensors/" + sensor + "/hours?limit=100&page=" + page
                                + "&datetime_from=" + encode(Instant.ofEpochMilli(p == 2 ? request.ozoneFrom() : request.from()).toString())
                                + "&datetime_to=" + encode(Instant.ofEpochMilli(request.to()).toString());
                        JSONArray results = fetcher.get(url, headers).getJSONArray("results");
                        publish.accept(parseOpenAq(results, p, reading));
                        if (results.length() < 100) break;
                    }
                } catch (Exception error) { failure = keepFailure(failure, error); }
            }
        }
        if (failure != null) throw failure;
    }
    private static Exception keepFailure(Exception previous, Exception error) throws Exception {
        if (Thread.currentThread().isInterrupted() || error instanceof java.util.concurrent.CancellationException
                || error instanceof AirQualityRepository.ApiKeyRequiredException) throw error;
        if (previous == null) return error;
        previous.addSuppressed(error);
        return previous;
    }
    private static String encode(String value) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }    static List<PollutantHistory.Sample> parseNea(JSONArray items, boolean pm25, AirQualityReading station) throws Exception {
        List<PollutantHistory.Sample> result = new ArrayList<>();
        String region = station.stationId.substring("nea:".length());
        String[] fields = {"pm25_one_hourly", "pm10_twenty_four_hourly", "o3_eight_hour_max",
                "no2_one_hour_max", "co_eight_hour_max", "so2_twenty_four_hourly"};
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            long time = OffsetDateTime.parse(item.getString("timestamp")).toInstant().toEpochMilli();
            JSONObject readings = item.getJSONObject("readings");
            double[] values = new double[6]; Arrays.fill(values, Double.NaN);
            for (int p = pm25 ? 0 : 1; p < (pm25 ? 1 : 6); p++) {
                JSONObject regions = readings.optJSONObject(fields[p]);
                if (regions != null) values[p] = regions.optDouble(region, Double.NaN);
                if (p == 4 && AirQualityReading.isPresent(values[p])) values[p] *= 1000;
            }
            result.add(new PollutantHistory.Sample(time, station.stationId, station.stationName, values));
        }
        return result;
    }
    static List<PollutantHistory.Sample> parseOpenAq(JSONArray items, int pollutant, AirQualityReading station) {
        List<PollutantHistory.Sample> result = new ArrayList<>();
        String[] names = {"pm25", "pm10", "o3", "no2", "co", "so2"};
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject period = item.optJSONObject("period"), parameter = item.optJSONObject("parameter");
            if (period == null || parameter == null) continue;
            long time = OpenAqRepository.parseTime(period.optJSONObject("datetimeTo"));
            if (time <= 0) continue;
            if (!names[pollutant].equals(parameter.optString("name", "").toLowerCase(Locale.ROOT).replace(".", ""))) continue;
            double[] values = new double[6]; Arrays.fill(values, Double.NaN);
            values[pollutant] = OpenAqRepository.toMicrograms(item.optDouble("value", Double.NaN),
                    parameter.optString("units", "µg/m³"), names[pollutant]);
            if (AirQualityReading.isPresent(values[pollutant])) {
                result.add(new PollutantHistory.Sample(time, station.stationId, station.stationName, values));
            }
        }
        return result;
    }
}
