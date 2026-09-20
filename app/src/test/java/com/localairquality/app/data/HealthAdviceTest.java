package com.localairquality.app.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class HealthAdviceTest {
    private static final long NOW = 1_800_000_000_000L;
    private AirQualityReading reading(double pm25) {
        AirQualityReading r = new AirQualityReading();
        r.pm25 = pm25;
        r.measuredAtMillis = NOW;
        return r;
    }

    @Test public void hourlySeventyProtectsSensitivePeopleEvenWhenEeaIsOnlyPoor() {
        HealthAdvice.Advice advice = HealthAdvice.forReading(reading(70), NOW);
        assertEquals(3, advice.caution());
        assertTrue(advice.everyone().contains("shorter"));
        assertTrue(advice.sensitive().contains("Avoid long"));
    }
    @Test public void eeaAdviceIsUsedWhenMoreCautious() {
        AirQualityReading r = reading(0);
        r.no2 = 160; // EEA Extremely Poor, but US NO2 below 100.
        HealthAdvice.Advice advice = HealthAdvice.forReading(r, NOW);
        assertEquals(4, advice.caution());
        assertTrue(advice.sensitive().contains("Avoid outdoor"));
    }
    @Test public void hazardousReadingsGiveStrongestAdvice() {
        HealthAdvice.Advice advice = HealthAdvice.forReading(reading(300), NOW);
        assertEquals(5, advice.caution());
        assertTrue(advice.sensitive().contains("Stay in cleaner indoor air"));
    }
    @Test public void goodReadingsAllowUsualActivities() {
        HealthAdvice.Advice advice = HealthAdvice.forReading(reading(4), NOW);
        assertEquals(0, advice.caution());
        assertTrue(advice.everyone().contains("usual outdoor"));
    }
    @Test public void cautionNeverDecreasesAsParticlePollutionIncreases() {
        int previous = 0;
        for (int concentration = 0; concentration <= 500; concentration++) {
            int caution = HealthAdvice.forReading(reading(concentration), NOW).caution();
            assertTrue("PM2.5 " + concentration, caution >= previous);
            previous = caution;
        }
    }
    @Test public void adviceUsesCurrentConcentrationsInsteadOfOldCachedIndices() {
        AirQualityReading r = reading(70);
        r.usAqi = 10;
        r.euBand = 1;
        assertEquals(3, HealthAdvice.forReading(r, NOW).caution());
        assertTrue(HealthAdvice.forReading(r, NOW).title().contains("partial data"));
    }
    @Test public void staleAndMissingReadingsDoNotGiveReassuringAdvice() {
        assertEquals("Readings need an update",
                HealthAdvice.forReading(reading(4), NOW + 4 * 3600_000L).title());
        assertEquals("Current readings unavailable",
                HealthAdvice.forReading(new AirQualityReading(), NOW).title());
    }
}
