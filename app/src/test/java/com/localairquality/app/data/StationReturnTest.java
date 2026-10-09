package com.localairquality.app.data;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class StationReturnTest {
    private static final long H=NowCast.HOUR, START=100*PollutantHistory.WINDOW;
    private static final double[] VALUES={36,45,40,20,1000,5};
    private void visit(PollutantHistory history,String station,long time) {
        long[] times=new long[6]; Arrays.fill(times,time);
        history.add("SG","Singapore",station,station,"Here",times,VALUES,time);
        history.updateNowcasts(time);
    }
    private PollutantHistory restart(PollutantHistory history) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); history.write(bytes);
        return PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
    }
    @Test public void newStationChartsExcludePreviousStationForEveryMetric() {
        PollutantHistory history=new PollutantHistory();
        visit(history,"a",START); visit(history,"a",START+H); visit(history,"b",START+2*H);
        assertEquals(START+2*H,history.beginRecovery(START+2*H).from());
        for (var metric:HistorySeries.Metric.values()) {
            var points=HistorySeries.points(history,metric);
            assertEquals(metric.label,1,points.size());
            assertEquals("b",points.get(0).stationName());
            assertEquals(START+2*H,points.get(0).measuredAt());
        }
    }
    @Test public void returningWithinDayRecoversGapAfterRestartAndRejectsPreviousVisitRequest() throws Exception {
        PollutantHistory history=new PollutantHistory(); visit(history,"nea:west",START);
        var old=history.beginRecovery(START);
        visit(history,"nea:south",START+H);
        history=restart(history);
        visit(history,"nea:west",START+5*H);
        var recovery=history.beginRecovery(START+5*H);
        assertEquals(START,recovery.from());
        assertFalse(history.acceptRecovery(old,List.of(),START+5*H));
        assertFalse(history.completeRecovery(old));
        List<PollutantHistory.Sample> reports=new ArrayList<>();
        for (int i=0;i<=5;i++) reports.add(new PollutantHistory.Sample(START+i*H,"nea:west","nea:west",VALUES));
        history.acceptRecovery(recovery,reports,START+5*H); history.completeRecovery(recovery);
        history.updateNowcasts(START+5*H);
        assertEquals(6,HistorySeries.points(history,HistorySeries.Metric.US_AQI).size());
        assertEquals(6,HistorySeries.points(history,HistorySeries.Metric.EUROPEAN_AQI).size());
        assertEquals(5,HistorySeries.points(history,HistorySeries.Metric.US_NOWCAST).size());
        assertTrue(history.latestNowcast("nea:west").aqi()>=0);
        assertEquals(START,restart(history).activeHistorySince);
    }
    @Test public void returnAfterLastRecordExpiresStartsFreshAndCannotImportOlderHours() {
        PollutantHistory history=new PollutantHistory(); visit(history,"a",START); visit(history,"b",START+H);
        long returned=START+24*H+1; visit(history,"a",returned);
        var request=history.beginRecovery(returned);
        assertEquals(START+24*H,request.from());
        history.acceptRecovery(request,List.of(new PollutantHistory.Sample(START+23*H,"a","a",VALUES)),returned);
        assertEquals(1,HistorySeries.points(history,HistorySeries.Metric.US_AQI).size());
        assertEquals(returned,history.activeHistorySince);
    }
    @Test public void exactlyTwentyFourHoursStillResumesButNoExpiredHoursReturn() {
        PollutantHistory history=new PollutantHistory(); visit(history,"a",START); visit(history,"b",START+H);
        visit(history,"a",START+24*H);
        assertEquals(START,history.activeHistorySince);
        assertEquals(START,history.beginRecovery(START+24*H).from());
    }
    @Test public void lastRecordNotFirstRecordDeterminesWhetherStationResumes() {
        PollutantHistory history=new PollutantHistory(); visit(history,"a",START); visit(history,"a",START+10*H);
        visit(history,"b",START+11*H); visit(history,"a",START+30*H);
        assertEquals(START+10*H,history.activeHistorySince);
    }
    @Test public void v7UpgradeRepairsAlreadyReturnedStationWithoutLosingItsRecords() throws Exception {
        PollutantHistory history=new PollutantHistory(); visit(history,"a",START); visit(history,"b",START+H);
        visit(history,"a",START+5*H); history.completeRecovery(history.beginRecovery(START+5*H));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); history.write(bytes);
        byte[] v7=Arrays.copyOf(bytes.toByteArray(),bytes.size()-8); v7[3]=7;
        var restored=PollutantHistory.read(new ByteArrayInputStream(v7));
        assertEquals(3,restored.samples.size());
        assertEquals(START,restored.beginRecovery(START+5*H+1).from());
        assertEquals(START+5*H,restored.activeStationSince);
    }
}
