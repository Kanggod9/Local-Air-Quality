package com.localairquality.app.data;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class AirQualityAlertTest {
    private static final long NOW = 2_000 * NowCast.HOUR;
    private static HistorySeries.Point point(long time, double value) {
        return new HistorySeries.Point(time,"A",value,1,"PM2.5",70);
    }
    private static List<HistorySeries.Point> hours(int count, double value) {
        List<HistorySeries.Point> points = new ArrayList<>();
        for (int i = 0; i < count; i++) points.add(point(NOW-i*NowCast.HOUR, value));
        return points;
    }
    private static AirQualityAlert.Result alert(int usCount, double us, int euCount, double eu) {
        return AirQualityAlert.fromPoints(hours(usCount,us), hours(euCount,eu), NOW);
    }

    @Test public void sevenHoursNeverQualifyAndEightDo() {
        assertFalse(alert(7,500,7,6).active());
        assertEquals(AirQualityAlert.Level.RED,alert(0,0,8,4).level());
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert(8,151,0,0).level());
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert(0,0,8,5).level());
        assertEquals(AirQualityAlert.Level.PURPLE,alert(8,201,0,0).level());
        assertEquals(AirQualityAlert.Level.PURPLE,alert(0,0,8,6).level());
        assertEquals(AirQualityAlert.Level.BLACK,alert(8,301,0,0).level());
    }
    @Test public void exactBoundariesAndWorseLevelsCount() {
        assertFalse(alert(8,150,8,3).active());
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert(8,200,0,0).level());
        assertEquals(AirQualityAlert.Level.PURPLE,alert(8,300,0,0).level());
        List<HistorySeries.Point> us = hours(7,151);
        us.add(point(NOW-7*NowCast.HOUR,500));
        assertEquals(AirQualityAlert.Level.DEEP_RED,AirQualityAlert.fromPoints(us,List.of(),NOW).level());
    }
    @Test public void highestWinsAndSourcesMatchWinningLevelOnly() {
        var black = alert(8,301,24,6);
        assertEquals(AirQualityAlert.Level.BLACK,black.level());
        assertEquals("US AQI",black.source());
        var both = alert(8,201,8,6);
        assertEquals("US AQI + European AQI",both.source());
        assertEquals("European AQI",alert(8,151,8,6).source());
        assertEquals("US AQI",alert(8,201,8,5).source());
    }
    @Test public void mixedHigherLevelsAccumulateTowardEachThreshold() {
        var us = hours(4,151);
        for (int i=4;i<6;i++) us.add(point(NOW-i*NowCast.HOUR,201));
        for (int i=6;i<8;i++) us.add(point(NOW-i*NowCast.HOUR,301));
        var result = AirQualityAlert.fromPoints(us,List.of(),NOW);
        assertEquals(AirQualityAlert.Level.DEEP_RED,result.level());
        assertEquals(8,result.usHours());

        us = hours(4,201);
        for (int i=4;i<8;i++) us.add(point(NOW-i*NowCast.HOUR,301));
        assertEquals(AirQualityAlert.Level.PURPLE,AirQualityAlert.fromPoints(us,List.of(),NOW).level());

        var eu = hours(4,4);
        for (int i=4;i<6;i++) eu.add(point(NOW-i*NowCast.HOUR,5));
        for (int i=6;i<8;i++) eu.add(point(NOW-i*NowCast.HOUR,6));
        result = AirQualityAlert.fromPoints(List.of(),eu,NOW);
        assertEquals(AirQualityAlert.Level.RED,result.level());
        assertEquals(8,result.europeanHours());

        eu = hours(4,5);
        for (int i=4;i<8;i++) eu.add(point(NOW-i*NowCast.HOUR,6));
        assertEquals(AirQualityAlert.Level.DEEP_RED,AirQualityAlert.fromPoints(List.of(),eu,NOW).level());
    }
    @Test public void differentIndicesDoNotPoolInsufficientHours() {
        var eu = new ArrayList<HistorySeries.Point>();
        for (int i=4;i<8;i++) eu.add(point(NOW-i*NowCast.HOUR,5));
        assertFalse(AirQualityAlert.fromPoints(hours(4,151),eu,NOW).active());
    }
    @Test public void userExamplesSelectDeepRedThenPurple() {
        var us = hours(5,151);
        for (int i=5;i<8;i++) us.add(point(NOW-i*NowCast.HOUR,201));
        var result = AirQualityAlert.fromPoints(us,List.of(),NOW);
        assertEquals(AirQualityAlert.Level.DEEP_RED,result.level());
        assertEquals(8,result.usHours());

        us = hours(3,151);
        for (int i=3;i<7;i++) us.add(point(NOW-i*NowCast.HOUR,201));
        for (int i=7;i<12;i++) us.add(point(NOW-i*NowCast.HOUR,301));
        result = AirQualityAlert.fromPoints(us,List.of(),NOW);
        assertEquals(AirQualityAlert.Level.PURPLE,result.level());
        assertEquals(9,result.usHours());
        assertEquals("US AQI",result.source());
    }
    @Test public void repeatedRefreshesAndSubhourReportsCountOnlyOnce() {
        var points = hours(7,500);
        for (int i=0;i<50;i++) points.add(point(NOW-NowCast.HOUR+i*1_000,500));
        assertFalse(AirQualityAlert.fromPoints(points,List.of(),NOW).active());
        points.add(point(NOW-7*NowCast.HOUR,301));
        assertEquals(8,AirQualityAlert.fromPoints(points,List.of(),NOW).usHours());
    }
    @Test public void expiredFutureAndMissingRecordsDoNotQualify() {
        var points = hours(7,500);
        points.add(point(NOW-PollutantHistory.WINDOW,500));
        points.add(point(NOW+NowCast.HOUR,500));
        points.add(point(NOW-8*NowCast.HOUR,Double.NaN));
        points.add(point(0,500));
        assertFalse(AirQualityAlert.fromPoints(points,List.of(),NOW).active());
        var qualifying = hours(8,301);
        qualifying.set(7,point(NOW-PollutantHistory.WINDOW+1,301));
        assertTrue(AirQualityAlert.fromPoints(qualifying,List.of(),NOW).active());
        assertFalse(AirQualityAlert.fromPoints(qualifying,List.of(),NOW+1).active());
    }
    @Test public void nonconsecutiveHoursWorkWithoutInventingGaps() {
        List<HistorySeries.Point> points = new ArrayList<>();
        for(int i=0;i<8;i++) points.add(point(NOW-i*3*NowCast.HOUR,301));
        assertEquals(AirQualityAlert.Level.BLACK,AirQualityAlert.fromPoints(points,List.of(),NOW).level());
    }
    @Test public void currentStationOnlyAndNotNowcastOrOzoneCalibration() {
        PollutantHistory history = new PollutantHistory();
        history.activeStationId = "a";
        for(int i=0;i<8;i++) history.samples.add(new PollutantHistory.Sample(NOW-i*NowCast.HOUR,"b","B",
                new double[]{500,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN}));
        history.ozoneCalibration.add(new PollutantHistory.Ozone(NOW,"a",1000));
        assertFalse(AirQualityAlert.calculate(history,"a",NOW).active());
        assertFalse(AirQualityAlert.calculate(history,"b",NOW).active());
        history.activeStationId = "b";
        assertEquals(AirQualityAlert.Level.BLACK,AirQualityAlert.calculate(history,"b",NOW).level());
        history.activeStationId = "a";
        assertFalse(AirQualityAlert.calculate(history,"a",NOW).active());
    }
    @Test public void highestBlackAlertStillUsesUsEvenWhenEuropeanConcentrationsQualify() {
        PollutantHistory history = new PollutantHistory(); history.activeStationId="nea:west";
        for(int i=0;i<8;i++) history.samples.add(new PollutantHistory.Sample(NOW-i*NowCast.HOUR,"nea:west","West",
                new double[]{1,1,1000,1,1,1}));
        var result=AirQualityAlert.calculate(history,"nea:west",NOW);
        assertEquals(6,HistorySeries.points(history,HistorySeries.Metric.EUROPEAN_AQI).get(0).value(),0);
        assertEquals(AirQualityAlert.Level.BLACK,result.level()); // Black threshold remains US-only.
        assertFalse(result.european());
    }
    @Test public void messagesExactlyMatchRequestedWords() {
        assertEquals("Air quality level 1 alert in the current location (European AQI) - Now",alert(0,0,8,4).message());
        assertEquals("Air quality level 2 alert in the current location (US AQI) - Now",alert(8,151,0,0).message());
        assertEquals("Air quality level 2 alert in the current location (European AQI) - Now",alert(0,0,8,5).message());
        assertEquals("Air quality level 2 alert in the current location (US AQI + European AQI) - Now",alert(8,151,8,5).message());
        assertEquals("Air quality level 3 alert in the current location (US AQI + European AQI) - Now",alert(8,201,8,6).message());
        assertEquals("Air quality level 4 (highest) in the current location (US AQI) - Now",alert(8,301,8,6).message());
        assertEquals("",AirQualityAlert.NONE.message());
    }
}
