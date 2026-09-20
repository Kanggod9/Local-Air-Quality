package com.localairquality.app.background;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.notification.AirQualityNotification;
import com.localairquality.app.widget.AirQualityWidgetProvider;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        RefreshScheduler.schedule(context);
        AirQualityReading cached = ReadingStore.loadReading(context);
        if (cached != null) {
            AirQualityNotification.show(context, cached);
            AirQualityWidgetProvider.updateAll(context, cached);
        }
    }
}
