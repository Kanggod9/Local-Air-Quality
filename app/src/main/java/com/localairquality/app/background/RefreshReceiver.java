package com.localairquality.app.background;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.localairquality.app.data.AirQualityReading;

public final class RefreshReceiver extends BroadcastReceiver {
    public static final String ACTION_REFRESH = "com.localairquality.app.action.REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_REFRESH.equals(intent.getAction())) return;
        PendingResult pendingResult = goAsync();
        RefreshCoordinator.refreshSavedLocation(context, new RefreshCoordinator.Callback() {
            @Override
            public void onSuccess(AirQualityReading reading) {
                pendingResult.finish();
            }

            @Override
            public void onError(Exception error) {
                pendingResult.finish();
            }
        });
    }
}

