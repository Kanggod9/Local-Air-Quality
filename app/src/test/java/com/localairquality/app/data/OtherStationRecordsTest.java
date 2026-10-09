package com.localairquality.app.data;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OtherStationRecordsTest {
    private static final long NOW=100*PollutantHistory.WINDOW;
    private void sample(PollutantHistory h,String id,long time) {
        h.samples.add(new PollutantHistory.Sample(time,id,id,new double[]{20,30,40,50,1000,5}));
    }
    @Test public void countsDistinctReportsAndStationsNotPollutantFields() {
        var h=new PollutantHistory(); sample(h,"current",NOW);
        sample(h,"a",NOW); sample(h,"a",NOW); sample(h,"a",NOW-3600000); sample(h,"b",NOW);
        var result=h.otherStationRecords("current",NOW);
        assertEquals(3,result.records()); assertEquals(2,result.stations());
        assertEquals("There are 3 records from 2 other stations in the last 24 hours.",result.message());
        assertEquals(2,h.otherStationRecords("a",NOW).records());
    }
    @Test public void excludesExpiredFutureEmptyAndCalibrationOnlyData() {
        var h=new PollutantHistory();
        sample(h,"expired",NOW-PollutantHistory.WINDOW-1); sample(h,"future",NOW+3600000); sample(h,"",NOW);
        h.ozoneCalibration.add(new PollutantHistory.Ozone(NOW,"ozone-only",60));
        double[] missing=new double[6]; Arrays.fill(missing,Double.NaN);
        h.samples.add(new PollutantHistory.Sample(NOW,"missing","Missing",missing));
        assertEquals(0,h.otherStationRecords("current",NOW).records());
        assertEquals("",h.otherStationRecords("current",NOW).message());
        sample(h,"edge",NOW-PollutantHistory.WINDOW);
        assertEquals("There is 1 record from 1 other station in the last 24 hours.",h.otherStationRecords("current",NOW).message());
        assertEquals("",h.otherStationRecords("current",NOW+1).message());
    }
    @Test public void summaryDoesNotExposeHiddenStationRecordsInChartsOrModifyHistory() {
        var h=new PollutantHistory(); h.activeStationId="current";
        sample(h,"current",NOW); sample(h,"other",NOW);
        assertEquals(1,h.otherStationRecords("current",NOW).records());
        assertEquals(2,h.samples.size());
        for(var metric:HistorySeries.Metric.values())
            assertTrue(HistorySeries.points(h,metric).stream().allMatch(p->p.stationName().equals("current")));
    }
}
