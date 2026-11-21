package com.localairquality.app.data;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class BackupChartTest {
    private static final long H = NowCast.HOUR, NOW = 2000 * H;
    private static final String PRIMARY = "openaq:10";
    private static double[] values(double pm) { return new double[]{pm,25,60,20,1000,5}; }
    private static PollutantHistory history() {
        var history = new PollutantHistory();
        history.add("TEST","Test",PRIMARY,"Official monitor","Here",new long[6],values(12),NOW);
        return history;
    }
    private static void addBackups(PollutantHistory history) {
        for (int i = 7; i >= 0; i--) history.addBackup(PRIMARY,
                new PollutantHistory.Sample(NOW-i*H,"openaq:20","Backup monitor",values(70),63),NOW);
        history.updateNowcasts(NOW);
    }
    private static List<HistorySeries.Point> backupPoints(PollutantHistory history,HistorySeries.Metric metric) {
        return HistorySeries.chartPoints(history,metric,new HistorySeries.ChartSource("openaq:20","Backup monitor",true));
    }
    private static void recover(PollutantHistory history, int hours) {
        var request = history.beginRecovery(NOW);
        for (int i = hours-1; i >= 0; i--) history.acceptRecovery(request,
                List.of(new PollutantHistory.Sample(NOW-i*H,PRIMARY,"Official monitor",values(12),63)),NOW);
        history.updateNowcasts(NOW);
    }
    @Test public void everyIndexAndPollutantChartMarksOnlyProvisionalSourceReports() {
        var history = history(); addBackups(history);
        for (var metric : HistorySeries.Metric.values()) {
            var points = backupPoints(history,metric);
            assertFalse(metric.label,points.isEmpty());
            assertTrue(metric.label,points.stream().allMatch(HistorySeries.Point::backup));
        }
        assertTrue(history.samples.isEmpty());
        assertTrue(history.backups.stream().noneMatch(b -> b.reading().backup()));
    }
    @Test public void officialOpenAqStationAndMisleadingNameDoNotBecomeBackup() {
        var history = history();
        history.stationName = "Official (OpenAQ, backup)";
        var request = history.beginRecovery(NOW);
        for (int i = 7; i >= 0; i--) {
            // /hours recovery, not unverified /latest, supplies valid NowCast inputs.
            history.acceptRecovery(request,List.of(new PollutantHistory.Sample(
                    NOW-i*H,PRIMARY,history.stationName,values(12),63)),NOW);
        }
        history.updateNowcasts(NOW);
        for (var metric : HistorySeries.Metric.values()) {
            var points = HistorySeries.points(history,metric);
            assertFalse(metric.label,points.isEmpty());
            assertTrue(metric.label,points.stream().noneMatch(HistorySeries.Point::backup));
        }
    }
    @Test public void officialChartContainsOnlyRecoveredReportsWithoutAnyBackupRings() {
        var history = history(); addBackups(history); recover(history,2);
        for (var metric : HistorySeries.Metric.values()) {
            var points = HistorySeries.points(history,metric);
            assertFalse(metric.label,points.isEmpty());
            assertEquals(metric.label,2,points.size());
            for (var point : points) assertFalse(metric.label,point.backup());
            assertFalse(metric.label,points.get(points.size()-1).backup());
            assertTrue(metric.label,backupPoints(history,metric).stream().allMatch(HistorySeries.Point::backup));
        }
        assertEquals(8,history.backups.size()); // Hidden originals do not leave a visual ring.
        recover(history,8);
        for (var metric : HistorySeries.Metric.values())
            assertTrue(metric.label,HistorySeries.points(history,metric).stream().noneMatch(HistorySeries.Point::backup));
    }
    @Test public void sourceMarkersSurviveRestartAndStayHiddenAfterStationChange() throws Exception {
        var history = history(); addBackups(history); recover(history,2);
        var bytes = new ByteArrayOutputStream(); history.write(bytes);
        var restored = PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
        for (var metric : HistorySeries.Metric.values()) {
            var before = backupPoints(history,metric);
            var after = backupPoints(restored,metric);
            assertEquals(metric.label,before,after);
        }
        restored.add("TEST","Test","openaq:30","New station","Here",new long[6],values(12),NOW);
        for (var metric : HistorySeries.Metric.values()) {
            assertTrue(HistorySeries.points(restored,metric).isEmpty());
            assertTrue(backupPoints(restored,metric).isEmpty());
        }
        restored.add("TEST","Test",PRIMARY,"Official monitor","Here",new long[6],values(12),NOW);
        assertEquals(8,backupPoints(restored,HistorySeries.Metric.US_AQI).stream().filter(HistorySeries.Point::backup).count());
    }
}
