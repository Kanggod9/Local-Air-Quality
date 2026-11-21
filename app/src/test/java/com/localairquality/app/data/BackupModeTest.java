package com.localairquality.app.data;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class BackupModeTest {
    private static final long H=NowCast.HOUR,NOW=2000*H;
    private static double[] values(double pm) { return new double[]{pm,1,1,1,1,1}; }
    private static PollutantHistory history() {
        var history=new PollutantHistory();
        history.add("SG","Singapore","nea:west","Official station","Here",new long[6],values(1),NOW);
        history.activeHistorySince=NOW-24*H;
        return history;
    }
    private static AirQualityReading reading(String source) {
        var primary=new AirQualityReading(); primary.stationId="nea:west"; primary.stationName="Official station";
        primary.countryCode="SG"; primary.countryName="Singapore"; primary.pm25=1;
        var current=new AirQualityReading(); current.stationId=source; current.stationName="Backup monitor";
        current.primary=primary; current.pm25=160; current.measuredAtMillis=NOW;
        return current;
    }
    private static void official(PollutantHistory history,long time,double pm) {
        history.acceptRecovery(history.beginRecovery(NOW),List.of(new PollutantHistory.Sample(
                time,"nea:west","Official station",values(pm))),NOW);
    }
    private static void backup(PollutantHistory history,long time,String station,double pm) {
        history.addBackup("nea:west",new PollutantHistory.Sample(time,station,"Backup monitor",values(pm),63),NOW);
    }
    @Test public void enteringBackupStartsSeparateChartsWithoutRemovingRetainedOfficialReports() {
        var history=history(); official(history,NOW-10*H,12);
        for(int i=7;i>=0;i--) backup(history,NOW-i*H,"openaq:20",160);
        history.updateNowcasts(NOW);
        var source=HistorySeries.chartSource(history,reading("openaq:20"));
        assertTrue(source.backup()); assertEquals("openaq:20",source.stationId());
        assertTrue(source.stationName().endsWith("(OpenAQ, backup)"));
        for(var metric:HistorySeries.Metric.values()) {
            var points=HistorySeries.chartPoints(history,metric,source);
            assertFalse(metric.label,points.isEmpty());
            assertTrue(metric.label,points.stream().allMatch(p -> p.backup() && p.measuredAt()>=NOW-7*H));
        }
        assertEquals(1,history.samples.size());
        assertEquals(1,HistorySeries.points(history,HistorySeries.Metric.US_AQI).size());
        assertEquals(AirQualityAlert.Level.PURPLE,AirQualityAlert.calculate(history,reading("openaq:20"),NOW).level());
    }
    @Test public void latestOfficialRecoverySwitchesToOfficialChartAndIgnoresAllBackupAlertHours() {
        var history=history(); official(history,NOW-10*H,12);
        for(int i=7;i>=0;i--) backup(history,NOW-i*H,"openaq:20",300);
        var current=reading("openaq:20");
        assertEquals(AirQualityAlert.Level.BLACK,AirQualityAlert.calculate(history,current,NOW).level());
        official(history,NOW,1);
        current=BackupReconciliation.current(current,history);
        assertFalse(current.isBackup());
        var source=HistorySeries.chartSource(history,current);
        assertEquals("nea:west",source.stationId()); assertFalse(source.backup());
        var points=HistorySeries.chartPoints(history,HistorySeries.Metric.US_AQI,source);
        assertEquals(2,points.size()); assertTrue(points.stream().noneMatch(HistorySeries.Point::backup));
        assertFalse(AirQualityAlert.calculate(history,current,NOW).active());
        assertEquals(8,history.backups.size());
        for(int i=1;i<8;i++) official(history,NOW-i*H,100);
        official(history,NOW,100);
        assertEquals(9,HistorySeries.chartPoints(history,HistorySeries.Metric.US_AQI,source).size());
        assertEquals(AirQualityAlert.Level.DEEP_RED,AirQualityAlert.calculate(history,current,NOW).level());
    }
    @Test public void recoveredOlderOfficialHoursDoNotSwitchAwayFromLatestBackup() {
        var history=history(); backup(history,NOW,"openaq:20",160); official(history,NOW-4*H,1);
        var current=BackupReconciliation.current(reading("openaq:20"),history);
        assertTrue(current.isBackup());
        var points=HistorySeries.chartPoints(history,HistorySeries.Metric.US_AQI,HistorySeries.chartSource(history,current));
        assertEquals(1,points.size()); assertTrue(points.get(0).backup());
        assertEquals(NOW,points.get(0).measuredAt());
    }
    @Test public void officialAndBackupHoursNeverAddUpToTheEightHourAlertRequirement() {
        var history=history();
        for(int i=4;i<8;i++) official(history,NOW-i*H,100);
        for(int i=0;i<4;i++) backup(history,NOW-i*H,"openaq:20",100);
        assertFalse(AirQualityAlert.calculate(history,reading("openaq:20"),NOW).active());
        assertFalse(AirQualityAlert.calculate(history,"nea:west",NOW).active());
        for(int i=4;i<8;i++) backup(history,NOW-i*H,"openaq:20",100);
        var alert=AirQualityAlert.calculate(history,reading("openaq:20"),NOW);
        assertEquals(AirQualityAlert.Level.DEEP_RED,alert.level());
        assertEquals(8,alert.usHours()); assertEquals(8,alert.europeanHours());
        assertFalse(AirQualityAlert.calculate(history,"nea:west",NOW).active());
    }
    @Test public void changingBackupSourceNeverCombinesStationsChartsOrAlerts() {
        var history=history();
        for(int i=0;i<8;i++) backup(history,NOW-i*H,"openaq:20",160);
        for(int i=0;i<2;i++) backup(history,NOW-i*H,"openaq:21",100);
        var first=HistorySeries.chartSource(history,reading("openaq:20"));
        var second=HistorySeries.chartSource(history,reading("openaq:21"));
        assertEquals(8,HistorySeries.chartPoints(history,HistorySeries.Metric.US_AQI,first).size());
        assertEquals(2,HistorySeries.chartPoints(history,HistorySeries.Metric.US_AQI,second).size());
        assertEquals(AirQualityAlert.Level.PURPLE,AirQualityAlert.calculate(history,reading("openaq:20"),NOW).level());
        assertFalse(AirQualityAlert.calculate(history,reading("openaq:21"),NOW).active());
    }
    @Test public void expiredOfficialRecordsAreNotRestoredAndNeitherChartExceedsTwentyFourHours() {
        var history=history(); official(history,NOW-23*H,160); backup(history,NOW-23*H,"openaq:20",300);
        official(history,NOW,1); backup(history,NOW,"openaq:20",12);
        history.prune(NOW+2*H);
        assertEquals(1,HistorySeries.points(history,HistorySeries.Metric.US_AQI).size());
        assertEquals(1,HistorySeries.chartPoints(history,HistorySeries.Metric.US_AQI,
                HistorySeries.chartSource(history,reading("openaq:20"))).size());
        assertEquals(NOW-24*H,history.beginRecovery(NOW).from());
    }
}
