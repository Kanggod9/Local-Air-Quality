package com.localairquality.app.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlertGuidanceTest {
    private static final long NOW=2_000*NowCast.HOUR;
    private static AirQualityReading reading(AirQualityAlert.Level level) {
        AirQualityReading r=new AirQualityReading(); r.stationId="test:a";
        r.measuredAtMillis=NOW; r.pm25=1; r.pm10=1; r.o3=1; r.no2=1; r.so2=1; r.co=1;
        r.alert=new AirQualityAlert.Result(level,level.severity>=2,level!=AirQualityAlert.Level.BLACK,
                level.severity>=2?8:0,level==AirQualityAlert.Level.BLACK?0:8);
        return r;
    }
    private static String content(AirQualityReading r) {
        return AlertGuidance.forReading(r,NOW).sections().stream().map(s -> s.title()+"\n"+s.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }
    @Test public void absentOrExpiredAlertHasNoStaleAdvice() {
        assertEquals("No active alert",AlertGuidance.forReading(null,NOW).sections().get(0).title());
        var r=reading(AirQualityAlert.Level.NONE);
        assertTrue(AlertGuidance.forReading(r,NOW).sources().isEmpty());
        assertFalse(content(r).contains("Current main pollutant"));
    }
    @Test public void reasonsMatchWinningThresholdsAndIndependentCounts() {
        assertTrue(content(reading(AirQualityAlert.Level.RED)).contains("Poor or higher in 8"));
        assertTrue(content(reading(AirQualityAlert.Level.DEEP_RED)).contains("8 hours at Unhealthy or higher"));
        var r=reading(AirQualityAlert.Level.PURPLE);
        r.alert=new AirQualityAlert.Result(AirQualityAlert.Level.PURPLE,true,false,9,4);
        String body=content(r);
        assertTrue(body.contains("9 hours at Very Unhealthy or higher — qualifies"));
        assertTrue(body.contains("4 hours at Extremely Poor or higher — fewer than 8"));
        assertTrue(body.contains("never added together"));
        assertTrue(content(reading(AirQualityAlert.Level.BLACK)).contains("Hazardous in 8"));
    }
    @Test public void pollutantAdviceReflectsEachIndexNotJustUsDominant() {
        var r=reading(AirQualityAlert.Level.RED); r.co=50000; r.no2=110;
        String body=content(r);
        assertTrue(body.contains("US AQI+\nCO · 50.0 mg/m³"));
        assertTrue(body.contains("European AQI\nNO2 · 110 µg/m³"));
        assertTrue(body.contains("do not filter this gas"));
        assertTrue(body.contains("leave for fresh air immediately"));
    }
    @Test public void sharedMainPollutantAppearsOnlyOnce() {
        var r=reading(AirQualityAlert.Level.DEEP_RED); r.pm25=100;
        var details=AlertGuidance.forReading(r,NOW);
        assertEquals(1,details.sections().stream().filter(s -> s.title().startsWith("Current main pollutant")).count());
        assertTrue(content(r).contains("US AQI+ and European AQI"));
        assertTrue(content(r).contains("N95"));
        assertTrue(content(r).contains("does not filter gases"));
    }
    @Test public void currentWorseConditionsEscalateAdviceWithoutChangingAlert() {
        var r=reading(AirQualityAlert.Level.RED); r.co=65000;
        assertTrue(content(r).contains("Avoid outdoor physical activity."));
        assertEquals(AirQualityAlert.Level.RED,r.alert.level());
        assertEquals(4,AlertGuidance.currentSeverity(301,1));
        assertEquals(3,AlertGuidance.currentSeverity(150,6));
    }
    @Test public void stalePartialAndSingaporeInputsAreDisclosed() {
        var r=reading(AirQualityAlert.Level.RED); r.measuredAtMillis=NOW-4*NowCast.HOUR;
        r.stationId="nea:west"; r.co=Double.NaN; r.o3=1000;
        String body=content(r);
        assertTrue(body.contains("Readings need an update"));
        assertTrue(body.contains("Partial coverage"));
        assertTrue(body.contains("Current main pollutant · US AQI+ and European AQI\nO3"));
        assertTrue(body.contains("not the official hourly EEA index"));
        assertFalse(body.contains("incompatible averaging-period measurements are excluded"));
    }
    @Test public void allPollutantsHaveSpecificTextAndOfficialLinks() {
        for(String pollutant:PollutantHistory.LABELS) {
            var r=reading(AirQualityAlert.Level.PURPLE);
            switch(pollutant) {
                case "PM2.5" -> r.pm25=160;
                case "PM10" -> r.pm10=500;
                case "O3" -> r.o3=800;
                case "NO2" -> r.no2=1500;
                case "SO2" -> r.so2=2000;
                case "CO" -> r.co=65000;
            }
            assertTrue(pollutant,content(r).contains("\n"+pollutant+" · "));
            var sources=AlertGuidance.forReading(r,NOW).sources();
            assertEquals(sources.size(),sources.stream().map(AlertGuidance.Source::url).distinct().count());
            assertTrue(sources.stream().allMatch(s -> s.url().startsWith("https://")));
        }
    }
    @Test public void levelFourWordingCanRepresentAnyExplicitSource() {
        assertEquals("Air quality level 4 (highest) in the current location (US AQI + European AQI) - Now",
                new AirQualityAlert.Result(AirQualityAlert.Level.BLACK,true,true,8,8).message());
        assertEquals("Air quality level 4 (highest) in the current location (European AQI) - Now",
                new AirQualityAlert.Result(AirQualityAlert.Level.BLACK,false,true,0,8).message());
    }
    @Test public void timingAndReassessmentAreExplained() {
        var r=reading(AirQualityAlert.Level.DEEP_RED);
        r.alert=new AirQualityAlert.Result(AirQualityAlert.Level.DEEP_RED,true,true,8,8,0,8);
        String body=content(r);
        assertTrue(body.contains("recent eight hourly slots"));
        assertTrue(body.contains("relative to the latest recorded hour"));
        r.alert=new AirQualityAlert.Result(AirQualityAlert.Level.PURPLE,false,true,8,9,1,24);
        body=content(r);
        assertTrue(body.contains("Last matching reading: 1 hour ago"));
        assertTrue(body.contains("eight-hour requirement met, but not the most recent triggering source"));
    }
}
