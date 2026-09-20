package com.localairquality.app.data;
import org.junit.Test;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.Assert.*;

public class HistoryRecoveryRepositoryTest {
    private JSONArray fixture(String name) throws Exception {
        try (var in=getClass().getResourceAsStream("/history/"+name+"-day.json")) {
            return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONObject("data").getJSONArray("items");
        }
    }
    @Test public void actualNeaDailyResponsesRecover24ReportsAndCombineAtReportedTimes() throws Exception {
        AirQualityReading station = new AirQualityReading(); station.stationId="nea:west"; station.stationName="West";
        var pm=HistoryRecoveryRepository.parseNea(fixture("pm25"),true,station);
        var psi=HistoryRecoveryRepository.parseNea(fixture("psi"),false,station);
        assertEquals(24,pm.size()); assertEquals(24,psi.size());
        assertEquals(2,pm.get(0).values()[0],0);
        assertTrue(Double.isNaN(psi.get(0).values()[0]));
        assertEquals(1000,psi.get(0).values()[4],0);
        assertEquals(pm.get(0).measuredAt(),psi.get(0).measuredAt());
        long start=OffsetDateTime.parse("2026-09-19T00:00:00+08:00").toInstant().toEpochMilli();
        PollutantHistory h=new PollutantHistory();
        long[] times=new long[6]; Arrays.fill(times,start);
        h.add("SG","Singapore",station.stationId,"West","Home",times,pm.get(23).values(),start);
        var ticket=h.beginRecovery(start+23*3600000L);
        h.acceptRecovery(ticket,pm,ticket.to()); h.acceptRecovery(ticket,psi,ticket.to());
        assertEquals(24,h.samples.size());
        assertEquals(24,HistorySeries.points(h,HistorySeries.Metric.US_AQI).size());
        assertEquals(24,HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).size());
    }
    @Test public void openAqHourlyUsesPeriodEndAndConvertsCoUnits() throws Exception {
        AirQualityReading station=new AirQualityReading(); station.stationId="openaq:1"; station.stationName="A";
        JSONArray data=new JSONArray("[{\"value\":1.2,\"parameter\":{\"name\":\"co\",\"units\":\"mg/m³\"},\"period\":{\"datetimeTo\":{\"utc\":\"2026-09-19T09:00:00Z\"}}}]");
        var reports=HistoryRecoveryRepository.parseOpenAq(data,4,station);
        assertEquals(1,reports.size()); assertEquals(1200,reports.get(0).values()[4],0.001);
        assertEquals(java.time.Instant.parse("2026-09-19T09:00:00Z").toEpochMilli(),reports.get(0).measuredAt());
        assertTrue(HistoryRecoveryRepository.parseOpenAq(data,0,station).isEmpty());
    }
    @Test public void openAqMissingTimestampIsNotInvented() throws Exception {
        AirQualityReading station=new AirQualityReading();
        assertTrue(HistoryRecoveryRepository.parseOpenAq(new JSONArray("[{\"value\":10,\"parameter\":{\"name\":\"pm25\"},\"period\":{}}]"),0,station).isEmpty());
    }
    @Test public void nonSingaporeStationRecoversOtherSensorsWhenOneFails() throws Exception {
        AirQualityReading station=new AirQualityReading(); station.stationId="openaq:42"; station.stationName="London";
        station.pollutantSensorIds[0]=100; station.pollutantSensorIds[4]=200;
        var ticket=new PollutantHistory.Recovery("GB",1,station.stationId,1,1,2000000000000L);
        List<PollutantHistory.Sample> saved=new ArrayList<>();
        List<String> urls=new ArrayList<>();
        try {
            HistoryRecoveryRepository.recover(station,ticket,"test-key",saved::addAll,(url,headers)->{
                urls.add(url); assertEquals("test-key",headers.get("X-API-Key"));
                if(url.contains("/100/")) throw new java.io.IOException("Sensor temporarily unavailable");
                return new JSONObject("{\"results\":[{\"value\":0.8,\"parameter\":{\"name\":\"co\",\"units\":\"ppm\"},\"period\":{\"datetimeTo\":{\"local\":\"2026-09-19T10:00:00+01:00\"}}}]}");
            });
            fail("Failure should remain visible to the coordinator");
        } catch(java.io.IOException expected) {}
        assertEquals(2,urls.size()); assertEquals(1,saved.size());
        assertTrue(urls.get(1).contains("datetime_from="));
        assertEquals(java.time.Instant.parse("2026-09-19T09:00:00Z").toEpochMilli(),saved.get(0).measuredAt());
        assertEquals(OpenAqRepository.toMicrograms(0.8,"ppm","co"),saved.get(0).values()[4],0.001);
    }
    @Test public void timezoneOffsetDoesNotChangeExpiryBasis() throws Exception {
        AirQualityReading station=new AirQualityReading(); station.stationId="openaq:2";
        JSONArray data=new JSONArray("[{\"value\":30,\"parameter\":{\"name\":\"pm25\",\"units\":\"µg/m³\"},\"period\":{\"datetimeTo\":{\"local\":\"2026-09-19T14:30:00+05:30\"}}}]");
        assertEquals(java.time.Instant.parse("2026-09-19T09:00:00Z").toEpochMilli(),HistoryRecoveryRepository.parseOpenAq(data,0,station).get(0).measuredAt());
    }

    @Test public void sixHourOutageAndPartialDownloadRecoverAfterRestartWithoutLiveFetch() throws Exception {
        long hour = 3600000L;
        long start = OffsetDateTime.parse("2026-09-19T01:00:00+08:00").toInstant().toEpochMilli();
        AirQualityReading station = new AirQualityReading();
        station.stationId = "nea:west"; station.stationName = "West"; station.countryCode = "SG";
        station.pm25 = 20; station.pollutantMeasuredAtMillis[0] = start;
        PollutantHistory h = new PollutantHistory();
        h.add("SG", "Singapore", station.stationId, "West", "Home", station.pollutantMeasuredAtMillis,
                new double[]{20,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN}, start);
        var failed = h.beginRecovery(start + 6*hour);
        try {
            HistoryRecoveryRepository.recover(station, failed, "", reports -> h.acceptRecovery(failed,reports,failed.to()),
                    (url,headers) -> { throw new java.io.IOException("Offline"); });
            fail("Offline recovery must remain pending");
        } catch (java.io.IOException expected) {}
        assertTrue(h.needsRecovery(start + 6*hour));
        try {
            HistoryRecoveryRepository.recover(station, failed, "", reports -> h.acceptRecovery(failed,reports,failed.to()),
                    (url,headers) -> {
                        if (url.contains("/psi?")) throw new java.io.IOException("Connection interrupted");
                        return new JSONObject().put("data",new JSONObject().put("items",fixture("pm25")));
                    });
            fail("Partial download must remain pending");
        } catch (java.io.IOException expected) {}
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); h.write(bytes);
        PollutantHistory restored = PollutantHistory.read(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        AirQualityReading cached = AirQualityReading.fromJson(station.toJson().toString());
        var retry = restored.beginRecovery(start + 6*hour + 1000);
        assertNotNull(retry);
        HistoryRecoveryRepository.recover(cached,retry,"",reports -> restored.acceptRecovery(retry,reports,retry.to()),
                (url,headers) -> new JSONObject().put("data",new JSONObject().put("items",
                        fixture(url.contains("/pm25?") ? "pm25" : "psi"))));
        assertTrue(restored.completeRecovery(retry));
        assertFalse(restored.needsRecovery(retry.to()+1));
        assertEquals(7,restored.samples.size());
        for (int i=0;i<7;i++) {
            var sample=restored.samples.get(i);
            assertEquals(start+i*hour,sample.measuredAt());
            for(double value:sample.values()) assertTrue(AirQualityReading.isPresent(value));
        }
        assertEquals(7,HistorySeries.points(restored,HistorySeries.Metric.US_AQI).size());
        assertEquals(7,HistorySeries.points(restored,HistorySeries.Metric.EUROPEAN_AQI).size());
        restored.prune(start+30*hour);
        assertEquals(1,restored.samples.size());
        assertEquals(start+6*hour,restored.samples.get(0).measuredAt());
    }

    @Test public void openAqCachedSensorIdsSurviveRestart() throws Exception {
        AirQualityReading reading = new AirQualityReading();
        reading.stationId="openaq:42"; reading.countryCode="GB";
        reading.pollutantSensorIds[0]=100; reading.pollutantSensorIds[4]=200;
        AirQualityReading restored=AirQualityReading.fromJson(reading.toJson().toString());
        assertArrayEquals(reading.pollutantSensorIds,restored.pollutantSensorIds);
        List<String> urls = new ArrayList<>();
        HistoryRecoveryRepository.recover(restored,new PollutantHistory.Recovery("GB",1,"openaq:42",1,1,2),
                "key", reports -> {}, (url,headers) -> {
                    urls.add(url); return new JSONObject().put("results",new JSONArray());
                });
        assertEquals(2,urls.size());
        assertTrue(urls.get(0).contains("/sensors/100/hours"));
        assertTrue(urls.get(1).contains("/sensors/200/hours"));
    }

    @Test public void overnightRecoveryRequestsBothSingaporeCalendarDays() throws Exception {
        long start=OffsetDateTime.parse("2026-09-19T22:00:00+08:00").toInstant().toEpochMilli();
        long wake=start+6*3600000L;
        AirQualityReading station=new AirQualityReading(); station.stationId="nea:west";
        PollutantHistory h=new PollutantHistory();
        h.add("SG","Singapore",station.stationId,"West","Home",new long[6],new double[6],start);
        var ticket=h.beginRecovery(wake);
        List<String> urls=new ArrayList<>();
        HistoryRecoveryRepository.recover(station,ticket,"",reports->h.acceptRecovery(ticket,reports,wake),
                (url,headers)->{
                    urls.add(url);
                    JSONArray day=fixture(url.contains("/pm25?") ? "pm25" : "psi");
                    if(url.contains("date=2026-09-20")) {
                        for(int i=0;i<day.length();i++) {
                            JSONObject item=day.getJSONObject(i);
                            item.put("timestamp",item.getString("timestamp").replace("2026-09-19","2026-09-20"));
                        }
                    }
                    return new JSONObject().put("data",new JSONObject().put("items",day));
                });
        assertEquals(4,urls.size());
        assertEquals(7,h.samples.size());
        assertEquals(start,h.samples.get(0).measuredAt());
        assertEquals(wake,h.samples.get(6).measuredAt());
        for(var sample:h.samples) for(double value:sample.values()) assertTrue(AirQualityReading.isPresent(value));
    }

    @Test public void olderOpenAqCacheResolvesKnownStationWithoutLatestEndpoint() throws Exception {
        AirQualityReading cached=AirQualityReading.fromJson("{\"stationId\":\"openaq:42\"}");
        List<String> urls=new ArrayList<>();
        List<PollutantHistory.Sample> saved=new ArrayList<>();
        HistoryRecoveryRepository.recover(cached,new PollutantHistory.Recovery("GB",1,"openaq:42",1,1,2000000000000L),
                "key",saved::addAll,(url,headers)->{
                    urls.add(url);
                    if(url.endsWith("/locations/42")) return new JSONObject("""
                        {"results":[{"id":42,"sensors":[{"id":100,"parameter":{"name":"pm25"}}]}]}
                        """);
                    assertTrue(url.contains("/sensors/100/hours"));
                    return new JSONObject("""
                        {"results":[{"value":20,"parameter":{"name":"pm25","units":"µg/m³"},
                        "period":{"datetimeTo":{"utc":"2026-09-19T09:00:00Z"}}}]}
                        """);
                });
        assertEquals(2,urls.size()); assertEquals(1,saved.size());
        assertEquals("openaq:42",saved.get(0).stationId());
    }
}
