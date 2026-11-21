package com.localairquality.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Draws every screen behind transparent system bars and reports the space they cover. */
public final class SystemBars {
    private SystemBars() {}

    /** Call after setContentView. */
    @SuppressWarnings("deprecation")
    public static void edgeToEdge(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) controller.setSystemBarsAppearance(palette.dark ? 0 : lightBars, lightBars);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!palette.dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** Left, top, right and bottom space covered by system bars and display cutouts. */
    @SuppressWarnings("deprecation")
    public static int[] insets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new int[]{bars.left, bars.top, bars.right, bars.bottom};
        }
        return new int[]{insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
    }
}
