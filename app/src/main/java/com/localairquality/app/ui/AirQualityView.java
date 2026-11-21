package com.localairquality.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AqiCalculator;
import com.localairquality.app.data.HealthAdvice;
import com.localairquality.app.data.HistorySeries;
import com.localairquality.app.data.PollutantHistory;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The home screen, drawn on one hardware-accelerated canvas. Text layouts are cached between
 * frames, so scrolling, the gauge animation and the refresh spinner allocate nothing.
 */
public final class AirQualityView extends View {
    private static final Typeface NORMAL_FONT = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD_FONT = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private static final String[] NAMES = {"Fine particles", "Coarse particles", "Ozone",
            "Nitrogen dioxide", "Carbon monoxide", "Sulphur dioxide"};
    private static final String[] SYMBOLS = {"PM2.5", "PM10", "O₃", "NO₂", "CO", "SO₂"};
    private static final String[] HISTORY_LABELS = {"US AQI+", "European AQI", "PM2.5", "PM10", "O3", "NO2",
            "CO", "SO2", "US NowCast"};
    private static final int[] US_BOUNDS = {0, 50, 100, 150, 200, 300, 500};
    private static final int[] US_SAMPLES = {0, 51, 101, 151, 201, 301};

    // Tap targets, in content coordinates.
    private static final int T_REFRESH = 0, T_ALERT = 1, T_US = 2, T_EU = 3, T_NOWCAST = 4,
            T_POLLUTANT = 5, T_BACKUP = 11, T_ACTION = 12, TARGETS = 13;
    // Cached text slots.
    private static final int S_TITLE = 0, S_SUBTITLE = 1, S_NOTICE = 2, S_ALERT = 3, S_US_LEVEL = 4,
            S_EU_LEVEL = 5, S_NOW_LEVEL = 6, S_NOW_DETAIL = 7, S_STATION = 8, S_SOURCE = 9,
            S_REMINDER = 10, S_STATE = 11, S_TILE_NAME = 12, S_TILE_LEVEL = 18, SLOTS = 24;

    public enum Action { REQUEST_LOCATION, ENABLE_LOCATION, ENTER_API_KEY, RETRY }

    public interface Listener {
        void onRefreshRequested();
        void onPollutantSelected(String pollutant);
        default void onAlertSelected() {}
        void onActionRequested(Action action);
    }

    private final Palette palette;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final RectF[] targets = new RectF[TARGETS];
    private final float[] targetRadius = new float[TARGETS];
    private final StaticLayout[] layouts = new StaticLayout[SLOTS];
    private final CharSequence[] slotText = new CharSequence[SLOTS];
    private final String[] slotFitted = new String[SLOTS];
    private final float[] slotWidth = new float[SLOTS];
    private final float density, scaled, fontScale;
    private final int touchSlop, minFling, maxFling;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private ValueAnimator gaugeAnimator;

    private Listener listener;
    private AirQualityReading reading;
    private boolean refreshing;
    private String stateMessage, actionLabel;
    private Action action;
    private int insetLeft, insetTop, insetRight, insetBottom;
    private float scrollOffset, contentHeight, downX, downY, lastY, pullDistance, gauge = 1f;
    private boolean dragging;
    private int pressed = -1, shownAqi = -1;

    // Values derived once per reading rather than once per frame.
    private String subtitle = "", measuredTime = "—", nowcastDetail = "", distance = "", stationNote = "";
    private final String[] tileValue = new String[6], tileUnit = new String[6], tileLevel = new String[6];
    private final int[] tileColor = new int[6];
    private StaticLayout adviceLayout;
    private HealthAdvice.Advice advice;
    private long adviceCheckedAt;
    private int adviceWidth;

    public AirQualityView(Context context) {
        super(context);
        palette = Palette.of(context);
        density = getResources().getDisplayMetrics().density;
        scaled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());
        fontScale = getResources().getConfiguration().fontScale;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFling = configuration.getScaledMinimumFlingVelocity();
        maxFling = configuration.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
        for (int i = 0; i < TARGETS; i++) targets[i] = new RectF();
        setBackgroundColor(palette.background);
        setFocusable(true);
        setContentDescription("Local Air Quality");
        setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = SystemBars.insets(insets);
            insetLeft = bars[0]; insetTop = bars[1]; insetRight = bars[2]; insetBottom = bars[3];
            invalidate();
            return insets;
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void showReading(AirQualityReading reading, boolean refreshing) {
        this.reading = reading;
        this.refreshing = refreshing;
        stateMessage = null;
        actionLabel = null;
        action = null;
        measuredTime = formatDateTime(reading.measuredAtMillis);
        // A city-state's place name already is its country ("Singapore · Singapore").
        subtitle = reading.countryName.isBlank() || reading.countryName.equalsIgnoreCase(reading.locationName)
                ? measuredTime : reading.countryName + " · " + measuredTime;
        var nowcast = reading.nowcast;
        nowcastDetail = nowcast == null || nowcast.aqi() < 0 ? "Waiting for enough hourly data" : nowcast.status() + "\n"
                + nowcast.pollutant() + " · "
                + AirQualityReading.formatConcentration(nowcast.pollutant(), nowcast.concentration());
        distance = String.format(Locale.getDefault(), "%.1f km away", reading.distanceKm);
        String reminder = reading.otherStationRecords.message();
        String backup = reading.backupNotice();
        stationNote = backup.isEmpty() ? reminder : backup + (reminder.isEmpty() ? "" : "\n" + reminder);
        double[] values = reading.values();
        for (int p = 0; p < 6; p++) {
            String label = PollutantHistory.LABELS[p];
            String formatted = AirQualityReading.formatConcentration(label, values[p]);
            int space = formatted.indexOf(' ');
            tileValue[p] = space < 0 ? formatted : formatted.substring(0, space);
            tileUnit[p] = space < 0 ? "" : formatted.substring(space + 1);
            HistorySeries.Metric metric = HistorySeries.Metric.forLabel(label);
            int band = HistorySeries.band(metric, values[p]);
            tileLevel[p] = band == 0 ? "No data" : HistorySeries.level(metric, band);
            tileColor[p] = HistorySeries.color(metric, band);
        }
        adviceLayout = null;
        adviceCheckedAt = 0;
        describe(null);
        if (reading.usAqi != shownAqi) {
            shownAqi = reading.usAqi;
            animateGauge();
        }
        invalidate();
    }

