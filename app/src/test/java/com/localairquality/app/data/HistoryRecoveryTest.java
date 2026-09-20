package com.localairquality.app.data;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class HistoryRecoveryTest {
    private static final long HOUR = 3600000, NOW = 100 * PollutantHistory.WINDOW;
    private PollutantHistory initial() {
        PollutantHistory h = new PollutantHistory();
        add(h, "SG", "a", NOW);
        return h;
    }
    private void add(PollutantHistory h, String country, String station, long now) {
        long[] times = new long[6]; Arrays.fill(times, now);
        h.add(country, country, station, station, "Home", times, new double[]{48,70,45,23,1000,9}, now);
    }
    @Test public void recoveryStartsAtKnownStationHourAndThrottlesRepeatedRefreshes() {
        PollutantHistory h = initial();
        PollutantHistory.Recovery r = h.beginRecovery(NOW + HOUR);
        assertEquals(NOW, r.from());
        h.completeRecovery(r);
        assertNull(h.beginRecovery(NOW + HOUR + 1));
        assertNotNull(h.beginRecovery(NOW + HOUR + 15*60000));
    }
    @Test public void importsMissedHoursButNotEarlierReportsOrAnotherStation() {
        PollutantHistory h = initial();
        PollutantHistory.Recovery r = h.beginRecovery(NOW + 3*HOUR);
        double[] values = {50,70,45,23,1000,9};
        assertTrue(h.acceptRecovery(r, List.of(
                new PollutantHistory.Sample(NOW-HOUR,"a","a",values),
                new PollutantHistory.Sample(NOW+HOUR,"a","a",values),
                new PollutantHistory.Sample(NOW+2*HOUR,"a","a",values),
                new PollutantHistory.Sample(NOW+HOUR,"b","b",values),
                new PollutantHistory.Sample(NOW+4*HOUR,"a","a",values)), NOW+3*HOUR));
        assertEquals(3,h.samples.size());
        h.acceptRecovery(r,h.samples.stream().toList(),NOW+3*HOUR);
        assertEquals(3,h.samples.size());
    }
    @Test public void stationChangeKeepsDataButRejectsOldRecovery() {
        PollutantHistory h = initial();
        PollutantHistory.Recovery old = h.beginRecovery(NOW);
        add(h,"SG","b",NOW+HOUR);
        assertFalse(h.acceptRecovery(old,List.of(),NOW+HOUR));
        assertEquals(2,h.samples.size());
        assertEquals(NOW+HOUR,h.beginRecovery(NOW+2*HOUR).from());
    }
    @Test public void returningToCountryCannotAcceptOldSessionResponse() {
        PollutantHistory h = initial();
        PollutantHistory.Recovery old = h.beginRecovery(NOW);
        add(h,"MY","b",NOW+HOUR);
        add(h,"SG","a",NOW+2*HOUR);
        assertFalse(h.acceptRecovery(old,List.of(),NOW+2*HOUR));
        assertEquals(1,h.samples.size());
    }
    @Test public void recoveryStateSurvivesRestartAndWindowIsBounded() throws Exception {
        PollutantHistory h = initial();
        h.completeRecovery(h.beginRecovery(NOW+HOUR));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); h.write(bytes);
        PollutantHistory restored = PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertNull(restored.beginRecovery(NOW+HOUR+1));
        assertEquals(NOW+6*HOUR,restored.beginRecovery(NOW+30*HOUR).from());
    }
    @Test public void interruptedAttemptCanRetryImmediatelyAfterProcessRestart() throws Exception {
        PollutantHistory h = initial();
        var interrupted = h.beginRecovery(NOW + 6*HOUR);
        h.acceptRecovery(interrupted, List.of(new PollutantHistory.Sample(NOW + HOUR,
                "a", "a", new double[]{50,70,45,23,1000,9})), interrupted.to());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); h.write(bytes);
        PollutantHistory restored = PollutantHistory.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(restored.needsRecovery(NOW + 6*HOUR + 1));
        assertEquals(NOW, restored.beginRecovery(NOW + 6*HOUR + 1).from());
        assertEquals(2, restored.samples.size());
    }
    @Test public void failedOldSessionCannotMarkNewCountryRecovered() {
        PollutantHistory h = initial();
        var old = h.beginRecovery(NOW);
        add(h,"MY","b",NOW+HOUR);
        assertFalse(h.completeRecovery(old));
        assertTrue(h.needsRecovery(NOW+HOUR));
    }
    @Test public void upgradeDiscardsOldAttemptThrottleButKeepsHistory() throws Exception {
        PollutantHistory h = initial();
        h.lastRecoveryCompletedAt = NOW;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); h.write(bytes);
        byte[] encoded = bytes.toByteArray();
        encoded[3] = 3; // v3 uses the same layout but this timestamp means an ATTEMPT.
        PollutantHistory restored = PollutantHistory.read(new ByteArrayInputStream(encoded));
        assertTrue(restored.needsRecovery(NOW+1));
        assertEquals(1,restored.samples.size());
        assertEquals(NOW,restored.activeStationSince);
    }
}
