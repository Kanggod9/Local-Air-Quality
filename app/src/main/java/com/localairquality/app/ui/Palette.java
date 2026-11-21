package com.localairquality.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** One set of surface and text colours per system theme, shared by every screen. */
public final class Palette {
    public final boolean dark;
    public final int background, surface, surfaceAlt, ink, muted, outline, accent, onAccent;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = 0xFF0D1217; surface = 0xFF171E26; surfaceAlt = 0xFF212B36;
            ink = 0xFFEEF2F6; muted = 0xFF9BA8B6; outline = 0xFF2B3743;
            accent = 0xFF4FD1B8; onAccent = 0xFF06231D;
        } else {
            background = 0xFFF2F5F8; surface = 0xFFFFFFFF; surfaceAlt = 0xFFEDF1F5;
            ink = 0xFF18212B; muted = 0xFF5A6777; outline = 0xFFDFE5EB;
            accent = 0xFF0F7B6C; onAccent = 0xFFFFFFFF;
        }
    }

    public static Palette of(Context context) {
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new Palette(night == Configuration.UI_MODE_NIGHT_YES);
    }

    /** A card colour carrying a hint of an air-quality colour. */
    public int tint(int color, float amount) {
        return blend(surface, color, amount);
    }

    /** An air-quality colour made readable as text or a thin mark on this theme's surfaces. */
    public int readable(int color) {
        return dark ? blend(color, Color.WHITE, 0.18f) : blend(color, Color.BLACK, 0.38f);
    }

    public static int blend(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.argb(255,
                Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }

    public static int alpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Dark ink or white, whichever is easier to read on a saturated air-quality colour. */
    public static int on(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.58 ? 0xFF18212B : Color.WHITE;
    }
}
