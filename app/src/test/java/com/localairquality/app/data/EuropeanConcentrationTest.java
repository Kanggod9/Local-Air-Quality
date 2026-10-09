package com.localairquality.app.data;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Concentration-only European indices must never loosen the independent NowCast rules. */
public class EuropeanConcentrationTest {
    private static final long NOW = 2000 * NowCast.HOUR;
    private static AirQualityReading reading(String station, double[] v) {
        AirQualityReading r = new AirQualityReading();
        r.stationId=station; r.measuredAtMillis=NOW;
        r.pm25=v[0]; r.pm10=v[1]; r.o3=v[2]; r.no2=v[3]; r.co=v[4]; r.so2=v[5];
        return r;
    }
    @Test public void everySupportedPollutantCanDriveEuropeanIndexAtEveryStation() {
        double[] high = {141,271,181,151,65000,276};
        for (String station : new String[]{"nea:west","openaq:1","national:1"}) {
            for (int p : new int[]{0,1,2,3,5}) {
                double[] v = new double[6]; Arrays.fill(v,Double.NaN); v[p]=high[p];
                var result=AqiCalculator.europeanAqi(reading(station,v));
                assertEquals(6,result.band());
                assertEquals(PollutantHistory.LABELS[p],result.pollutant());
                assertEquals("Extremely Poor",result.level());
            }
        }
    }
    @Test public void carbonMonoxideNeverCreatesEuropeanCategory() {
        var r=reading("nea:west",new double[]{Double.NaN,Double.NaN,Double.NaN,Double.NaN,65000,Double.NaN});
        assertEquals(0,AqiCalculator.europeanAqi(r).band());
        assertTrue(AqiCalculator.usAqi(r).aqi()>=301);
    }
    @Test public void hourlyMetadataDoesNotChangeEuropeanHistoryOrMutateSamples() {
        for (String station : new String[]{"nea:west","openaq:1"}) for(int mask : new int[]{0,1,63}) {
            double[] v={12,130,65,120,65000,9}; double[] before=v.clone();
            PollutantHistory h=new PollutantHistory(); h.activeStationId=station;
            h.samples.add(new PollutantHistory.Sample(NOW,station,"Station",v,mask));
            var point=HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).get(0);
            var expected=AqiCalculator.europeanAqi(reading(station,v));
            assertEquals(expected.band(),point.value(),0);
            assertEquals("NO2",point.mainPollutant()); assertEquals(120,point.mainConcentration(),0);
            assertEquals(5,point.availablePollutants()); assertTrue(point.inputs().isEmpty());
            assertArrayEquals(before,v,0); assertEquals(mask,h.samples.get(0).hourlyMask());
        }
    }
    @Test public void cachedReadingRecalculatesEuropeanIndexOnUpgrade() throws Exception {
        var r=reading("nea:west",new double[]{12,130,65,120,1000,9});
        r.calculateIndices();
        var json=r.toJson(); json.put("euBand",2); json.put("euLevel","Fair");
        var loaded=AirQualityReading.fromJson(json.toString());
        assertEquals(5,loaded.euBand); assertEquals("Very Poor",loaded.euLevel);
        assertEquals(r.usAqi,loaded.usAqi);
    }
    @Test public void persistedHistoryRecalculatesWithoutRequiringHourlyRecovery() throws Exception {
        PollutantHistory h=new PollutantHistory(); h.activeStationId="openaq:1";
        h.samples.add(new PollutantHistory.Sample(NOW,"openaq:1","Station",
                new double[]{Double.NaN,Double.NaN,181,Double.NaN,Double.NaN,Double.NaN},0));
        ByteArrayOutputStream out=new ByteArrayOutputStream(); h.write(out);
        var restored=PollutantHistory.read(new ByteArrayInputStream(out.toByteArray()));
        var point=HistorySeries.points(restored,HistorySeries.Metric.EUROPEAN_AQI).get(0);
        assertEquals(6,point.value(),0); assertEquals("O3",point.mainPollutant());
        assertEquals(1,point.availablePollutants()); assertEquals(0,restored.samples.get(0).hourlyMask());
    }
    @Test public void europeanAlertsUseRollingStationConcentrationsButNowcastDoesNot() {
        PollutantHistory h=new PollutantHistory(); h.activeStationId="nea:west";
        for(int i=0;i<8;i++) h.samples.add(new PollutantHistory.Sample(NOW-i*NowCast.HOUR,
                "nea:west","West",new double[]{1,1,1,151,1,1}));
        var alert=AirQualityAlert.calculate(h,"nea:west",NOW);
        assertEquals(AirQualityAlert.Level.PURPLE,alert.level());
        assertFalse(alert.us()); assertTrue(alert.european()); assertEquals(8,alert.europeanHours());
        var nowcast=NowCast.calculate(h.samples,NOW,"nea:west","West");
        assertEquals(1,nowcast.count()); assertEquals("PM2.5",nowcast.pollutant());
        assertTrue(nowcast.inputs().get(3).hourlyNotSupplied());
    }
    @Test public void nonHourlyOpenAqReadingsRemainExcludedFromNowcast() {
        PollutantHistory h=new PollutantHistory();
        for(int i=0;i<3;i++) h.samples.add(new PollutantHistory.Sample(NOW-i*NowCast.HOUR,
                "openaq:1","Station",new double[]{70,80,65,120,1000,9},0));
        assertEquals(3,HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).size());
        assertEquals(-1,NowCast.calculate(h.samples,NOW,"openaq:1","Station").aqi());
    }
    @Test public void fullSingaporeCoverageIsNotFalselyMarkedPartial() {
        var r=reading("nea:west",new double[]{1,1,1,80,1,1});
        r.alert=new AirQualityAlert.Result(AirQualityAlert.Level.RED,false,true,0,8);
        String body=AlertGuidance.forReading(r,NOW).sections().toString();
        assertFalse(body.contains("Partial coverage"));
    }
}
