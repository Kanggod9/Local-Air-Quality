package com.localairquality.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.localairquality.app.MainActivity;
import com.localairquality.app.R;
import com.localairquality.app.background.RefreshReceiver;
import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AqiCalculator;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.ui.AlertSymbols;
import com.localairquality.app.ui.Palette;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen card matching the app: a tinted US AQI+ tile beside the level, main pollutant and European AQI. */
public final class AirQualityWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        AirQualityReading reading = ReadingStore.loadReading(context);
        for (int appWidgetId : appWidgetIds) {
            manager.updateAppWidget(appWidgetId, createViews(context, reading));
        }
    }

    public static void updateAll(Context context, AirQualityReading reading) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, AirQualityWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length == 0) return;
        RemoteViews views = createViews(context, reading);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    private static RemoteViews createViews(Context context, AirQualityReading reading) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.air_quality_widget);
        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(context, 20, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent updateIntent = new Intent(context, RefreshReceiver.class)
                .setAction(RefreshReceiver.ACTION_REFRESH)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent update = PendingIntent.getBroadcast(context, 21, updateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_update, update);

        String status = WidgetRefreshStatus.text(context);
        views.setTextViewText(R.id.widget_status, status);
        views.setViewVisibility(R.id.widget_status, status.isEmpty() ? View.GONE : View.VISIBLE);

        // Hosts reapply RemoteViews to an existing layout; every changeable property is set each time.
        boolean alert = reading != null && reading.alert.active();
        views.setViewVisibility(R.id.widget_alert_container, alert ? View.VISIBLE : View.GONE);
        views.setTextViewTextSize(R.id.widget_aqi, TypedValue.COMPLEX_UNIT_SP, alert ? 28 : 34);
        views.setTextViewTextSize(R.id.widget_level, TypedValue.COMPLEX_UNIT_SP, alert ? 15 : 17);

        if (reading == null) {
            int grey = AqiCalculator.euColor(0);
            views.setInt(R.id.aqi_panel, "setColorFilter", grey);
            views.setInt(R.id.widget_eu_dot, "setColorFilter", grey);
            views.setTextColor(R.id.widget_aqi, Palette.on(grey));
            views.setTextColor(R.id.widget_aqi_label, Palette.on(grey));
            views.setTextViewText(R.id.widget_aqi, context.getString(R.string.not_available));
            views.setTextViewText(R.id.widget_location, context.getString(R.string.app_name));
            views.setTextViewText(R.id.widget_station, context.getString(R.string.nearest_official_station));
            views.setTextViewText(R.id.widget_level, context.getString(R.string.waiting_for_data));
            views.setTextViewText(R.id.widget_pollutant, context.getString(R.string.widget_tap_to_open));
            views.setTextViewText(R.id.widget_eu, context.getString(R.string.european_aqi_empty));
            views.setTextViewText(R.id.widget_time, context.getString(R.string.empty_time));
            views.setContentDescription(R.id.widget_root, context.getString(R.string.app_name));
            return views;
        }

        if (alert) {
            views.setInt(R.id.widget_alert_background, "setColorFilter", reading.alert.level().color);
            views.setTextViewCompoundDrawablesRelative(R.id.widget_alert,
                    AlertSymbols.icon(reading.alert.level()), 0, 0, 0);
            views.setTextViewText(R.id.widget_alert, reading.alert.message());
        }

        int color = AqiCalculator.usColor(reading.usAqi);
        int onColor = Palette.on(color);
        views.setInt(R.id.aqi_panel, "setColorFilter", color);
        views.setTextColor(R.id.widget_aqi, onColor);
        views.setTextColor(R.id.widget_aqi_label, onColor);
        views.setTextViewText(R.id.widget_aqi, Integer.toString(reading.usAqi));
        views.setTextViewText(R.id.widget_location, reading.locationName);
        views.setTextViewText(R.id.widget_station,
                (reading.isBackup() ? "Backup · " : "") + reading.stationName
                        + String.format(Locale.getDefault(), " · %.1f km", reading.distanceKm));
        views.setTextViewText(R.id.widget_level, reading.usLevel);
        views.setTextViewText(R.id.widget_pollutant, "Main · " + reading.mainPollutant + "  "
                + reading.pollutantValue(reading.mainPollutant));
        views.setInt(R.id.widget_eu_dot, "setColorFilter", AqiCalculator.euColor(reading.euBand));
        views.setTextViewText(R.id.widget_eu, reading.euBand > 0
                ? "EU AQI " + reading.euBand + " · " + reading.euLevel
                : context.getString(R.string.european_aqi_empty));
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(reading.measuredAtMillis));
        views.setTextViewText(R.id.widget_time, context.getString(R.string.widget_reported, time));
        views.setContentDescription(R.id.widget_root, reading.locationName + ", US AQI " + reading.usAqi + ", "
                + reading.usLevel + ", European AQI " + reading.euBand + ", " + reading.euLevel
                + (alert ? ". " + reading.alert.message() : ""));
        return views;
    }
}
