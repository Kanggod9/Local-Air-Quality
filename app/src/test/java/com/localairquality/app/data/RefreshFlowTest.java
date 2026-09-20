package com.localairquality.app.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshFlowTest {
    @Test public void lateGeocoderCannotRestartCompletedOrTimedOutRequest() {
        RefreshFlow flow = new RefreshFlow();
        int id = flow.start();
        assertTrue(flow.acceptLocation(id));
        assertTrue(flow.startDownload(id)); // geocoder timeout wins
        assertTrue(flow.finish(id));
        assertFalse(flow.startDownload(id)); // late geocoder result
        assertFalse(flow.finish(id));
    }
    @Test public void onlyFirstProviderIsAcceptedAndOldCallbacksCannotAffectRetry() {
        RefreshFlow flow = new RefreshFlow();
        int old = flow.start();
        assertTrue(flow.acceptLocation(old));
        assertFalse(flow.acceptLocation(old));
        int current = flow.start();
        assertFalse(flow.startDownload(old));
        assertTrue(flow.acceptLocation(current));
        assertTrue(flow.startDownload(current));
        flow.cancel();
        assertFalse(flow.finish(current));
    }
}
