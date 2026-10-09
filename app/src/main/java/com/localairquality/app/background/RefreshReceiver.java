package com.localairquality.app.background;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RefreshReceiver extends BroadcastReceiver {
    public static final String ACTION_REFRESH = "com.localairquality.app.action.REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_REFRESH.equals(intent.getAction())) return;
        // The download can outlive a broadcast, even one held with goAsync().
        RefreshScheduler.requestManualRefresh(context);
    }
}
