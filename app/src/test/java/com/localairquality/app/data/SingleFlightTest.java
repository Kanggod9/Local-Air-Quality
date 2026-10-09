package com.localairquality.app.data;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SingleFlightTest {
    @Test public void foregroundAndJobShareOneDownloadAndCompletionNotification() {
        List<Runnable> queued = new ArrayList<>();
        SingleFlight<Boolean> flight = new SingleFlight<>(queued::add);
        var foreground = flight.run(() -> true);
        var job = flight.run(() -> { throw new AssertionError("Duplicate download"); });
        assertSame(foreground, job); assertEquals(1, queued.size());
        List<String> finished = new ArrayList<>();
        foreground.thenRun(() -> finished.add("chart")); job.thenRun(() -> finished.add("job"));
        assertTrue(finished.isEmpty());
        queued.remove(0).run();
        assertTrue(foreground.join()); assertEquals(2, finished.size());
    }
    @Test public void completedAndFailedRequestsDoNotBlockLaterCatchUp() {
        SingleFlight<Boolean> flight = new SingleFlight<>(Runnable::run);
        var failed = flight.run(() -> { throw new IllegalStateException("Offline"); });
        assertTrue(failed.isCompletedExceptionally());
        var retry = flight.run(() -> true);
        assertNotSame(failed,retry); assertTrue(retry.join());
        assertNotSame(retry,flight.run(() -> true));
    }
}