    public void showState(String message, String actionLabel, Action action) {
        this.stateMessage = message;
        this.actionLabel = actionLabel;
        this.action = action;
        this.refreshing = false;
        invalidate();
    }

    public void setRefreshing(boolean refreshing) {
        this.refreshing = refreshing;
        if (refreshing && reading == null) stateMessage = "Finding local station";
        invalidate();
    }

    public void showProgress(String message) {
        refreshing = true;
        actionLabel = null;
        action = null;
        stateMessage = reading == null ? message : null;
        invalidate();
    }

    public void clearReading() {
        reading = null;
        shownAqi = -1;
        scrollOffset = 0;
        scroller.forceFinished(true);
        invalidate();
    }

    public boolean needsRetry() {
        return stateMessage != null && !refreshing && action != Action.ENTER_API_KEY;
    }

    private void animateGauge() {
        if (gaugeAnimator != null) gaugeAnimator.cancel();
        gaugeAnimator = ValueAnimator.ofFloat(0f, 1f);
        gaugeAnimator.setDuration(750);
        gaugeAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        gaugeAnimator.addUpdateListener(animation -> {
            gauge = (float) animation.getAnimatedValue();
            invalidate();
        });
        gaugeAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (gaugeAnimator != null) gaugeAnimator.cancel();
        if (velocity != null) { velocity.recycle(); velocity = null; }
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (RectF target : targets) target.setEmpty();
        float left = insetLeft + dp(16), right = getWidth() - insetRight - dp(16);
        float limit = dp(620);
        if (right - left > limit) {
            float centre = (left + right) / 2;
            left = centre - limit / 2;
            right = centre + limit / 2;
        }
        float width = right - left;
        float top = insetTop + dp(14);

        canvas.save();
        canvas.translate(0, -scrollOffset + (scrollOffset <= 0 ? pullDistance * 0.5f : 0));
        float bottom = reading != null ? drawContent(canvas, left, top, width) : drawEmpty(canvas, left, top, width);
        if (pressed >= 0 && !targets[pressed].isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Palette.alpha(palette.ink, palette.dark ? 34 : 22));
            canvas.drawRoundRect(targets[pressed], targetRadius[pressed], targetRadius[pressed], paint);
        }
        canvas.restore();

