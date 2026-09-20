package com.localairquality.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.localairquality.app.MainActivity;
import com.localairquality.app.R;
import com.localairquality.app.background.RefreshReceiver;
import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AqiCalculator;
import com.localairquality.app.data.ReadingStore;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

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
        for (int id : ids) manager.updateAppWidget(id, createViews(context, reading));
    }

    private static RemoteViews createViews(Context context, AirQualityReading reading) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.air_quality_widget);
        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(context, 20, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent updateIntent = new Intent(context, RefreshReceiver.class)
                .setAction(RefreshReceiver.ACTION_REFRESH);
        PendingIntent update = PendingIntent.getBroadcast(context, 21, updateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_update, update);

        if (reading == null) return views;
        int color = AqiCalculator.usColor(reading.usAqi);
        int textColor = reading.usAqi >= 151 ? 0xFFFFFFFF : 0xFF17202A;
        views.setInt(R.id.aqi_panel, "setBackgroundColor", color);
        views.setTextColor(R.id.widget_aqi, textColor);
        views.setTextColor(R.id.widget_pollutant, textColor);
        views.setTextViewText(R.id.widget_aqi, Integer.toString(reading.usAqi));
        views.setTextViewText(R.id.widget_location, reading.locationName);
        views.setTextViewText(R.id.widget_station,
                reading.stationName + String.format(Locale.getDefault(), "  %.1f km", reading.distanceKm));
        views.setTextViewText(R.id.widget_level, reading.usLevel);
        views.setTextViewText(R.id.widget_pollutant,
                reading.mainPollutant + "\n" + reading.pollutantValue(reading.mainPollutant));
        views.setTextViewText(R.id.widget_eu,
                "European AQI " + reading.euBand + "  " + reading.euLevel);
        views.setTextViewText(R.id.widget_time,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(reading.measuredAtMillis)));
        return views;
    }
}

