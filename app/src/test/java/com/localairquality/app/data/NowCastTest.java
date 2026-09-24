package com.localairquality.app.data;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class NowCastTest {
    private static final long H = NowCast.HOUR, NOW = 1000L*PollutantHistory.WINDOW;
    private static final double NA = Double.NaN;
    private static double[] values(double pm, double ozone) { return new double[]{pm,NA,ozone,NA,NA,NA}; }
    private static PollutantHistory.Sample sample(long time, String station, double[] values) {
        return new PollutantHistory.Sample(time,station,station,values);
    }
    private static void add(PollutantHistory history, long time, double pm, double ozone, long now) {
        long[] times = new long[6]; Arrays.fill(times,time);
        history.add("GB","Britain","openaq:1","Station","Here",times,values(pm,ozone),now);
    }

    @Test public void particleRequiresTwoOfLatestThreeAndKeepsMissingHourWeights() {
        assertTrue(Double.isNaN(NowCast.particle(new double[]{10,NA,NA,80})));
        assertEquals((10+80*.25)/1.25,NowCast.particle(new double[]{10,NA,80}),1e-9);
        assertEquals(0,NowCast.particle(new double[]{0,0,NA}),0);
        assertEquals(21.33333333,NowCast.particle(new double[]{10,20,40,80}),1e-7);
        assertEquals(30,NowCast.particle(new double[]{30,30,30}),0);
    }
    @Test public void particleTwelveHourWindowDoesNotCollapseGaps() {
        double[] data = new double[13]; Arrays.fill(data,NA); data[0]=10; data[1]=20; data[12]=10000;
        assertEquals(40.0/3,NowCast.particle(data),1e-9);
    }
    @Test public void duplicatesFutureAndOtherStationsCannotFillMissingHours() {
        var reading = NowCast.calculate(List.of(sample(NOW+60000,"a",values(10,NA)),
                sample(NOW+30000,"a",values(80,NA)),sample(NOW-H,"b",values(80,NA)),
                sample(NOW+H,"a",values(80,NA))),NOW+60000,"a","A");
        assertEquals(-1,reading.aqi());
    }
    @Test public void sameStationUsesLatestMeasurementPerHour() {
        var reading = NowCast.calculate(List.of(sample(NOW,"a",values(10,NA)),
                sample(NOW-H,"a",values(20,NA)),sample(NOW-60000,"a",values(80,NA))),NOW,"a","A");
        // NOW-60s is in the previous clock hour, replacing that hour's earlier report.
        assertEquals((10+80*.5)/1.5,reading.inputs().get(0).concentration(),1e-8);
        assertEquals(2,reading.inputs().get(0).hours());
    }
    @Test public void coNeedsSixHoursAndUsesEightHourMean() {
        List<PollutantHistory.Sample> samples = new ArrayList<>();
        for (int i=0;i<6;i++) samples.add(sample(NOW-i*H,"a",new double[]{NA,NA,NA,NA,10000,NA}));
        assertEquals(NowCast.index(4,10000),NowCast.calculate(samples,NOW,"a","A").aqi());
        samples.remove(5);
        assertEquals(-1,NowCast.calculate(samples,NOW,"a","A").aqi());
    }
    @Test public void so2HighValuesRequireEighteenHoursAndUseTwentyFourHourConcentration() {
        List<PollutantHistory.Sample> samples = new ArrayList<>();
        double high = 400*64.066/24.45;
        for(int i=0;i<18;i++) samples.add(sample(NOW-i*H,"a",new double[]{NA,NA,NA,NA,NA,high}));
        var result = NowCast.calculate(samples,NOW,"a","A");
        assertTrue(result.aqi()>200); assertEquals("24h mean",result.inputs().get(5).method());
        samples.remove(17);
        assertEquals(-1,NowCast.calculate(samples,NOW,"a","A").aqi());
    }
    @Test public void so2HighHourAndLowDailyMeanIsTwoHundred() {
        List<PollutantHistory.Sample> samples = new ArrayList<>();
        for(int i=0;i<18;i++) samples.add(sample(NOW-i*H,"a",new double[]{NA,NA,NA,NA,NA,(i==0?400:20)*64.066/24.45}));
        assertEquals(200,NowCast.calculate(samples,NOW,"a","A").aqi());
    }
    @Test public void highDailySo2StillCountsAfterCurrentHourDrops() {
        List<PollutantHistory.Sample> samples=new ArrayList<>();
        for(int i=0;i<18;i++) samples.add(sample(NOW-i*H,"a",new double[]{NA,NA,NA,NA,NA,(i==0?20:400)*64.066/24.45}));
        var result=NowCast.calculate(samples,NOW,"a","A");
        assertTrue(result.aqi()>200); assertEquals("24h mean",result.inputs().get(5).method());
    }
    @Test public void newPmBreakpointsAndUncappedHazardousValues() {
        assertEquals(50,NowCast.index(0,9)); assertEquals(51,NowCast.index(0,9.1));
        assertEquals(161,NowCast.index(0,70)); assertEquals(500,NowCast.index(0,325.4));
        assertTrue(NowCast.index(0,400)>500);
        assertEquals(-1,NowCast.index(2,.201*1000*48/24.45));
        assertEquals(50,NowCast.index(4,4.4*1000*28.01/24.45));
    }
    @Test public void highOneHourOzoneCanOverrideSurrogate() {
        var reading = NowCast.calculate(List.of(sample(NOW,"a",values(NA,.405*1000*48/24.45))),NOW,"a","A");
        assertEquals(301,reading.aqi()); assertEquals("1h high ozone",reading.inputs().get(2).method());
        assertTrue(reading.ozoneEstimate()>0);
    }
    @Test public void neaRollingInputsAreNeverTreatedAsHourlyMeans() {
        var samples = List.of(sample(NOW,"nea:west",new double[]{70,1000,1000,1000,50000,1000}),
                sample(NOW-H,"nea:west",new double[]{70,1000,1000,1000,50000,1000}));
        var reading = NowCast.calculate(samples,NOW,"nea:west","West");
        assertEquals(1,reading.count()); assertEquals("PM2.5",reading.pollutant()); assertEquals(161,reading.aqi());
        AirQualityReading eu = new AirQualityReading(); eu.stationId="nea:west"; eu.pm25=70; eu.o3=1000;
        assertEquals(4,AqiCalculator.europeanAqi(eu).band());
    }
    @Test public void ozoneOnlyMayOutliveTwentyFourHoursAndChartsNeverShowCalibration() throws Exception {
        PollutantHistory history = new PollutantHistory(); add(history,NOW,20,60,NOW);
        var request = history.beginRecovery(NOW);
        history.acceptRecovery(request,List.of(sample(NOW-300*H,"openaq:1",values(100,80)),
                sample(NOW-337*H,"openaq:1",values(100,90)),sample(NOW-2*H,"openaq:1",values(20,60))),NOW);
        history.updateNowcasts(NOW);
        assertTrue(history.samples.stream().allMatch(s->s.measuredAt()>=NOW-PollutantHistory.WINDOW));
        assertTrue(history.ozoneCalibration.stream().anyMatch(s->s.measuredAt()==NOW-300*H));
        assertTrue(history.ozoneCalibration.stream().noneMatch(s->s.measuredAt()==NOW-337*H));
        for(var metric:HistorySeries.Metric.values()) assertTrue(HistorySeries.points(history,metric).stream().allMatch(p->p.measuredAt()>=NOW-24*H));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); history.write(out);
        var restored = PollutantHistory.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(history.ozoneCalibration,restored.ozoneCalibration);
        assertEquals(history.nowcasts,restored.nowcasts);
        restored.prune(NOW+37*H);
        assertTrue(restored.samples.isEmpty()); assertTrue(restored.nowcasts.isEmpty());
        assertTrue(restored.ozoneCalibration.stream().noneMatch(s->s.measuredAt()==NOW-300*H));
    }
    @Test public void pastNowcastDoesNotChangeWhenRawInputsExpire() {
        PollutantHistory history = new PollutantHistory();
        for(int i=11;i>=0;i--) {
            add(history,NOW-i*H,i==0?10:80,NA,NOW-i*H);
            history.acceptRecovery(history.beginRecovery(NOW),List.of(sample(NOW-i*H,"openaq:1",values(i==0?10:80,NA))),NOW);
        }
        history.updateNowcasts(NOW);
        double before=history.latestNowcast("openaq:1").inputs().get(0).concentration();
        assertTrue(Double.isFinite(before));
        add(history,NOW+20*H,40,NA,NOW+20*H); history.updateNowcasts(NOW+20*H);
        var recorded=history.nowcasts.stream().filter(s->s.measuredAt()==NOW).findFirst().orElseThrow();
        assertEquals(before,recorded.inputs().get(0).concentration(),0);
    }
    @Test public void latestOpenAqRequiresHourlyRecoveryAndCannotReplaceVerifiedInputs() throws Exception {
        PollutantHistory history = new PollutantHistory();
        add(history,NOW-H,20,60,NOW-H); add(history,NOW,10,60,NOW);
        history.updateNowcasts(NOW);
        assertEquals(-1,history.latestNowcast("openaq:1").aqi());
        assertTrue(history.ozoneCalibration.isEmpty());
        history.acceptRecovery(history.beginRecovery(NOW),List.of(sample(NOW-H,"openaq:1",values(20,60)),
                sample(NOW,"openaq:1",values(10,60))),NOW);
        history.updateNowcasts(NOW);
        assertEquals(40.0/3,history.latestNowcast("openaq:1").inputs().get(0).concentration(),1e-9);
        add(history,NOW,500,500,NOW); history.updateNowcasts(NOW);
        assertEquals(40.0/3,history.latestNowcast("openaq:1").inputs().get(0).concentration(),1e-9);
        assertEquals(60,history.ozoneCalibration.get(1).value(),0);
        ByteArrayOutputStream out=new ByteArrayOutputStream(); history.write(out);
        var restored=PollutantHistory.read(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(restored.samples.get(0).isHourly(0));
        assertFalse(restored.samples.get(0).isHourly(1));
    }
    @Test public void countryResetClearsOzoneAndRejectsLateRecovery() {
        PollutantHistory history=new PollutantHistory(); add(history,NOW,20,60,NOW);
        var old=history.beginRecovery(NOW);
        history.add("FR","France","openaq:2","B","Here",new long[6],values(NA,NA),NOW);
        assertTrue(history.ozoneCalibration.isEmpty());
        assertFalse(history.acceptRecovery(old,List.of(sample(NOW,"openaq:1",values(20,60))),NOW));
    }
    @Test public void ozoneRecoveryInitialFourteenDaysThenIncremental() {
        PollutantHistory history=new PollutantHistory(); add(history,NOW,20,60,NOW);
        var first=history.beginRecovery(NOW);
        assertEquals(NOW-336*H,first.ozoneFrom()); assertEquals(NOW,first.from());
        history.completeRecovery(first);
        assertEquals(NOW-2*H,history.beginRecovery(NOW+H).ozoneFrom());
    }
}
