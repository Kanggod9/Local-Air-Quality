package com.localairquality.app.data;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class AlertTimingTest {
    private static final long NOW=2_000*NowCast.HOUR;
    private static HistorySeries.Point point(int hoursAgo,double value) {
        return new HistorySeries.Point(NOW-hoursAgo*NowCast.HOUR,"A",value,1,"PM2.5",1);
    }
    private static List<HistorySeries.Point> range(int first,int count,double value) {
        var points=new ArrayList<HistorySeries.Point>();
        for(int i=first;i<first+count;i++) points.add(point(i,value));
        return points;
    }
    private static AirQualityAlert.Result result(List<HistorySeries.Point> us,List<HistorySeries.Point> eu) {
        return AirQualityAlert.fromPoints(us,eu,NOW);
    }
    @Test public void ageIsRelativeToLatestRecordedHourNotFetchOrPhoneTime() {
        var us=range(2,8,201); us.add(point(0,151)); us.add(point(1,151));
        var alert=result(us,List.of());
        assertEquals(AirQualityAlert.Level.PURPLE,alert.level());
        assertEquals(2,alert.ageHours());
        assertTrue(alert.message().endsWith(" - 2 hours ago"));
        assertEquals(2,AirQualityAlert.fromPoints(us,List.of(),NOW+2*NowCast.HOUR).ageHours());
        assertEquals("Now",result(range(2,8,201),List.of()).timeLabel());
    }
    @Test public void singularTimeAndReturnBeforeTimeoutRefreshNow() {
        var us=range(1,8,201); us.add(point(0,151));
        assertEquals("1 hour ago",result(us,List.of()).timeLabel());
        us.remove(us.size()-1); us.add(point(0,201));
        assertEquals("Now",result(us,List.of()).timeLabel());
    }
    @Test public void sevenHoursRetainsPurpleExactlyEightDowngradesToDeepRed() {
        var us=range(7,8,201); us.addAll(range(0,7,151));
        var alert=result(us,List.of());
        assertEquals(AirQualityAlert.Level.PURPLE,alert.level());
        assertEquals("7 hours ago",alert.timeLabel());
        us=range(8,8,201); us.addAll(range(0,8,151));
        alert=result(us,List.of());
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert.level());
        assertEquals(8,alert.windowHours());
        assertEquals(8,alert.usHours());
        assertEquals("Now",alert.timeLabel());
    }
    @Test public void expiredHighestWithEightCleanRecentHoursRemovesAlert() {
        var us=range(8,8,201); us.addAll(range(0,8,40));
        assertFalse(result(us,List.of()).active());
        var eu=range(8,8,4); eu.addAll(range(0,8,1));
        assertFalse(result(List.of(),eu).active());
    }
    @Test public void bothSourcesBecomesEuropeanThenUsThenBothAtSameLevel() {
        var us=range(1,8,201); us.add(point(0,151));
        var eu=range(0,9,6);
        var alert=result(us,eu);
        assertEquals(AirQualityAlert.Level.PURPLE,alert.level());
        assertEquals("European AQI",alert.source());
        assertEquals("Now",alert.timeLabel());
        us.remove(us.size()-1); us.add(point(0,201));
        eu.remove(0); eu.add(point(0,5));
        assertEquals("US AQI",result(us,eu).source());
        eu.remove(eu.size()-1); eu.add(point(0,6));
        assertEquals("US AQI + European AQI",result(us,eu).source());
    }
    @Test public void bothSourcesBelowLatestUsesMostRecentQualifyingSourceOnly() {
        var us=range(2,8,201); us.addAll(range(0,2,151));
        var eu=range(1,8,6); eu.add(point(0,5));
        var alert=result(us,eu);
        assertEquals("European AQI",alert.source()); assertEquals("1 hour ago",alert.timeLabel());
        eu=range(2,8,6); eu.addAll(range(0,2,5));
        alert=result(us,eu);
        assertEquals("US AQI + European AQI",alert.source()); assertEquals("2 hours ago",alert.timeLabel());
    }
    @Test public void insufficientNewSourceNeverBypassesEightHourEligibility() {
        var us=range(1,8,201); us.add(point(0,151));
        var alert=result(us,List.of(point(0,6)));
        assertEquals("US AQI",alert.source()); assertEquals("1 hour ago",alert.timeLabel());
        assertEquals(1,alert.europeanHours());
    }
    @Test public void recentReassessmentDoesNotPoolIndicesOrFillMissingSlots() {
        var us=range(8,8,201); us.addAll(range(0,4,151)); us.addAll(range(4,4,40));
        var eu=range(4,4,5); eu.addAll(range(0,4,1));
        assertFalse(result(us,eu).active());
        us=range(8,8,201); us.addAll(range(0,7,151));
        assertFalse(result(us,List.of()).active());
    }
    @Test public void gapsUseRealHourlyDistanceNotCompressedPointCount() {
        var us=range(9,8,201); us.add(point(0,40));
        assertFalse(result(us,List.of()).active());
        us=range(3,8,201); us.add(point(0,40));
        assertEquals("3 hours ago",result(us,List.of()).timeLabel());
    }
    @Test public void timeoutCanChangeIndexWhileDowngrading() {
        var us=range(8,8,301); us.addAll(range(0,8,40));
        var eu=range(0,8,4);
        var alert=result(us,eu);
        assertEquals(AirQualityAlert.Level.RED,alert.level());
        assertEquals("European AQI",alert.source()); assertEquals("Now",alert.timeLabel());
    }
    @Test public void latestHigherDataResetsTimeButNotTheEightOfTwentyFourRule() {
        var us=range(8,8,201); us.addAll(range(0,8,40));
        us.remove(us.size()-8); // Replace latest good hour with a new Purple hour.
        us.add(point(0,201));
        assertEquals(AirQualityAlert.Level.PURPLE,result(us,List.of()).level());
        assertEquals("Now",result(us,List.of()).timeLabel());
        assertFalse(result(range(0,7,201),List.of()).active());
    }
    @Test public void oldEligibilityStillExpiresOutsideLastTwentyFourHours() {
        var us=range(17,8,201); us.addAll(range(0,8,151));
        var alert=result(us,List.of());
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert.level());
        assertEquals(24,alert.windowHours());
    }
    @Test public void higherSingleSourceBeatsLowerBothSourcesBeforeTimeout() {
        var us=range(1,8,301); us.add(point(0,201));
        var eu=range(0,9,6);
        var alert=result(us,eu);
        assertEquals(AirQualityAlert.Level.BLACK,alert.level());
        assertEquals("US AQI",alert.source()); assertEquals("1 hour ago",alert.timeLabel());
    }
}
