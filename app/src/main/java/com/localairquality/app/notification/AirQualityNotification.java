package com.localairquality.app.notification;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import com.localairquality.app.MainActivity;
import com.localairquality.app.R;
import com.localairquality.app.background.RefreshReceiver;
import com.localairquality.app.data.AirQualityAlert;
import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AqiCalculator;
import com.localairquality.app.ui.AlertSymbols;
import com.localairquality.app.ui.Palette;

import java.text.DateFormat;
import java.util.Date;

/**
 * Ongoing status in the system's decorated template: a US AQI+ chip in the category colour beside
 * the level, with the alert banner and station details when expanded. Plain title and text are kept
 * for the lock screen, accessibility services and devices that do not show custom content.
 */
public final class AirQualityNotification {
    private static final String CHANNEL_ID = "local_air_quality_status";
    private static final int NOTIFICATION_ID = 7101;

    private AirQualityNotification() {}

    public static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    public static void createChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    public static void show(Context context, AirQualityReading reading) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        createChannel(context);
        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent refreshIntent = new Intent(context, RefreshReceiver.class)
                .setAction(RefreshReceiver.ACTION_REFRESH)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent refresh = PendingIntent.getBroadcast(context, 11, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AirQualityAlert.Result alert = reading.alert;
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(reading.measuredAtMillis));
        String title = reading.usLevel;
        String european = reading.euBand > 0 ? "European AQI " + reading.euBand + " · " + reading.euLevel
                : "European AQI —";
        String summary = alert.active() ? alert.level().title + " · " + alert.timeLabel() : european + " · " + time;
        String main = reading.mainPollutant + " " + reading.pollutantValue(reading.mainPollutant);
        StringBuilder detail = new StringBuilder("US AQI+ ").append(reading.usAqi).append(" · Main pollutant ").append(main)
                .append('\n').append(reading.sourceLabel()).append(" · Reported ").append(time);
        if (!reading.backupNotice().isEmpty()) detail.append('\n').append(reading.backupNotice());

        int color = AqiCalculator.usColor(reading.usAqi);
        RemoteViews compact = new RemoteViews(context.getPackageName(), R.layout.notification_compact);
        fillChip(compact, reading.usAqi, color);
        compact.setTextViewText(R.id.notification_title, title);
        compact.setTextViewText(R.id.notification_text, summary);

        RemoteViews expanded = new RemoteViews(context.getPackageName(), R.layout.notification_expanded);
        fillChip(expanded, reading.usAqi, color);
        expanded.setTextViewText(R.id.notification_title, title);
        expanded.setTextViewText(R.id.notification_text, european);
        expanded.setTextViewText(R.id.notification_detail, detail);
        expanded.setViewVisibility(R.id.notification_alert_container, alert.active() ? View.VISIBLE : View.GONE);
        if (alert.active()) {
            expanded.setInt(R.id.notification_alert_background, "setColorFilter", alert.level().color);
            expanded.setTextViewCompoundDrawablesRelative(R.id.notification_alert, AlertSymbols.icon(alert.level()), 0, 0, 0);
            expanded.setTextViewText(R.id.notification_alert, alert.message());
        }

        String plain = "US " + reading.usAqi + " " + reading.usLevel + "  •  " + european + "  •  " + time;
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(AlertSymbols.icon(alert.level()))
                .setContentTitle(alert.active() ? alert.level().title + " · " + alert.timeLabel() + " · "
                        + reading.locationName : reading.locationName)
                .setContentText(plain)
                .setSubText(reading.locationName)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(compact)
                .setCustomBigContentView(expanded)
                .setContentIntent(open)
                .setColor(alert.active() ? alert.level().color : color)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_refresh, context.getString(R.string.update), refresh).build());
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static void fillChip(RemoteViews views, int aqi, int color) {
        views.setInt(R.id.notification_chip, "setColorFilter", color);
        views.setTextColor(R.id.notification_aqi, Palette.on(color));
        views.setTextViewText(R.id.notification_aqi, Integer.toString(aqi));
    }
}
