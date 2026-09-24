package com.localairquality.app.data;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HistorySeriesTest {
    @Test public void neaEuropeanInputsExplainUnsupportedMeasurements() {
        PollutantHistory history = new PollutantHistory();
        history.samples.add(new PollutantHistory.Sample(100,"nea:west","West",new double[]{41,45,9,45,1000,4}));
        var point = HistorySeries.points(history, HistorySeries.Metric.EUROPEAN_AQI).get(0);
        assertFalse(point.inputs().get(0).hourlyNotSupplied());
        for (int p : new int[]{1,2,3,5}) {
            assertTrue(point.inputs().get(p).hourlyNotSupplied());
            assertEquals("Required hourly measurement not supplied", point.inputs().get(p).unavailableReason());
        }
    }

    @Test public void missingHistoryAndLegacyProviderLimitationHaveDifferentMessages() {
        var legacy = new NowCast.Input(Double.NaN,-1,0,"Hourly data unavailable");
        assertEquals("Required hourly measurement not supplied",legacy.unavailableReason());
        var waiting = new NowCast.Input(Double.NaN,-1,1,"12h NowCast");
        assertFalse(waiting.hourlyNotSupplied());
        assertEquals("Not enough hourly data",waiting.unavailableReason());
    }
    @Test public void eachIndexUsesItsOwnMainPollutantFromTheSelectedReport() {
        PollutantHistory history = new PollutantHistory();
        // PM2.5 drives US AQI; NO2 is in a worse European category.
        history.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{70,80,45,120,1000,9}));
        var us = HistorySeries.points(history, HistorySeries.Metric.US_AQI).get(0);
        var eu = HistorySeries.points(history, HistorySeries.Metric.EUROPEAN_AQI).get(0);
        assertEquals("PM2.5", us.mainPollutant());
        assertEquals(70, us.mainConcentration(), 0);
        assertEquals("NO2", eu.mainPollutant());
        assertEquals(120, eu.mainConcentration(), 0);
    }

    @Test public void mainPollutantDoesNotLeakBetweenReportsOrStations() {
        PollutantHistory history = new PollutantHistory();
        history.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{70,80,45,23,1000,9}));
        history.samples.add(new PollutantHistory.Sample(101,"b","B",new double[]{Double.NaN,Double.NaN,200,Double.NaN,Double.NaN,Double.NaN}));
        for (var metric : new HistorySeries.Metric[]{HistorySeries.Metric.US_AQI, HistorySeries.Metric.EUROPEAN_AQI}) {
            var points = HistorySeries.points(history, metric);
            assertEquals("PM2.5", points.get(0).mainPollutant());
            assertEquals(70, points.get(0).mainConcentration(), 0);
            assertEquals("O3", points.get(1).mainPollutant());
            assertEquals(200, points.get(1).mainConcentration(), 0);
            assertEquals(1, points.get(1).availablePollutants());
        }
    }

    @Test public void coIsStoredInMicrogramsAndExcludedFromEuropeanMainPollutant() {
        PollutantHistory history = new PollutantHistory();
        history.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{1,Double.NaN,Double.NaN,Double.NaN,10000,Double.NaN}));
        var us = HistorySeries.points(history, HistorySeries.Metric.US_AQI).get(0);
        var eu = HistorySeries.points(history, HistorySeries.Metric.EUROPEAN_AQI).get(0);
        assertEquals("CO", us.mainPollutant());
        assertEquals(10000, us.mainConcentration(), 0);
        assertEquals("PM2.5", eu.mainPollutant());
        assertEquals(1, eu.mainConcentration(), 0);
    }

    @Test public void tiesAndZeroReadingsKeepCalculatorChoice() {
        PollutantHistory history = new PollutantHistory();
        history.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{0,0,0,0,0,0}));
        for (var metric : new HistorySeries.Metric[]{HistorySeries.Metric.US_AQI, HistorySeries.Metric.EUROPEAN_AQI}) {
            var point = HistorySeries.points(history, metric).get(0);
            assertEquals("PM2.5", point.mainPollutant());
            assertEquals(0, point.mainConcentration(), 0);
        }
    }

    @Test public void missingReportsDoNotCreateMainPollutants() {
        PollutantHistory history = new PollutantHistory();
        history.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN}));
        assertTrue(HistorySeries.points(history, HistorySeries.Metric.US_AQI).isEmpty());
        assertTrue(HistorySeries.points(history, HistorySeries.Metric.EUROPEAN_AQI).isEmpty());
    }

    @Test public void concentrationFormattingMatchesHomeAndUsesPollutantUnits() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("70.0 µg/m³", AirQualityReading.formatConcentration("PM2.5", 70));
            assertEquals("120 µg/m³", AirQualityReading.formatConcentration("NO2", 120));
            assertEquals("10.0 mg/m³", AirQualityReading.formatConcentration("CO", 10000));
            assertEquals("—", AirQualityReading.formatConcentration("PM2.5", Double.NaN));
            Locale.setDefault(Locale.GERMANY);
            assertEquals("10,0 mg/m³", AirQualityReading.formatConcentration("CO", 10000));
        } finally { Locale.setDefault(previous); }
    }

    @Test public void historyIndicesMatchHomeFormulas() {
        AirQualityReading reading = new AirQualityReading();
        reading.pm25=70; reading.pm10=80; reading.o3=45; reading.no2=23; reading.co=1000; reading.so2=9;
        PollutantHistory h = new PollutantHistory();
        h.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{70,80,45,23,1000,9}));
        assertEquals(AqiCalculator.usAqi(reading).aqi(),HistorySeries.points(h,HistorySeries.Metric.US_AQI).get(0).value(),0);
        assertEquals(AqiCalculator.europeanAqi(reading).band(),HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).get(0).value(),0);
        assertEquals(6,HistorySeries.points(h,HistorySeries.Metric.US_AQI).get(0).availablePollutants());
        assertEquals(5,HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).get(0).availablePollutants());
    }
    @Test public void separateStationsAndTimesAreNeverCombined() {
        PollutantHistory h = new PollutantHistory();
        h.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{1,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN}));
        h.samples.add(new PollutantHistory.Sample(101,"b","B",new double[]{Double.NaN,Double.NaN,200,Double.NaN,Double.NaN,Double.NaN}));
        var eu = HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI);
        assertEquals(2,eu.size()); assertEquals(1,eu.get(0).value(),0); assertEquals(6,eu.get(1).value(),0);
        assertEquals(1,eu.get(0).availablePollutants());
        assertEquals("A",eu.get(0).stationName()); assertEquals("B",eu.get(1).stationName());
    }
    @Test public void coOnlyHasUsIndexButNoEuropeanIndex() {
        PollutantHistory h = new PollutantHistory();
        h.samples.add(new PollutantHistory.Sample(100,"a","A",new double[]{Double.NaN,Double.NaN,Double.NaN,Double.NaN,1000,Double.NaN}));
        assertEquals(1,HistorySeries.points(h,HistorySeries.Metric.US_AQI).size());
        assertTrue(HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).isEmpty());
        assertEquals(1000,HistorySeries.points(h,HistorySeries.Metric.CO).get(0).value(),0);
    }
    @Test public void usLegendAndPointColorsMatchHome() {
        var metric=HistorySeries.Metric.US_AQI;
        assertEquals(4,HistorySeries.band(metric,161));
        assertEquals("Unhealthy",HistorySeries.level(metric,4));
        assertEquals(AqiCalculator.usColor(161),HistorySeries.color(metric,4));
    }
    private static double coMicrograms(double ppm) { return ppm * 28.01 / 24.45 * 1000; }

    @Test public void coUsesUsCategoriesAtEveryConcentrationBoundary() {
        double[] lowerPpm={0,4.5,9.5,12.5,15.5,30.5};
        double[] upperPpm={4.4,9.4,12.4,15.4,30.4,50.4};
        int[] indexLow={0,51,101,151,201,301}, indexHigh={50,100,150,200,300,500};
        String[] levels={"Good","Moderate","Unhealthy for Sensitive Groups","Unhealthy","Very Unhealthy","Hazardous"};
        for(int i=0;i<6;i++) {
            assertEquals("lower ppm "+lowerPpm[i],indexLow[i],AqiCalculator.coIndex(coMicrograms(lowerPpm[i])));
            assertEquals("upper ppm "+upperPpm[i],indexHigh[i],AqiCalculator.coIndex(coMicrograms(upperPpm[i])));
            assertEquals(i+1,HistorySeries.band(HistorySeries.Metric.CO,coMicrograms(lowerPpm[i])));
            assertEquals(i+1,HistorySeries.band(HistorySeries.Metric.CO,coMicrograms(upperPpm[i])));
            assertEquals(levels[i],HistorySeries.level(HistorySeries.Metric.CO,i+1));
            assertEquals(AqiCalculator.usColor(indexLow[i]),HistorySeries.color(HistorySeries.Metric.CO,i+1));
        }
        assertEquals(6,HistorySeries.band(HistorySeries.Metric.CO,coMicrograms(100)));
    }
    @Test public void coTruncatesPpmAndUsesMicrogramsDespiteMilligramDisplay() {
        assertEquals(1,HistorySeries.band(HistorySeries.Metric.CO,coMicrograms(4.499)));
        assertEquals(2,HistorySeries.band(HistorySeries.Metric.CO,coMicrograms(4.501)));
        // Stored 10,000 µg/m³ is displayed as 10.0 mg/m³ and is Moderate (~8.7 ppm).
        assertEquals(2,HistorySeries.band(HistorySeries.Metric.CO,10000));
        assertEquals(1,HistorySeries.band(HistorySeries.Metric.CO,1000));
    }
    @Test public void missingCoRemainsUnratedInsteadOfGreen() {
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}) {
            int band=HistorySeries.band(HistorySeries.Metric.CO,value);
            assertEquals(0,band);
            assertEquals("Not rated",HistorySeries.level(HistorySeries.Metric.CO,band));
            assertEquals(AqiCalculator.euColor(0),HistorySeries.color(HistorySeries.Metric.CO,band));
        }
    }
    @Test public void otherPollutantsKeepEuropeanCategories() {
        for(var metric:HistorySeries.Metric.values()) {
            if(metric.isIndex() || metric==HistorySeries.Metric.CO) continue;
            int expected=AqiCalculator.europeanPollutantBand(metric.label,70);
            assertEquals(expected,HistorySeries.band(metric,70));
            assertEquals(AqiCalculator.euColor(expected),HistorySeries.color(metric,expected));
            assertEquals(AqiCalculator.europeanLevel(expected),HistorySeries.level(metric,expected));
        }
    }
}
