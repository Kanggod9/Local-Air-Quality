package com.localairquality.app.widget;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;

import com.localairquality.app.R;
import com.localairquality.app.background.RefreshScheduler;
import com.localairquality.app.data.ReadingStore;

import java.text.DateFormat;
import java.util.Date;

/** Separate check time from the station's measurement time. Survives process recreation. */
public final class WidgetRefreshStatus {
    public static final String QUEUED = "queued";
    public static final String UPDATING = "updating";
    public static final String CHECKED = "checked";
    public static final String FAILED = "failed";
    public static final String OPEN_APP = "open_app";
    private static final String PREFS = "widget_refresh";

    private WidgetRefreshStatus() {}

    public static void set(Context context, String status) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("status", status).putLong("time", System.currentTimeMillis()).apply();
        try {
            AirQualityWidgetProvider.updateAll(context, ReadingStore.loadReading(context));
        } catch (RuntimeException error) {
            // A launcher/presentation failure must not strand the download job.
            android.util.Log.w("LocalAirQuality", "Widget status update failed", error);
        }
    }

    public static String text(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String status = prefs.getString("status", "");
        if (QUEUED.equals(status) || UPDATING.equals(status)) {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            // Do not leave a restored/rebooted widget stuck at Updating indefinitely.
            if (scheduler == null || scheduler.getPendingJob(RefreshScheduler.MANUAL_JOB_ID) == null) {
                return context.getString(R.string.widget_update_failed);
            }
            return context.getString(UPDATING.equals(status)
                    ? R.string.widget_updating : R.string.widget_update_queued);
        }
        if (CHECKED.equals(status)) {
            String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(new Date(prefs.getLong("time", 0)));
            return context.getString(R.string.widget_checked, time);
        }
        if (FAILED.equals(status)) return context.getString(R.string.widget_update_failed);
        if (OPEN_APP.equals(status)) return context.getString(R.string.widget_open_app);
        return "";
    }
}
