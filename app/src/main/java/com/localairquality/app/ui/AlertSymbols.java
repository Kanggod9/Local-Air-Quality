package com.localairquality.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import com.localairquality.app.R;
import com.localairquality.app.data.AirQualityAlert;

/** Shared, colour-independent warning triangle with one to four severity marks. */
public final class AlertSymbols {
    private AlertSymbols() {}
    public static int icon(AirQualityAlert.Level level) {
        return switch (level) {
            case RED -> R.drawable.ic_alert_1;
            case DEEP_RED -> R.drawable.ic_alert_2;
            case PURPLE -> R.drawable.ic_alert_3;
            case BLACK -> R.drawable.ic_alert_4;
            default -> R.drawable.ic_air_quality;
        };
    }

    public static Bitmap badge(Context context, AirQualityAlert.Level level) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(level.color);
        canvas.drawRoundRect(0, 0, 64, 64, 12, 12, paint);
        draw(context, canvas, level, 10, 10, 44);
        return bitmap;
    }

    public static void draw(Context context, Canvas canvas, AirQualityAlert.Level level,
                            float x, float y, float size) {
        Drawable drawable = context.getDrawable(icon(level));
        if (drawable == null) return;
        drawable.setBounds((int)x, (int)y, (int)(x+size), (int)(y+size));
        drawable.draw(canvas);
    }
}