        contentHeight = reading != null ? bottom + insetBottom + dp(24) : getHeight();
        float maxScroll = maxScroll();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            invalidate();
        }
        if (scrollOffset > 0 && insetTop > 0) {
            // Keep scrolled content from showing through the status bar icons.
            fill(palette.background);
            canvas.drawRect(0, 0, getWidth(), insetTop, paint);
        }
        if (pullDistance > dp(6) || (refreshing && reading != null && scrollOffset > dp(40))) drawPullIndicator(canvas);
        if (refreshing) postInvalidateOnAnimation();
    }

    private float drawContent(Canvas canvas, float x, float y, float width) {
        y = drawHeader(canvas, x, y, width, reading.locationName, subtitle) + dp(18);
        if (stateMessage != null) y = drawNotice(canvas, x, y, width) + dp(12);
        y = drawAlert(canvas, x, y, width);
        y = drawUsCard(canvas, x, y, width) + dp(12);
        y = drawIndexRow(canvas, x, y, width) + dp(26);
        y = drawPollutants(canvas, x, y, width) + dp(14);
        y = drawHealthAdvice(canvas, x, y, width) + dp(12);
        y = drawStationCard(canvas, x, y, width) + dp(18);
        return top(canvas, "Station report · " + measuredTime, x + dp(4), y, 12.5f, palette.muted, false);
    }

    private float drawHeader(Canvas canvas, float x, float y, float width, String title, String detail) {
        float button = dp(44);
        float textWidth = width - button - dp(12);
        float start = y;
        y = top(canvas, fit(S_TITLE, title, textWidth, 26, true), x + dp(2), y, 26, palette.ink, true);
        y = top(canvas, fit(S_SUBTITLE, detail, textWidth, 14, false), x + dp(2), y + dp(2), 14, palette.muted, false);

        float cx = x + width - button / 2, cy = start + Math.max(button, y - start) / 2;
        target(T_REFRESH, cx - dp(24), cy - dp(24), cx + dp(24), cy + dp(24), dp(24));
        fill(palette.surface);
        canvas.drawCircle(cx, cy, button / 2, paint);
        stroke(palette.outline, dp(1));
        canvas.drawCircle(cx, cy, button / 2, paint);
        drawRefreshGlyph(canvas, cx, cy, dp(9.5f), refreshing ? palette.accent : palette.muted,
                refreshing ? (SystemClock.uptimeMillis() % 900) * 0.4f : 0);
        return y;
    }

    private float drawNotice(Canvas canvas, float x, float y, float width) {
        float pad = dp(16);
        StaticLayout message = block(S_NOTICE, stateMessage, 15, true, palette.ink,
                width - pad * 2, Layout.Alignment.ALIGN_NORMAL, 4);
        float height = pad + message.getHeight() + (actionLabel == null ? 0 : dp(12) + dp(40)) + pad;
        card(canvas, x, y, width, height, dp(18), palette.surface, true);
        draw(canvas, message, x + pad, y + pad);
        if (actionLabel != null) {
            float buttonTop = y + pad + message.getHeight() + dp(12);
            drawButton(canvas, x + pad, buttonTop, Math.min(width - pad * 2, measure(actionLabel, 15, true) + dp(40)));
        }
        return y + height;
    }

    private void drawButton(Canvas canvas, float x, float y, float width) {
        target(T_ACTION, x, y - dp(4), x + width, y + dp(44), dp(22));
        fill(palette.accent);
        canvas.drawRoundRect(x, y, x + width, y + dp(40), dp(20), dp(20), paint);
        centred(canvas, actionLabel, x + width / 2, y + dp(20), 15, palette.onAccent, true);
    }

    private float drawAlert(Canvas canvas, float x, float y, float width) {
        if (!reading.alert.active()) return y;
        StaticLayout message = block(S_ALERT, reading.alert.message(), 15, true, 0xFFFFFFFF,
                width - dp(106), Layout.Alignment.ALIGN_NORMAL, 6);
        float height = Math.max(dp(76), message.getHeight() + dp(32));
        target(T_ALERT, x, y, x + width, y + height, dp(20));
        fill(reading.alert.level().color);
        canvas.drawRoundRect(x, y, x + width, y + height, dp(20), dp(20), paint);
        AlertSymbols.draw(getContext(), canvas, reading.alert.level(), x + dp(16), y + (height - dp(40)) / 2, dp(40));
        draw(canvas, message, x + dp(70), y + (height - message.getHeight()) / 2);
        chevron(canvas, x + width - dp(20), y + height / 2, 0xFFFFFFFF);
        return y + height + dp(12);
    }

    /** The headline index: a six-band dial with the level and main pollutant beneath it. */
    private float drawUsCard(Canvas canvas, float x, float y, float width) {
        int aqi = reading.usAqi;
        int color = AqiCalculator.usColor(aqi);
        int background = palette.tint(color, palette.dark ? 0.17f : 0.15f);
        float pad = dp(18);
        float radius = Math.min(dp(96), (width - dp(88)) / 2);
        float band = dp(13);
        float labelHeight = lineHeight(12.5f, true);
        float centreX = x + width / 2;
        float centreY = y + pad + labelHeight + dp(10) + band / 2 + radius;
        float dialBottom = centreY + radius * 0.5f + band / 2;

        String level = reading.usLevel;
        float levelWidth = Math.min(width - pad * 2 - dp(28), measure(level, 16, true) + dp(2));
        StaticLayout levelLayout = block(S_US_LEVEL, level, 16, true, Palette.on(color), levelWidth,
                Layout.Alignment.ALIGN_CENTER, 2);
        float pillHeight = levelLayout.getHeight() + dp(14);
        float pillTop = dialBottom + dp(12);
        float divider = pillTop + pillHeight + dp(16);
        float rowHeight = lineHeight(12.5f, false) + dp(2) + lineHeight(17, true);
        float height = divider + dp(14) + rowHeight + pad - y;

        target(T_US, x, y, x + width, y + height, dp(26));
        fill(background);
        canvas.drawRoundRect(x, y, x + width, y + height, dp(26), dp(26), paint);
        top(canvas, "US AQI+", x + pad, y + pad, 12.5f, palette.muted, true);
        chevron(canvas, x + width - pad - dp(2), y + pad + labelHeight / 2, palette.muted);

        // Six equal segments, one per US category, so the hazardous range does not swamp the dial.
        float fraction = usFraction(aqi) * gauge;
        float reach = 150 + 240 * fraction;
        oval.set(centreX - radius, centreY - radius, centreX + radius, centreY + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(band);
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i < 6; i++) {
            float start = 150 + i * 40 + 1.2f, sweep = 40 - 2.4f;
            int segment = AqiCalculator.usColor(US_SAMPLES[i]);
            paint.setColor(Palette.alpha(segment, palette.dark ? 60 : 66));
            canvas.drawArc(oval, start, sweep, false, paint);
            float filled = Math.min(sweep, reach - start);
            if (filled > 0) {
                paint.setColor(segment);
                canvas.drawArc(oval, start, filled, false, paint);
            }
        }
        double angle = Math.toRadians(reach);
        float knobX = centreX + radius * (float) Math.cos(angle), knobY = centreY + radius * (float) Math.sin(angle);
        fill(background);
        canvas.drawCircle(knobX, knobY, dp(11), paint);
        fill(palette.readable(color));
        canvas.drawCircle(knobX, knobY, dp(7), paint);

        float numberSize = Math.min(54 * scaled, radius * 0.72f);
        textPaint.setTypeface(BOLD_FONT);
        textPaint.setTextSize(numberSize);
        textPaint.setColor(palette.ink);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Integer.toString(Math.round(aqi * gauge)), centreX, centreY + numberSize * 0.30f, textPaint);
        textPaint.setTypeface(NORMAL_FONT);
        textPaint.setTextSize(Math.min(13 * scaled, radius * 0.2f));
        textPaint.setColor(palette.muted);
        canvas.drawText("of 500", centreX, centreY + numberSize * 0.30f + Math.min(20 * scaled, radius * 0.3f), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float pillWidth = levelWidth + dp(28);
        fill(color);
        canvas.drawRoundRect(centreX - pillWidth / 2, pillTop, centreX + pillWidth / 2, pillTop + pillHeight,
                pillHeight / 2, pillHeight / 2, paint);
        draw(canvas, levelLayout, centreX - levelWidth / 2, pillTop + dp(7));

        stroke(Palette.alpha(palette.ink, 26), dp(1));
        canvas.drawLine(x + pad, divider, x + width - pad, divider, paint);
        float rowTop = divider + dp(14);
        float valueTop = top(canvas, "Main pollutant", x + pad, rowTop, 12.5f, palette.muted, false) + dp(2);
        top(canvas, symbol(reading.mainPollutant), x + pad, valueTop, 17, palette.ink, true);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        top(canvas, reading.pollutantValue(reading.mainPollutant), x + width - pad, valueTop, 17, palette.ink, true);
        textPaint.setTextAlign(Paint.Align.LEFT);
        return y + height;
    }

    /** European AQI and US NowCast, side by side when there is room for both. */
    private float drawIndexRow(Canvas canvas, float x, float y, float width) {
        boolean stacked = width < dp(300) || fontScale > 1.35f;
        float gap = dp(12);
        float cardWidth = stacked ? width : (width - gap) / 2;
        float pad = dp(16);
        float inner = cardWidth - pad * 2;

        int band = reading.euBand;
        int euColor = AqiCalculator.euColor(band);
        var nowcast = reading.nowcast;
        boolean rated = nowcast != null && nowcast.aqi() >= 0;
        int nowColor = rated ? AqiCalculator.usColor(nowcast.aqi()) : AqiCalculator.euColor(0);

        StaticLayout euLevel = block(S_EU_LEVEL, reading.euLevel, 15, true, palette.ink, inner,
                Layout.Alignment.ALIGN_NORMAL, 2);
        StaticLayout nowLevel = block(S_NOW_LEVEL, nowcast == null ? "Not enough data" : nowcast.level(), 15, true,
                palette.ink, inner, Layout.Alignment.ALIGN_NORMAL, 3);
        StaticLayout nowDetail = block(S_NOW_DETAIL, nowcastDetail, 12, false, palette.muted, inner,
                Layout.Alignment.ALIGN_NORMAL, 4);
        int reported = reading.availablePollutants() - (AirQualityReading.isPresent(reading.co) ? 1 : 0);
        boolean partial = reported < 5;
        float head = pad + lineHeight(12.5f, true) + dp(6) + lineHeight(32, true) + dp(4);
        float euHeight = head + euLevel.getHeight() + dp(12) + dp(6)
                + (partial ? dp(8) + lineHeight(12, false) : 0) + pad;
        float nowHeight = head + nowLevel.getHeight() + dp(8) + nowDetail.getHeight() + pad;
        float height = stacked ? euHeight : Math.max(euHeight, nowHeight);

        // European AQI
        target(T_EU, x, y, x + cardWidth, y + height, dp(22));
        fill(palette.tint(euColor, palette.dark ? 0.15f : 0.14f));
        canvas.drawRoundRect(x, y, x + cardWidth, y + height, dp(22), dp(22), paint);
        float line = top(canvas, "European AQI", x + pad, y + pad, 12.5f, palette.muted, true) + dp(6);
        chevron(canvas, x + cardWidth - pad, y + pad + lineHeight(12.5f, true) / 2, palette.muted);
        String score = band > 0 ? Integer.toString(band) : "—";
        float scoreWidth = measure(score, 32, true);
        float next = top(canvas, score, x + pad, line, 32, palette.ink, true) + dp(4);
        baseline(canvas, " / 6", x + pad + scoreWidth, line, 32, 14, palette.muted);
        draw(canvas, euLevel, x + pad, next);
        float barTop = next + euLevel.getHeight() + dp(12);
        float segment = (inner - dp(3) * 5) / 6;
        for (int i = 1; i <= 6; i++) {
            float segmentLeft = x + pad + (i - 1) * (segment + dp(3));
            fill(i <= band ? AqiCalculator.euColor(i) : Palette.alpha(palette.ink, 26));
            canvas.drawRoundRect(segmentLeft, barTop, segmentLeft + segment, barTop + dp(6), dp(3), dp(3), paint);
        }
        if (partial) top(canvas, fit(-1, "Partial · " + reported + "/5 pollutants", inner, 12, false),
                x + pad, barTop + dp(6) + dp(8), 12, palette.muted, false);

        // US NowCast
        float nowX = stacked ? x : x + cardWidth + gap;
        float nowY = stacked ? y + height + gap : y;
        float nowCardHeight = stacked ? nowHeight : height;
        target(T_NOWCAST, nowX, nowY, nowX + cardWidth, nowY + nowCardHeight, dp(22));
        fill(rated ? palette.tint(nowColor, palette.dark ? 0.15f : 0.14f) : palette.surface);
        canvas.drawRoundRect(nowX, nowY, nowX + cardWidth, nowY + nowCardHeight, dp(22), dp(22), paint);
        if (!rated) {
            stroke(palette.outline, dp(1));
            canvas.drawRoundRect(nowX, nowY, nowX + cardWidth, nowY + nowCardHeight, dp(22), dp(22), paint);
        }
        line = top(canvas, "US NowCast", nowX + pad, nowY + pad, 12.5f, palette.muted, true) + dp(6);
        chevron(canvas, nowX + cardWidth - pad, nowY + pad + lineHeight(12.5f, true) / 2, palette.muted);
        String nowScore = nowcast == null ? "—" : nowcast.score();
        float dotX = nowX + pad + measure(nowScore, 32, true) + dp(12);
        next = top(canvas, nowScore, nowX + pad, line, 32, palette.ink, true) + dp(4);
        if (rated) {
            fill(nowColor);
            canvas.drawCircle(dotX, line + lineHeight(32, true) / 2, dp(6), paint);
        }
        draw(canvas, nowLevel, nowX + pad, next);
        draw(canvas, nowDetail, nowX + pad, next + nowLevel.getHeight() + dp(8));
        return stacked ? nowY + nowCardHeight : y + height;
    }

    private float drawPollutants(Canvas canvas, float x, float y, float width) {
        float titleBottom = top(canvas, "Pollutants", x + dp(4), y, 19, palette.ink, true);
        String hint = "Tap for 24 h history";
        if (measure(hint, 12.5f, false) + measure("Pollutants", 19, true) + dp(28) < width) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            top(canvas, hint, x + width - dp(4), y + lineHeight(19, true) - lineHeight(12.5f, false) - dp(1),
                    12.5f, palette.muted, false);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }
        y = titleBottom + dp(12);
        boolean single = width < dp(280) || fontScale > 1.6f;
        int columns = single ? 1 : 2;
        float gap = dp(10), pad = dp(14);
        float tileWidth = (width - gap * (columns - 1)) / columns;
        float tileHeight = pad + lineHeight(16, true) + lineHeight(12, false) + dp(10)
                + lineHeight(20, true) + dp(6) + lineHeight(12, false) + pad;
        for (int p = 0; p < 6; p++) {
            float left = x + (p % columns) * (tileWidth + gap);
            float tileTop = y + (p / columns) * (tileHeight + gap);
            target(T_POLLUTANT + p, left, tileTop, left + tileWidth, tileTop + tileHeight, dp(18));
            card(canvas, left, tileTop, tileWidth, tileHeight, dp(18), palette.surface, true);
            float inner = tileWidth - pad * 2;
            float line = top(canvas, SYMBOLS[p], left + pad, tileTop + pad, 16, palette.ink, true);
            chevron(canvas, left + tileWidth - pad, tileTop + pad + lineHeight(16, true) / 2, palette.muted);
            line = top(canvas, fit(S_TILE_NAME + p, NAMES[p], inner, 12, false), left + pad, line, 12,
                    palette.muted, false) + dp(10);
            float valueWidth = measure(tileValue[p], 20, true);
            float next = top(canvas, tileValue[p], left + pad, line, 20, palette.ink, true) + dp(6);
            if (!tileUnit[p].isEmpty()) baseline(canvas, " " + tileUnit[p], left + pad + valueWidth, line, 20, 12, palette.muted);
            fill(tileColor[p]);
            float levelHeight = lineHeight(12, false);
            canvas.drawCircle(left + pad + dp(4.5f), next + levelHeight / 2, dp(4.5f), paint);
            top(canvas, fit(S_TILE_LEVEL + p, tileLevel[p], inner - dp(16), 12, false), left + pad + dp(16), next,
                    12, palette.muted, false);
        }
        int rows = (6 + columns - 1) / columns;
        return y + rows * tileHeight + (rows - 1) * gap;
    }

    private float drawHealthAdvice(Canvas canvas, float x, float y, float width) {
        int bodyWidth = Math.max(1, (int) (width - dp(44)));
        long now = System.currentTimeMillis();
        if (adviceLayout == null || adviceWidth != bodyWidth || now - adviceCheckedAt > 60_000) {
            advice = HealthAdvice.forReading(reading, now);
            SpannableStringBuilder body = new SpannableStringBuilder();
            appendAdvice(body, advice.title(), advice.sensitive().isEmpty() ? "" : "Everyone");
            body.append(advice.everyone());
            if (!advice.sensitive().isEmpty()) {
                body.append("\n\n");
                appendAdvice(body, "Sensitive groups", "");
                body.append(advice.sensitive());
                body.append("\n\nChildren, older adults, and people with heart or lung conditions.");
            }
            TextPaint advicePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            advicePaint.setColor(palette.ink);
            advicePaint.setTypeface(NORMAL_FONT);
            advicePaint.setTextSize(15 * scaled);
            adviceLayout = StaticLayout.Builder.obtain(body, 0, body.length(), advicePaint, bodyWidth)
                    .setIncludePad(false).setLineSpacing(dp(3), 1f).build();
            adviceWidth = bodyWidth;
            adviceCheckedAt = now;
            describe(body);
        }
        float height = adviceLayout.getHeight() + dp(36);
        card(canvas, x, y, width, height, dp(22), palette.surface, true);
        int accent = advice.sensitive().isEmpty() ? palette.muted : advice.caution() == 0 ? palette.accent
                : advice.caution() <= 2 ? 0xFFD98A1F : 0xFFD9485B;
        fill(accent);
        canvas.drawRoundRect(x + dp(12), y + dp(20), x + dp(16), y + height - dp(20), dp(2), dp(2), paint);
        draw(canvas, adviceLayout, x + dp(28), y + dp(18));
        return y + height;
    }

    private void appendAdvice(SpannableStringBuilder body, String heading, String subheading) {
        int start = body.length();
        body.append(heading);
        body.setSpan(new StyleSpan(Typeface.BOLD), start, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.append("\n");
        if (!subheading.isEmpty()) {
            body.append("\n");
            int subStart = body.length();
            body.append(subheading);
            body.setSpan(new StyleSpan(Typeface.BOLD), subStart, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.append("\n");
        }
    }

    private float drawStationCard(Canvas canvas, float x, float y, float width) {
        float pad = dp(16), icon = dp(44);
        float textLeft = x + pad + icon + dp(14);
        float textWidth = x + width - pad - textLeft;
        StaticLayout station = block(S_STATION, reading.sourceLabel(), 16, true, palette.ink, textWidth,
                Layout.Alignment.ALIGN_NORMAL, 3);
        float detailHeight = lineHeight(12.5f, false);
        float body = Math.max(icon, station.getHeight() + dp(3) + detailHeight * 2);
        StaticLayout note = stationNote.isEmpty() ? null : block(S_REMINDER, stationNote, 12.5f, false,
                palette.muted, width - pad * 2, Layout.Alignment.ALIGN_NORMAL, 8);
        float rowTop = y + pad + body + (note == null ? 0 : dp(12) + note.getHeight()) + dp(14);
        float height = rowTop + dp(48) - y;
        card(canvas, x, y, width, height, dp(22), palette.surface, true);

        fill(Palette.alpha(palette.accent, palette.dark ? 44 : 28));
        canvas.drawCircle(x + pad + icon / 2, y + pad + icon / 2, icon / 2, paint);
        drawStationGlyph(canvas, x + pad + icon / 2, y + pad + icon / 2 - dp(1));
        draw(canvas, station, textLeft, y + pad);
        float line = y + pad + station.getHeight() + dp(3);
        line = top(canvas, fit(S_SOURCE, reading.sourceName, textWidth, 12.5f, false), textLeft, line, 12.5f,
                palette.muted, false);
        top(canvas, distance, textLeft, line, 12.5f, palette.muted, false);
        if (note != null) draw(canvas, note, x + pad, y + pad + body + dp(12));

        stroke(palette.outline, dp(1));
        canvas.drawLine(x + pad, rowTop, x + width - pad, rowTop, paint);
        target(T_BACKUP, x, rowTop, x + width, rowTop + dp(48), dp(18));
        centredLeft(canvas, "OpenAQ backup settings", x + pad, rowTop + dp(24), 14, palette.accent, true);
        chevron(canvas, x + width - pad, rowTop + dp(24), palette.accent);
        return y + height;
    }

    /** Before the first reading: a loading skeleton, or one clear request with one button. */
    private float drawEmpty(Canvas canvas, float x, float y, float width) {
        y = drawHeader(canvas, x, y, width, "Local Air Quality", "Nearest official monitoring station") + dp(22);
        if (refreshing || stateMessage == null) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(SystemClock.uptimeMillis() / 260.0);
            fill(Palette.alpha(palette.ink, Math.round((palette.dark ? 22 : 14) * (0.6f + 0.4f * pulse))));
            canvas.drawRoundRect(x, y, x + width, y + dp(250), dp(26), dp(26), paint);
            float half = (width - dp(12)) / 2;
            canvas.drawRoundRect(x, y + dp(262), x + half, y + dp(392), dp(22), dp(22), paint);
            canvas.drawRoundRect(x + half + dp(12), y + dp(262), x + width, y + dp(392), dp(22), dp(22), paint);
            centred(canvas, stateMessage == null ? "Loading" : stateMessage, x + width / 2, y + dp(125), 16,
                    palette.muted, true);
            return y + dp(392);
        }
        float pad = dp(22);
        StaticLayout message = block(S_STATE, stateMessage, 19, true, palette.ink, width - pad * 2,
                Layout.Alignment.ALIGN_CENTER, 5);
        float height = pad + dp(64) + dp(18) + message.getHeight() + (actionLabel == null ? 0 : dp(22) + dp(40)) + pad;
        float cardTop = Math.max(y, (getHeight() - height) / 2 - dp(24));
        card(canvas, x, cardTop, width, height, dp(26), palette.surface, true);
        float cx = x + width / 2, iconY = cardTop + pad + dp(32);
        fill(Palette.alpha(palette.accent, palette.dark ? 44 : 28));
        canvas.drawCircle(cx, iconY, dp(32), paint);
        drawStationGlyph(canvas, cx, iconY - dp(1));
        draw(canvas, message, x + pad, cardTop + pad + dp(82));
        if (actionLabel != null) {
            float buttonWidth = Math.min(width - pad * 2, Math.max(dp(180), measure(actionLabel, 15, true) + dp(48)));
            drawButton(canvas, cx - buttonWidth / 2, cardTop + pad + dp(82) + message.getHeight() + dp(22), buttonWidth);
        }
        return cardTop + height;
    }

    private void drawPullIndicator(Canvas canvas) {
        float travel = refreshing ? dp(44) : Math.min(dp(64), pullDistance * 0.5f);
        float cx = getWidth() / 2f, cy = insetTop + travel - dp(6);
        fill(palette.surface);
        canvas.drawCircle(cx, cy, dp(19), paint);
        stroke(palette.outline, dp(1));
        canvas.drawCircle(cx, cy, dp(19), paint);
        boolean armed = pullDistance >= dp(84);
        drawRefreshGlyph(canvas, cx, cy, dp(8.5f), armed || refreshing ? palette.accent : palette.muted,
                refreshing ? (SystemClock.uptimeMillis() % 900) * 0.4f : Math.min(300, pullDistance * 1.6f));
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true);
                downX = event.getX();
                downY = lastY = event.getY();
                dragging = false;
                pressed = targetAt(downX, downY + scrollOffset);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                float y = event.getY();
                if (!dragging && (Math.abs(y - downY) > touchSlop || Math.abs(event.getX() - downX) > touchSlop)) {
                    dragging = true;
                    pressed = -1;
                    lastY = y;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    float dy = y - lastY;
                    if (scrollOffset <= 0 && (pullDistance > 0 || dy > 0) && !refreshing) {
                        pullDistance = Math.max(0, Math.min(dp(170), pullDistance + dy * 0.75f));
                    } else {
                        pullDistance = 0;
                        scrollOffset = clamp(scrollOffset - dy, 0, maxScroll());
                    }
                    lastY = y;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                pullDistance = 0;
                pressed = -1;
                dragging = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (dragging) {
                    if (pullDistance >= dp(84) && !refreshing && listener != null) {
                        listener.onRefreshRequested();
                    } else if (pullDistance <= 0) {
                        velocity.computeCurrentVelocity(1000, maxFling);
                        float speed = -velocity.getYVelocity();
                        if (Math.abs(speed) > minFling) {
                            scroller.fling(0, Math.round(scrollOffset), 0, Math.round(speed), 0, 0,
                                    0, Math.round(maxScroll()));
                        }
                    }
                } else {
                    int hit = targetAt(event.getX(), event.getY() + scrollOffset);
                    if (hit >= 0 && hit == pressed) activate(hit);
                    performClick();
                }
                pullDistance = 0;
                pressed = -1;
                dragging = false;
                velocity.clear();
                postInvalidateOnAnimation();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float delta = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * dp(64);
            scroller.forceFinished(true);
            scrollOffset = clamp(scrollOffset + delta, 0, maxScroll());
            invalidate();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = clamp(scroller.getCurrY(), 0, maxScroll());
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float maxScroll() {
        return Math.max(0, contentHeight - getHeight());
    }

    private int targetAt(float x, float y) {
        // The action button sits on top of whatever it covers.
        if (targets[T_ACTION].contains(x, y)) return T_ACTION;
        for (int i = 0; i < TARGETS; i++) if (targets[i].contains(x, y)) return i;
        return -1;
    }

    private void activate(int target) {
        if (listener == null) return;
        if (target == T_REFRESH) {
            if (!refreshing) listener.onRefreshRequested();
        } else if (target == T_ACTION) {
            if (action != null) listener.onActionRequested(action);
        } else if (reading == null) {
            return;
        } else if (target == T_ALERT) {
            if (reading.alert.active()) listener.onAlertSelected();
        } else if (target == T_BACKUP) listener.onActionRequested(Action.ENTER_API_KEY);
        else if (target == T_US) listener.onPollutantSelected("US AQI+");
        else if (target == T_EU) listener.onPollutantSelected("European AQI");
        else if (target == T_NOWCAST) listener.onPollutantSelected("US NowCast");
        else if (target >= T_POLLUTANT && target < T_POLLUTANT + 6) {
            listener.onPollutantSelected(PollutantHistory.LABELS[target - T_POLLUTANT]);
        }
    }

    // ---------------------------------------------------------------- accessibility

    private void describe(CharSequence adviceText) {
        setContentDescription(reading.locationName + ", US AQI " + reading.usAqi + ", "
                + reading.usLevel + ", European AQI " + reading.euBand + ", " + reading.euLevel
                + (reading.nowcast == null ? ", US NowCast not enough data" : ", US NowCast "
                + reading.nowcast.score() + ", " + reading.nowcast.level() + ", " + reading.nowcast.status())
                + ". " + reading.backupNotice() + ". " + reading.alert.message()
                + ". " + reading.otherStationRecords.message() + (adviceText == null ? "" : ". " + adviceText));
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (reading == null) {
            if (action != null && actionLabel != null) info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(0x01000022, actionLabel));
            return;
        }
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000023, "Update air quality"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000021, "Open OpenAQ backup settings"));
        if (reading.alert.active()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                0x01000020, "Open alert reason, precautions and advice"));
        for (int i = 0; i < HISTORY_LABELS.length; i++) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    0x01000000 + i, "Open " + HISTORY_LABELS[i] + " history"));
        }
    }

    @Override
    public boolean performAccessibilityAction(int id, Bundle arguments) {
        if (listener != null) {
            if (id == 0x01000023 && !refreshing) { listener.onRefreshRequested(); return true; }
            if (id == 0x01000022 && action != null) { listener.onActionRequested(action); return true; }
            if (id == 0x01000021) { listener.onActionRequested(Action.ENTER_API_KEY); return true; }
            if (id == 0x01000020 && reading != null && reading.alert.active()) {
                listener.onAlertSelected();
                return true;
            }
            int index = id - 0x01000000;
            if (reading != null && index >= 0 && index < HISTORY_LABELS.length) {
                listener.onPollutantSelected(HISTORY_LABELS[index]);
                return true;
            }
        }
        return super.performAccessibilityAction(id, arguments);
    }

    // ---------------------------------------------------------------- primitives

    private static float usFraction(int aqi) {
        int value = Math.max(0, Math.min(500, aqi));
        for (int i = 0; i < 6; i++) {
            if (value <= US_BOUNDS[i + 1]) {
                return (i + (value - US_BOUNDS[i]) / (float) (US_BOUNDS[i + 1] - US_BOUNDS[i])) / 6f;
            }
        }
        return 1f;
    }

    private static String symbol(String label) {
        for (int p = 0; p < 6; p++) if (PollutantHistory.LABELS[p].equals(label)) return SYMBOLS[p];
        return label;
    }

    private void target(int id, float left, float top, float right, float bottom, float radius) {
        targets[id].set(left, top, right, bottom);
        targetRadius[id] = radius;
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
    }

    private void card(Canvas canvas, float x, float y, float width, float height, float radius, int color,
                      boolean outlined) {
        fill(color);
        canvas.drawRoundRect(x, y, x + width, y + height, radius, radius, paint);
        if (outlined) {
            stroke(palette.outline, dp(1));
            canvas.drawRoundRect(x, y, x + width, y + height, radius, radius, paint);
        }
    }

    private void font(float sizeSp, boolean bold) {
        textPaint.setTextSize(sizeSp * scaled);
        textPaint.setTypeface(bold ? BOLD_FONT : NORMAL_FONT);
    }

    private float lineHeight(float sizeSp, boolean bold) {
        font(sizeSp, bold);
        textPaint.getFontMetrics(metrics);
        return metrics.descent - metrics.ascent;
    }

    private float measure(String value, float sizeSp, boolean bold) {
        font(sizeSp, bold);
        return textPaint.measureText(value);
    }

    /** Draws one line whose top edge is at {@code y}; returns the bottom edge. Honours the paint's alignment. */
    private float top(Canvas canvas, String value, float x, float y, float sizeSp, int color, boolean bold) {
        font(sizeSp, bold);
        textPaint.setColor(color);
        textPaint.getFontMetrics(metrics);
        canvas.drawText(value == null ? "" : value, x, y - metrics.ascent, textPaint);
        return y + metrics.descent - metrics.ascent;
    }

    /** Smaller text sharing the baseline of a larger line whose top edge is at {@code y}. */
    private void baseline(Canvas canvas, String value, float x, float y, float lineSp, float sizeSp, int color) {
        font(lineSp, true);
        textPaint.getFontMetrics(metrics);
        float base = y - metrics.ascent;
        font(sizeSp, false);
        textPaint.setColor(color);
        canvas.drawText(value, x, base, textPaint);
    }

    private void centred(Canvas canvas, String value, float cx, float cy, float sizeSp, int color, boolean bold) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        centredLeft(canvas, value, cx, cy, sizeSp, color, bold);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    /** One line centred vertically on {@code cy}. */
    private void centredLeft(Canvas canvas, String value, float x, float cy, float sizeSp, int color, boolean bold) {
        font(sizeSp, bold);
        textPaint.setColor(color);
        textPaint.getFontMetrics(metrics);
        canvas.drawText(value, x, cy - (metrics.ascent + metrics.descent) / 2, textPaint);
    }

    /** Ellipsised single line, remembered per slot. Slot -1 is never cached. */
    private String fit(int slot, String value, float width, float sizeSp, boolean bold) {
        if (value == null) return "";
        if (slot >= 0 && value.equals(slotText[slot]) && slotWidth[slot] == width) return slotFitted[slot];
        font(sizeSp, bold);
        String fitted = TextUtils.ellipsize(value, textPaint, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        if (slot >= 0) {
            slotText[slot] = value;
            slotWidth[slot] = width;
            slotFitted[slot] = fitted;
        }
        return fitted;
    }

    /** Wrapped text, rebuilt only when its content or width changes. */
    private StaticLayout block(int slot, CharSequence value, float sizeSp, boolean bold, int color, float width,
                               Layout.Alignment alignment, int maxLines) {
        int available = Math.max(1, (int) width);
        if (layouts[slot] != null && value.equals(slotText[slot]) && slotWidth[slot] == available
                && layouts[slot].getPaint().getColor() == color) return layouts[slot];
        TextPaint own = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        own.setTextSize(sizeSp * scaled);
        own.setTypeface(bold ? BOLD_FONT : NORMAL_FONT);
        own.setColor(color);
        layouts[slot] = StaticLayout.Builder.obtain(value, 0, value.length(), own, available)
                .setAlignment(alignment).setIncludePad(false).setLineSpacing(dp(2), 1f)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
        slotText[slot] = value;
        slotWidth[slot] = available;
        return layouts[slot];
    }

    private void draw(Canvas canvas, StaticLayout layout, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private void chevron(Canvas canvas, float right, float cy, int color) {
        stroke(color, dp(1.8f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.rewind();
        path.moveTo(right - dp(6), cy - dp(5));
        path.lineTo(right - dp(1), cy);
        path.lineTo(right - dp(6), cy + dp(5));
        canvas.drawPath(path, paint);
    }

    private void drawStationGlyph(Canvas canvas, float cx, float cy) {
        stroke(palette.accent, dp(2));
        canvas.drawCircle(cx, cy - dp(2), dp(4.5f), paint);
        canvas.drawLine(cx, cy + dp(2.5f), cx, cy + dp(11), paint);
        canvas.drawLine(cx - dp(8), cy + dp(11), cx + dp(8), cy + dp(11), paint);
        oval.set(cx - dp(11), cy - dp(13), cx + dp(11), cy + dp(9));
        canvas.drawArc(oval, 205, 130, false, paint);
    }

    private void drawRefreshGlyph(Canvas canvas, float cx, float cy, float radius, int color, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        stroke(color, dp(2.2f));
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(oval, -50, 285, false, paint);
        fill(color);
        path.rewind();
        path.moveTo(cx + radius - dp(1), cy - dp(5.5f));
        path.lineTo(cx + radius + dp(5), cy - dp(4));
        path.lineTo(cx + radius + dp(1), cy + dp(1.5f));
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private String formatDateTime(long millis) {
        if (millis <= 0) return "—";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(millis));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * density;
    }
}
