package com.localairquality.app.data;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HistorySeriesTest {
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
