package com.localairquality.app.data;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PollutantHistoryTest {
    private static final long NOW = 100 * PollutantHistory.WINDOW;
    private static final long HOUR = 3600000;
    private static double[] values(double pm25) { return new double[]{pm25, 20, 30, 40, 1000, Double.NaN}; }
    private static long[] times(long time) { long[] result = new long[6]; Arrays.fill(result, time); return result; }
    private void add(PollutantHistory h, String station, long report, long now) {
        h.add("SG", "Singapore", station, "Station " + station, "Home", times(report), values(10), now);
    }
    @Test public void repeatedRefreshesOfSameReportCreateOneEntry() {
        PollutantHistory h = new PollutantHistory();
        for (int i = 0; i < 20; i++) add(h, "a", NOW - HOUR, NOW + i * 1000);
        assertEquals(1, h.samples.size());
        assertEquals(NOW - HOUR, h.samples.get(0).measuredAt());
    }
    @Test public void correctedReportUpdatesValueWithoutDuplicating() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW - HOUR, NOW);
        h.add("SG", "Singapore", "a", "Station a", "Work", times(NOW - HOUR), values(48), NOW + 1);
        assertEquals(1, h.samples.size());
        assertEquals(48, h.samples.get(0).values()[0], 0);
        assertEquals(NOW, h.startedAt);
    }
    @Test public void newerReportWithUnchangedConcentrationIsRetained() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW - HOUR, NOW);
        add(h, "a", NOW, NOW + 1);
        assertEquals(2, h.samples.size());
    }
    @Test public void stationsWithSameReportTimeRemainSeparate() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW - HOUR, NOW);
        add(h, "b", NOW - HOUR, NOW + 1);
        assertEquals(2, h.samples.size());
        assertEquals(NOW, h.startedAt);
        assertEquals("Station a", h.samples.get(0).stationName());
        assertEquals("Station b", h.samples.get(1).stationName());
    }
    @Test public void countryChangeClearsAllAndReturningDoesNotRestore() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW - HOUR, NOW);
        h.add("MY", "Malaysia", "b", "Station b", "Away", times(NOW), values(20), NOW + 1);
        assertEquals(1, h.samples.size());
        assertEquals(NOW + 1, h.startedAt);
        add(h, "a", NOW, NOW + 2);
        assertEquals(1, h.samples.size());
        assertEquals("a", h.samples.get(0).stationId());
    }
    @Test public void expiryUsesReportedTimeAndIncludesExactBoundary() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW - PollutantHistory.WINDOW, NOW);
        add(h, "a", NOW - PollutantHistory.WINDOW + 1, NOW);
        assertEquals(2, h.samples.size());
        h.prune(NOW + 1);
        assertEquals(1, h.samples.size());
        h.prune(NOW + 2);
        assertTrue(h.samples.isEmpty());
        add(h, "a", NOW - PollutantHistory.WINDOW, NOW + 2);
        assertTrue(h.samples.isEmpty()); // Refresh cannot revive an expired report.
    }
    @Test public void usesIndependentPollutantTimesAndDoesNotReAddUnchangedPollutants() {
        PollutantHistory h = new PollutantHistory();
        long[] times = times(NOW - HOUR);
        times[0] = NOW;
        h.add("SG", "Singapore", "a", "A", "Home", times, values(48), NOW);
        assertEquals(2, h.samples.size());
        assertTrue(Double.isNaN(h.samples.get(0).values()[0]));
        assertEquals(20, h.samples.get(0).values()[1], 0);
        assertEquals(48, h.samples.get(1).values()[0], 0);
        assertTrue(Double.isNaN(h.samples.get(1).values()[1]));
        times[0] = NOW + HOUR;
        h.add("SG", "Singapore", "a", "A", "Home", times, values(50), NOW + HOUR);
        assertEquals(3, h.samples.size());
        long pm10Reports = h.samples.stream().filter(s -> Double.isFinite(s.values()[1])).count();
        assertEquals(1, pm10Reports);
    }
    @Test public void outOfOrderReportsAreSortedByReportTime() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW, NOW);
        add(h, "a", NOW - HOUR, NOW + 1);
        assertEquals(NOW - HOUR, h.samples.get(0).measuredAt());
        assertEquals(NOW, h.samples.get(1).measuredAt());
    }
    @Test public void rejectsMissingFutureAndExpiredReportTimes() {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", 0, NOW);
        add(h, "a", NOW + 1, NOW);
        add(h, "a", NOW - PollutantHistory.WINDOW - 1, NOW);
        assertTrue(h.samples.isEmpty());
    }
    @Test public void persistencePreservesReportsAndDeduplication() throws Exception {
        PollutantHistory h = new PollutantHistory();
        add(h, "a", NOW - HOUR, NOW);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        h.write(bytes);
        PollutantHistory restored = PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("SG", restored.countryId);
        assertArrayEquals(values(10), restored.samples.get(0).values(), 0);
        add(restored, "a", NOW - HOUR, NOW + 1);
        assertEquals(1, restored.samples.size());
    }
    @Test public void upgradingOldHistoryCollapsesRefreshDuplicatesAndKeepsLatestCorrection() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeUTF("SG"); out.writeUTF("Singapore"); out.writeUTF("A"); out.writeUTF("Home");
        out.writeLong(NOW); out.writeInt(3);
        for (int i = 0; i < 3; i++) {
            out.writeLong(NOW + i); out.writeLong(NOW - HOUR);
            out.writeUTF("a"); out.writeUTF("A");
            for (double value : values(46 + i)) out.writeDouble(value);
        }
        PollutantHistory h = PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(1, h.samples.size());
        assertEquals(NOW - HOUR, h.samples.get(0).measuredAt());
        assertEquals(48, h.samples.get(0).values()[0], 0);
        h.prune(NOW - HOUR + PollutantHistory.WINDOW + 1);
        assertTrue(h.samples.isEmpty());
    }
    @Test(expected = IOException.class) public void rejectsTruncatedFile() throws Exception {
        PollutantHistory.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 1}));
    }
}
