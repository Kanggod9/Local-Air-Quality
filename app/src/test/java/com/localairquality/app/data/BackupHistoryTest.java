package com.localairquality.app.data;

import org.junit.Test;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;

public class BackupHistoryTest {
    private static final long H=NowCast.HOUR,NOW=2000*H;
    private static double[] values(double pm) { return new double[]{pm,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN}; }
    private static PollutantHistory history() {
        PollutantHistory h=new PollutantHistory(); h.add("SG","Singapore","nea:west","West","Here",new long[6],values(Double.NaN),NOW); return h;
    }
    private static void backup(PollutantHistory h,long time,double pm) {
        h.addBackup("nea:west",new PollutantHistory.Sample(time,"openaq:1","Backup station",values(pm),1),NOW);
    }
    private static void official(PollutantHistory h,long time,double pm) {
        h.acceptRecovery(h.beginRecovery(NOW),List.of(new PollutantHistory.Sample(time,"nea:west","West",values(pm))),NOW);
    }
    private static AirQualityReading backupReading(String source) {
        var reading=new AirQualityReading(); reading.stationId=source; reading.stationName="Backup station";
        reading.primary=new AirQualityReading(); reading.primary.stationId="nea:west"; return reading;
    }
    private static List<HistorySeries.Point> backupPoints(PollutantHistory h,HistorySeries.Metric metric) {
        return backupPoints(h,metric,"openaq:1");
    }
    private static List<HistorySeries.Point> backupPoints(PollutantHistory h,HistorySeries.Metric metric,String source) {
        return HistorySeries.chartPoints(h,metric,HistorySeries.chartSource(h,backupReading(source)));
    }
    @Test public void backupAppearsInSeparateChartAndAlertWithSourceLabel() {
        PollutantHistory h=history(); h.activeHistorySince=NOW-8*H;
        for(int i=0;i<8;i++) backup(h,NOW-i*H,300);
        var points=backupPoints(h,HistorySeries.Metric.US_AQI);
        assertEquals(8,points.size()); assertTrue(points.get(0).stationName().contains("(OpenAQ, backup)"));
        assertTrue(HistorySeries.points(h,HistorySeries.Metric.US_AQI).isEmpty());
        assertEquals(AirQualityAlert.Level.BLACK,AirQualityAlert.calculate(h,backupReading("openaq:1"),NOW).level());
    }
    @Test public void officialReplacementChangesBothIndicesAndRemovesObsoleteAlert() {
        PollutantHistory h=history(); h.activeHistorySince=NOW-8*H;
        for(int i=0;i<8;i++) backup(h,NOW-i*H,300);
        assertTrue(AirQualityAlert.calculate(h,backupReading("openaq:1"),NOW).active());
        for(int i=0;i<8;i++) official(h,NOW-i*H,1);
        for(var metric:new HistorySeries.Metric[]{HistorySeries.Metric.US_AQI,HistorySeries.Metric.EUROPEAN_AQI}) {
            var points=HistorySeries.points(h,metric); assertEquals(8,points.size());
            assertTrue(points.stream().noneMatch(p -> p.stationName().contains("backup")));
        }
        assertFalse(AirQualityAlert.calculate(h,"nea:west",NOW).active());
    }
    @Test public void replacementMatchesClockHourAndDoesNotBlendMissingPollutants() {
        PollutantHistory h=history(); h.activeHistorySince=NOW-H;
        h.addBackup("nea:west",new PollutantHistory.Sample(NOW+10*60_000,"openaq:1","Backup",
                new double[]{300,400,500,600,700,800},63),NOW+20*60_000);
        official(h,NOW,1);
        var point=HistorySeries.points(h,HistorySeries.Metric.EUROPEAN_AQI).get(0);
        assertEquals(1,point.availablePollutants()); assertEquals(1,point.value(),0);
        assertEquals("West",point.stationName());
    }
    @Test public void unrecoveredHoursDoNotFillOfficialChartAndBackupsStaySeparate() {
        PollutantHistory h=history(); h.activeHistorySince=NOW-8*H;
        for(int i=0;i<8;i++) { backup(h,NOW-i*H,160); backup(h,NOW-i*H,160); }
        official(h,NOW,1);
        var points=HistorySeries.points(h,HistorySeries.Metric.US_AQI);
        assertEquals(1,points.size()); assertFalse(points.get(0).backup());
        assertEquals(8,backupPoints(h,HistorySeries.Metric.US_AQI).size());
        assertFalse(AirQualityAlert.calculate(h,"nea:west",NOW).active());
        assertTrue(AirQualityAlert.calculate(h,backupReading("openaq:1"),NOW).active());
    }
    @Test public void newestBackupSourceForHourWinsWithoutCombiningStations() {
        PollutantHistory h=history(); backup(h,NOW,300);
        h.addBackup("nea:west",new PollutantHistory.Sample(NOW+1,"openaq:2","Second",values(12),1),NOW+1);
        var points=backupPoints(h,HistorySeries.Metric.US_AQI,"openaq:2");
        assertEquals(1,points.size()); assertTrue(points.get(0).stationName().contains("Second"));
        assertEquals(12,points.get(0).mainConcentration(),0);
    }
    @Test public void backupsSurviveRestartWithProvenanceAndExpireAtTwentyFourHours() throws Exception {
        PollutantHistory h=history(); backup(h,NOW,300); h.updateNowcasts(NOW);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); h.write(bytes);
        var restored=PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("openaq:1",restored.backups.get(0).reading().stationId());
        assertEquals(1,backupPoints(restored,HistorySeries.Metric.US_AQI).size());
        restored.prune(NOW+24*H); assertEquals(1,restored.backups.size());
        restored.prune(NOW+24*H+1); assertTrue(restored.backups.isEmpty());
    }
    @Test public void switchingStationHidesBackupAndRejectsSupersededRecovery() {
        PollutantHistory h=history(); backup(h,NOW,300); var old=h.beginRecovery(NOW);
        h.add("SG","Singapore","nea:south","South","Here",new long[6],values(Double.NaN),NOW+H);
        assertTrue(HistorySeries.points(h,HistorySeries.Metric.US_AQI).isEmpty());
        assertFalse(h.acceptBackupRecovery(old,"openaq:1",List.of(new PollutantHistory.Sample(NOW,"openaq:1","A",values(300))),NOW+H));
    }
    @Test public void nowcastDoesNotMixOfficialAndBackupParticleWindows() {
        PollutantHistory h=history(); h.activeHistorySince=NOW-2*H;
        backup(h,NOW,70); official(h,NOW-H,70); h.updateNowcasts(NOW);
        assertEquals(-1,h.latestNowcast("openaq:1").aqi());
        backup(h,NOW-H,20); h.updateNowcasts(NOW);
        assertTrue(h.latestNowcast("openaq:1").aqi()>0);
        assertEquals((70+20*.5)/1.5,h.latestNowcast("openaq:1").concentration(),1e-8);
    }
    @Test public void futureReportsAndWrongSourcesCannotBecomeBackup() {
        PollutantHistory h=history(); backup(h,NOW+H,300);
        h.addBackup("nea:west",new PollutantHistory.Sample(NOW,"nea:east","E",values(300)),NOW);
        assertTrue(h.backups.isEmpty());
    }
    @Test public void backgroundOfficialCatchupReconcilesCurrentCachedBackup() {
        PollutantHistory h=history(); official(h,NOW,1);
        AirQualityReading r=new AirQualityReading(); r.stationId="openaq:1"; r.measuredAtMillis=NOW;
        r.pm25=300; r.primary=new AirQualityReading(); r.primary.stationId="nea:west"; r.primary.stationName="West";
        var replacement=BackupReconciliation.current(r,h);
        assertFalse(replacement.isBackup()); assertEquals("nea:west",replacement.stationId);
        assertEquals(1,replacement.pm25,0); assertEquals("Good",replacement.usLevel);
    }
    @Test public void v8HistoryMigrationKeepsRecords() throws Exception {
        PollutantHistory h=history(); h.activeHistorySince=NOW-H; official(h,NOW,12);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); h.write(bytes);
        byte[] v8=Arrays.copyOf(bytes.toByteArray(),bytes.size()-4); v8[3]=8;
        var restored=PollutantHistory.read(new ByteArrayInputStream(v8));
        assertEquals(1,restored.samples.size()); assertTrue(restored.backups.isEmpty());
        assertEquals(NOW-H,restored.activeHistorySince);
    }
    @Test public void replacementCanDowngradePurpleToDeepRedWithBothIndices() {
        PollutantHistory h=history();
        for(int i=0;i<8;i++) backup(h,NOW-i*H,160);
        assertEquals(AirQualityAlert.Level.PURPLE,AirQualityAlert.calculate(h,backupReading("openaq:1"),NOW).level());
        for(int i=0;i<8;i++) official(h,NOW-i*H,100);
        var alert=AirQualityAlert.calculate(h,"nea:west",NOW);
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert.level()); assertTrue(alert.us()); assertTrue(alert.european());
    }
    @Test public void backupOzoneCanRecoverTwoWeeksWithoutExtendingCharts() {
        PollutantHistory h=history(); backup(h,NOW,12);
        var request=h.beginRecovery(NOW);
        var acceptance=new PollutantHistory.Recovery(request.countryId(),request.sessionStart(),request.stationId(),
                request.stationSince(),request.from(),request.to(),NOW-PollutantHistory.OZONE_WINDOW);
        assertTrue(h.acceptBackupRecovery(acceptance,"openaq:1",List.of(new PollutantHistory.Sample(NOW-300*H,
                "openaq:1","Backup",new double[]{12,Double.NaN,70,Double.NaN,Double.NaN,Double.NaN},63)),NOW));
        assertEquals(1,h.ozoneCalibration.size()); assertEquals(1,h.backups.size());
        h.prune(NOW+37*H); assertTrue(h.ozoneCalibration.isEmpty()); assertTrue(h.backups.isEmpty());
    }
    @Test public void tiedReportTimesUseNowcastFromTheSelectedBackupSource() {
        PollutantHistory h=history(); backup(h,NOW-H,300); backup(h,NOW,300);
        h.addBackup("nea:west",new PollutantHistory.Sample(NOW-H,"openaq:2","Second",values(20),1),NOW);
        h.addBackup("nea:west",new PollutantHistory.Sample(NOW,"openaq:2","Second",values(12),1),NOW);
        h.updateNowcasts(NOW);
        var points=backupPoints(h,HistorySeries.Metric.US_NOWCAST,"openaq:2");
        var newest=points.get(points.size()-1);
        assertTrue(newest.stationName().contains("Second"));
        // This source's min/max is12/20, so its adaptive weight is0.6.
        assertEquals((12+20*.6)/1.6,newest.mainConcentration(),1e-8);
    }
    @Test public void returningToBackupOnlyStationRecoversItsRetainedOutageHours() {
        PollutantHistory h=history(); backup(h,NOW-H,300);
        h.add("SG","Singapore","nea:south","South","Here",new long[6],values(Double.NaN),NOW);
        h.add("SG","Singapore","nea:west","West","Here",new long[6],values(Double.NaN),NOW+H);
        assertEquals(NOW-H,h.beginRecovery(NOW+H).from());
        assertEquals(1,backupPoints(h,HistorySeries.Metric.US_AQI).size());
    }
}
