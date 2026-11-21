package com.localairquality.app.data;

import org.junit.Test;
import org.json.*;
import java.io.IOException;
import static org.junit.Assert.*;

public class NeaBackupTest {
    private static final long NOW=2000*NowCast.HOUR;
    private static AirQualityReading reading(long time) {
        var r=new AirQualityReading(); r.stationId="nea:west"; r.measuredAtMillis=time; r.pm25=12; return r;
    }
    @Test public void healthyButStaleV1StillChecksNewerOfficialV2() throws Exception {
        var first=reading(NOW-4*NowCast.HOUR); var alternate=reading(NOW);
        assertSame(alternate,NeaRepository.latestOfficial(()->first,()->alternate,NOW));
    }
    @Test public void freshPrimaryAvoidsUnneededAlternateRequest() throws Exception {
        var first=reading(NOW);
        assertSame(first,NeaRepository.latestOfficial(()->first,()->{fail();return null;},NOW));
    }
    @Test public void staleOfficialReportRetainedIfAlternateFails() throws Exception {
        var first=reading(NOW-4*NowCast.HOUR);
        assertSame(first,NeaRepository.latestOfficial(()->first,()->{throw new IOException();},NOW));
    }
    @Test public void alternateReportUsedOnPrimaryFailure() throws Exception {
        var alternate=reading(NOW);
        assertSame(alternate,NeaRepository.latestOfficial(()->{throw new IOException();},()->alternate,NOW));
    }
    @Test public void unorderedResultsAlwaysSelectNewestReport() throws Exception {
        var older=new JSONObject().put("timestamp","2026-10-13T22:00:00+08:00");
        var newest=new JSONObject().put("timestamp","2026-10-14T01:00:00+08:00");
        assertSame(newest,NeaRepository.latestItem(new JSONArray().put(older).put(newest).put(older)));
    }
}
