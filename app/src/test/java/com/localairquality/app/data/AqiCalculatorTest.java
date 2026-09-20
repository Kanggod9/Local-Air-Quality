package com.localairquality.app.data;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class AqiCalculatorTest {
    @Test
    public void updatedPm25BreakpointProducesModerate() {
        AirQualityReading reading = new AirQualityReading();
        reading.pm25 = 9.1;
        AqiCalculator.UsResult result = AqiCalculator.usAqi(reading);
        assertEquals(51, result.aqi());
        assertEquals("Moderate", result.level());
    }

    @Test
    public void pm25ExampleProducesSensitiveGroupBand() {
        AirQualityReading reading = new AirQualityReading();
        reading.pm25 = 48.0;
        AqiCalculator.UsResult result = AqiCalculator.usAqi(reading);
        assertEquals(132, result.aqi());
        assertEquals("PM2.5", result.pollutant());
    }

    @Test
    public void aqiPlusUsesDisplayedHourlyPm25() {
        AirQualityReading reading = new AirQualityReading();
        reading.pm25 = 70.0;
        reading.pm25NowCast = 51.0;
        AqiCalculator.UsResult result = AqiCalculator.usAqi(reading);
        assertEquals(161, result.aqi());
        assertEquals("Unhealthy", result.level());
        assertEquals("PM2.5", result.pollutant());
    }

    @Test
    public void europeanIndexUsesWorstPollutant() {
        AirQualityReading reading = new AirQualityReading();
        reading.pm25 = 12;
        reading.pm10 = 130;
        reading.o3 = 65;
        AqiCalculator.EuResult result = AqiCalculator.europeanAqi(reading);
        assertEquals(4, result.band());
        assertEquals("Poor", result.level());
        assertEquals("PM10", result.pollutant());
    }

    @Test
    public void newestEuropeanStandardMarksSeventyAsPoor() {
        AirQualityReading reading = new AirQualityReading();
        reading.pm25 = 70.0;
        AqiCalculator.EuResult result = AqiCalculator.europeanAqi(reading);
        assertEquals(4, result.band());
        assertEquals("Poor", result.level());
        assertEquals("PM2.5", result.pollutant());
    }

    @Test
    public void nowCastWeightsRecentValues() {
        double result = AqiCalculator.pmNowCast(List.of(10.0, 20.0, 40.0, 80.0));
        assertEquals(21.33, result, 0.01);
    }
}
