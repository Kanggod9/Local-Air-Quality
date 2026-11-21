package com.localairquality.app.data;

import org.json.*;
import org.junit.Test;
import java.time.Instant;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class OpenAqBackupTest {
    private static final long NOW=2000*NowCast.HOUR;
    private static JSONObject station(int id,double longitude) throws Exception {
        return new JSONObject().put("id",id).put("name","Monitor "+id).put("isMobile",false).put("isMonitor",true)
                .put("coordinates",new JSONObject().put("latitude",1.3).put("longitude",longitude))
                .put("datetimeLast",new JSONObject().put("utc",Instant.ofEpochMilli(NOW).toString()))
                .put("country",new JSONObject().put("code","SG").put("name","Singapore"))
                .put("owner",new JSONObject().put("name","Government test monitor"))
                .put("instruments",new JSONArray().put(new JSONObject().put("name","Government Monitor")))
                .put("sensors",new JSONArray().put(new JSONObject().put("id",id*10)
                        .put("parameter",new JSONObject().put("name","pm25").put("units","µg/m³"))));
    }
    private static JSONObject latest(int station,long time,double value) throws Exception {
        return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("sensorsId",station*10)
                .put("value",value).put("datetime",new JSONObject().put("utc",Instant.ofEpochMilli(time).toString()))));
    }
    private static JSONObject locations(JSONObject... locations) throws Exception { return new JSONObject().put("results",new JSONArray(Arrays.asList(locations))); }
    @Test public void skipsNearestStaleReadingEvenWhenMetadataClaimsCurrent() throws Exception {
        List<String> requests=new ArrayList<>();
        var result=OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","",NOW,(url,headers)->{
            requests.add(url);
            if(url.contains("coordinates=")) return locations(station(2,103.82),station(1,103.801));
            return url.contains("/1/latest")?latest(1,NOW-4*NowCast.HOUR,300):latest(2,NOW,12);
        });
        assertEquals("openaq:2",result.stationId); assertEquals(12,result.pm25,0);
        assertTrue(requests.get(1).contains("/1/latest")); assertTrue(requests.get(2).contains("/2/latest"));
    }
    @Test public void nearestFailedRequestDoesNotBlockNextAvailableMonitor() throws Exception {
        var result=OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","",NOW,(url,headers)->{
            if(url.contains("coordinates=")) return locations(station(1,103.801),station(2,103.82));
            if(url.contains("/1/latest")) throw new IOException("offline");
            return latest(2,NOW,12);
        });
        assertEquals("openaq:2",result.stationId);
    }
    @Test public void excludesPrimarySourceAndRechecksActualLatestOnEachRefresh() throws Exception {
        List<String> requests=new ArrayList<>();
        HistoryRecoveryRepository.JsonFetcher fetcher=(url,headers)->{
            requests.add(url);
            if(url.contains("coordinates=")) return locations(station(1,103.801),station(2,103.82));
            return latest(2,NOW,12);
        };
        for(int i=0;i<2;i++) assertEquals("openaq:2",OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","openaq:1",NOW,fetcher).stationId);
        assertEquals(4,requests.size()); assertTrue(requests.stream().noneMatch(u->u.contains("/1/latest")));
    }
    @Test public void futureInvalidAndOutsideRadiusReadingsAreRejected() throws Exception {
        var outside=station(2,104.8); var mobile=station(3,103.801).put("isMobile",true);
        List<String> requests=new ArrayList<>();
        try {
            OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","",NOW,(url,headers)->{
                requests.add(url);
                return url.contains("coordinates=")?locations(station(1,103.801),outside,mobile):latest(1,NOW+1,300);
            }); fail();
        } catch(AirQualityRepository.NoStationException expected) { assertEquals(2,requests.size()); }
    }
    @Test public void invalidKeyStopsSearchRatherThanTryingEveryStation() throws Exception {
        List<String> requests=new ArrayList<>();
        try {
            OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","",NOW,(url,headers)->{
                requests.add(url); if(url.contains("coordinates=")) return locations(station(1,103.801),station(2,103.82));
                throw new AirQualityRepository.ApiKeyRequiredException("Invalid key");
            }); fail();
        } catch(AirQualityRepository.ApiKeyRequiredException expected) { assertEquals(2,requests.size()); }
    }
    @Test public void governmentMonitorRestrictionIsRetained() throws Exception {
        var sensor=station(1,103.801).put("instruments",new JSONArray().put(new JSONObject().put("name","Low cost sensor")));
        try {
            OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","",NOW,(url,headers)->locations(sensor)); fail();
        } catch(AirQualityRepository.NoStationException expected) {}
    }
    @Test public void nearestFreshMonitorCanAppearOnLaterLocationPage() throws Exception {
        JSONArray firstPage=new JSONArray();
        for(int i=0;i<1000;i++) firstPage.put(station(i+100,103.9).put("datetimeLast",
                new JSONObject().put("utc",Instant.ofEpochMilli(NOW-49*NowCast.HOUR).toString())));
        List<String> requests=new ArrayList<>();
        var result=OpenAqRepository.fetchAvailable(1.3,103.8,"Here","test","",NOW,(url,headers)->{
            requests.add(url);
            if(url.contains("coordinates=")) return url.contains("&page=1")?new JSONObject().put("results",firstPage):locations(station(1,103.801));
            return latest(1,NOW,12);
        });
        assertEquals("openaq:1",result.stationId); assertTrue(requests.get(1).contains("&page=2"));
    }
}
