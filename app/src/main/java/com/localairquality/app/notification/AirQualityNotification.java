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

import com.localairquality.app.MainActivity;
import com.localairquality.app.R;
import com.localairquality.app.background.RefreshReceiver;
import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.ui.AlertSymbols;

import java.text.DateFormat;
import java.util.Date;

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

        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(reading.measuredAtMillis));
        String text = "US " + reading.usAqi + " " + reading.usLevel
                + "  •  EU " + reading.euBand + " " + reading.euLevel + "  •  " + time;
        var alert = reading.alert;
        String expanded = (alert.active() ? alert.message()+"\n\n" : "")
                + text + "\n" + reading.stationName + " • " + reading.mainPollutant;
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(AlertSymbols.icon(alert.level()))
                .setContentTitle(alert.active() ? alert.level().title+" · "+alert.timeLabel()+" · "+reading.locationName : reading.locationName)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(expanded))
                .setContentIntent(open)
                .setColor(alert.active() ? alert.level().color : com.localairquality.app.data.AqiCalculator.usColor(reading.usAqi))
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_air_quality, context.getString(R.string.update), refresh).build());
        if (alert.active()) builder.setLargeIcon(AlertSymbols.badge(context, alert.level()));
        Notification notification = builder.build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }
}
