package com.localairquality.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AqiCalculator;
import com.localairquality.app.data.HealthAdvice;
import com.localairquality.app.data.HistorySeries;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class AirQualityView extends View {
    private static final int BACKGROUND = Color.rgb(244, 247, 250);
    private static final int INK = Color.rgb(31, 40, 52);
    private static final int MUTED = Color.rgb(91, 103, 119);
    private static final int TILE = Color.rgb(242, 245, 247);
    private static final Typeface NORMAL_FONT = Typeface.create("sans", Typeface.NORMAL);
    private static final Typeface BOLD_FONT = Typeface.create("sans", Typeface.BOLD);

    public enum Action { REQUEST_LOCATION, ENABLE_LOCATION, ENTER_API_KEY, RETRY }

    public interface Listener {
        void onRefreshRequested();
        void onPollutantSelected(String pollutant);
        void onActionRequested(Action action);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF updateBounds = new RectF();
    private final RectF actionBounds = new RectF();
    private final RectF usBounds = new RectF(), euBounds = new RectF(), nowcastBounds = new RectF();
    private final RectF[] pollutantBounds = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final float density;
    private final float scaledDensity;
    private Listener listener;
    private AirQualityReading reading;
    private boolean refreshing;
    private String stateMessage;
    private String actionLabel;
    private Action action;
    private float scrollOffset;
    private float contentHeight;
    private float downY;
    private float lastY;
    private float pullDistance;
    private boolean moved;
    private final TextPaint advicePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout adviceLayout;
    private HealthAdvice.Advice advice;
    private long adviceCheckedAt;
    private int adviceWidth;
    private String measuredTime = "—";

    public AirQualityView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        setBackgroundColor(BACKGROUND);
        setFocusable(true);
        setContentDescription("Local Air Quality");
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        advicePaint.setColor(INK);
        advicePaint.setTypeface(NORMAL_FONT);
        advicePaint.setTextSize(15 * scaledDensity);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void showReading(AirQualityReading reading, boolean refreshing) {
        this.reading = reading;
        this.refreshing = refreshing;
        this.stateMessage = null;
        this.actionLabel = null;
        this.action = null;
        measuredTime = formatDateTime(reading.measuredAtMillis);
        adviceLayout = null;
        adviceCheckedAt = 0;
        setContentDescription(reading.locationName + ", US AQI " + reading.usAqi + ", "
                + reading.usLevel + ", European AQI " + reading.euBand + ", " + reading.euLevel + nowcastDescription());
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
        scrollOffset = 0;
        invalidate();
    }

    public boolean needsRetry() {
        return stateMessage != null && !refreshing && action != Action.ENTER_API_KEY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (RectF bounds : pollutantBounds) bounds.setEmpty();
        actionBounds.setEmpty();
        usBounds.setEmpty(); euBounds.setEmpty(); nowcastBounds.setEmpty();
        updateBounds.setEmpty();
        canvas.drawColor(BACKGROUND);
        float translatedPull = scrollOffset <= 0 ? pullDistance * 0.45f : 0;
        canvas.save();
        canvas.translate(0, -scrollOffset + translatedPull);
        if (reading != null) drawContent(canvas);
        else drawEmptyHeader(canvas);
        canvas.restore();

        if (reading == null || stateMessage != null) drawStateOverlay(canvas);
        if (refreshing || pullDistance > dp(8)) drawRefreshIndicator(canvas);
        if (refreshing) postInvalidateDelayed(80);
    }

    private void drawContent(Canvas canvas) {
        float pad = dp(18);
        float width = getWidth() - pad * 2;
        float y = dp(22);

        drawLocationHeader(canvas, pad, y, width);
        y += dp(108);

        drawUsCard(canvas, pad, y, width, dp(218));
        y += dp(234);
        drawEuCard(canvas, pad, y, width, dp(132));
        y += dp(148);

        var nowcast = reading.nowcast;
        String detail = nowcast == null ? "Not enough hourly data" : nowcast.status() + "\n"
                + nowcast.pollutant() + " · " + AirQualityReading.formatConcentration(nowcast.pollutant(), nowcast.concentration())
                + "\n" + formatDateTime(nowcast.measuredAt());
        y = drawIndexCard(canvas, pad, y, width, "US AQI · NowCast", nowcast == null ? "—" : nowcast.score(),
                nowcast == null ? "Not enough data" : nowcast.level(),
                nowcast == null || nowcast.aqi() < 0 ? AqiCalculator.euColor(0) : AqiCalculator.usColor(nowcast.aqi()),
                detail, nowcastBounds);
        y += dp(16);

        drawStationCard(canvas, pad, y, width, dp(104));
        y += dp(122);

        y = drawPollutants(canvas, pad, y, width);
        y = drawHealthAdvice(canvas, pad, y + dp(18), width);
        y += dp(24);

        text(canvas, "Updated " + measuredTime, pad, y,
                13, MUTED, Paint.Align.LEFT, Typeface.NORMAL);
        y += dp(38);
        contentHeight = y;
    }

    private float drawIndexCard(Canvas canvas, float x, float y, float width, String title, String score,
                                String level, int color, String detail, RectF bounds) {
        TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTypeface(BOLD_FONT); labelPaint.setColor(INK); labelPaint.setTextSize(17 * scaledDensity);
        StaticLayout levelLayout = StaticLayout.Builder.obtain(level, 0, level.length(), labelPaint,
                Math.max(1, (int)(width - dp(115)))).setIncludePad(false).build();
        float body = Math.max(dp(38), levelLayout.getHeight());
        TextPaint detailPaint = new TextPaint(labelPaint); detailPaint.setTypeface(NORMAL_FONT); detailPaint.setTextSize(12 * scaledDensity);
        StaticLayout detailLayout = StaticLayout.Builder.obtain(detail, 0, detail.length(), detailPaint,
                Math.max(1, (int)(width - dp(32)))).setIncludePad(false).setLineSpacing(dp(3), 1).build();
        float height = dp(58) + body + (detail.isEmpty() ? 0 : detailLayout.getHeight() + dp(10));
        bounds.set(x,y,x+width,y+height);
        rounded(canvas,x,y,x+width,y+height,dp(22),color);
        // Use a light neutral label panel for legible text even on dark hazardous colours.
        rounded(canvas,x+dp(8),y+dp(8),x+width-dp(8),y+height-dp(8),dp(16),0xEFFFFFFF);
        text(canvas,title,x+dp(16),y+dp(30),14,INK,Paint.Align.LEFT,Typeface.BOLD);
        text(canvas,"›",x+width-dp(17),y+dp(30),18,INK,Paint.Align.RIGHT,Typeface.NORMAL);
        text(canvas,score,x+dp(16),y+dp(67),29,INK,Paint.Align.LEFT,Typeface.BOLD);
        canvas.save(); canvas.translate(x+dp(99),y+dp(44)); levelLayout.draw(canvas); canvas.restore();
        if (!detail.isEmpty()) {
            canvas.save(); canvas.translate(x+dp(16),y+dp(48)+body); detailLayout.draw(canvas); canvas.restore();
        }
        return y+height;
    }

    private void drawLocationHeader(Canvas canvas, float x, float y, float width) {
        drawLocationArrow(canvas, x + dp(15), y + dp(22));
        String title = reading.locationName;
        if (title.length() > 28) title = title.substring(0, 27) + "…";
        text(canvas, title, x + dp(43), y + dp(23), 27, INK,
                Paint.Align.LEFT, Typeface.BOLD);
        text(canvas, reading.countryName, x + dp(43), y + dp(51), 16, MUTED,
                Paint.Align.LEFT, Typeface.NORMAL);
        text(canvas, measuredTime, x + dp(43), y + dp(77),
                14, MUTED, Paint.Align.LEFT, Typeface.NORMAL);

        updateBounds.set(x + width - dp(50), y, x + width, y + dp(50));
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(dp(7), 0, dp(2), 0x18000000);
        canvas.drawCircle(updateBounds.centerX(), updateBounds.centerY(), dp(23), paint);
        paint.clearShadowLayer();
        drawRefreshGlyph(canvas, updateBounds.centerX(), updateBounds.centerY(), dp(11), MUTED,
                refreshing ? (SystemClock.uptimeMillis() % 1000) * 0.36f : 0);
    }

    private void drawUsCard(Canvas canvas, float x, float y, float width, float height) {
        usBounds.set(x, y, x + width, y + height);
        int color = AqiCalculator.usColor(reading.usAqi);
        rounded(canvas, x, y, x + width, y + height, dp(24), color);
        int foreground = reading.usAqi >= 151 ? Color.WHITE : INK;
        text(canvas, "›", x + width - dp(18), y + dp(30), 20, foreground, Paint.Align.RIGHT, Typeface.NORMAL);
        boolean compact = width < dp(330);
        float scoreRight = x + dp(compact ? 104 : 119);
        float scoreCenter = (x + dp(18) + scoreRight) / 2f;

        rounded(canvas, x + dp(18), y + dp(18), scoreRight, y + dp(127),
                dp(18), darken(color, 0.90f));
        text(canvas, Integer.toString(reading.usAqi), scoreCenter, y + dp(71),
                compact ? 35 : 38, foreground, Paint.Align.CENTER, Typeface.BOLD);
        text(canvas, "US AQI+", scoreCenter, y + dp(103),
                compact ? 12 : 14, foreground, Paint.Align.CENTER, Typeface.NORMAL);

        float levelX = x + dp(compact ? 118 : 140);
        float levelSize = compact ? 19 : 24;
        if ("Unhealthy for Sensitive Groups".equals(reading.usLevel)) {
            text(canvas, "Unhealthy for", levelX, y + dp(58), levelSize, foreground,
                    Paint.Align.LEFT, Typeface.BOLD);
            text(canvas, "Sensitive Groups", levelX, y + dp(91), levelSize, foreground,
                    Paint.Align.LEFT, Typeface.BOLD);
        } else if (reading.usLevel.length() > 16) {
            int split = reading.usLevel.lastIndexOf(' ', 16);
            if (split < 1) split = reading.usLevel.length();
            text(canvas, reading.usLevel.substring(0, split), levelX, y + dp(60),
                    25, foreground, Paint.Align.LEFT, Typeface.BOLD);
            if (split < reading.usLevel.length()) text(canvas,
                    reading.usLevel.substring(split + 1), levelX, y + dp(94),
                    25, foreground, Paint.Align.LEFT, Typeface.BOLD);
        } else {
            text(canvas, reading.usLevel, levelX, y + dp(78), 27, foreground,
                    Paint.Align.LEFT, Typeface.BOLD);
        }

        paint.setColor(withAlpha(foreground, 50));
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(x + dp(18), y + dp(148), x + width - dp(18), y + dp(148), paint);
        text(canvas, "Main pollutant", x + dp(20), y + dp(174), 13, foreground,
                Paint.Align.LEFT, Typeface.NORMAL);
        text(canvas, reading.mainPollutant, x + dp(20), y + dp(204), 19, foreground,
                Paint.Align.LEFT, Typeface.BOLD);
        text(canvas, reading.pollutantValue(reading.mainPollutant), x + width - dp(20),
                y + dp(204), compact ? 15 : 18, foreground, Paint.Align.RIGHT, Typeface.BOLD);
    }

    private void drawEuCard(Canvas canvas, float x, float y, float width, float height) {
        euBounds.set(x, y, x + width, y + height);
        int color = AqiCalculator.euColor(reading.euBand);
        rounded(canvas, x, y, x + width, y + height, dp(22), color);
        int foreground = reading.euBand >= 4 ? Color.WHITE : INK;
        text(canvas, "›", x + width - dp(18), y + dp(30), 20, foreground, Paint.Align.RIGHT, Typeface.NORMAL);
        rounded(canvas, x + dp(16), y + dp(15), x + dp(92), y + height - dp(15),
                dp(16), darken(color, 0.90f));
        text(canvas, Integer.toString(reading.euBand), x + dp(54), y + dp(62), 33,
                foreground, Paint.Align.CENTER, Typeface.BOLD);
        text(canvas, "of 6", x + dp(54), y + dp(88), 13,
                foreground, Paint.Align.CENTER, Typeface.NORMAL);
        text(canvas, reading.euLevel, x + dp(112), y + dp(55), 26,
                foreground, Paint.Align.LEFT, Typeface.BOLD);
        text(canvas, "European AQI", x + dp(112), y + dp(84), 15,
                foreground, Paint.Align.LEFT, Typeface.NORMAL);
        int count = reading.stationId.startsWith("nea:") ? (AirQualityReading.isPresent(reading.pm25) ? 1 : 0)
                : reading.availablePollutants() - (AirQualityReading.isPresent(reading.co) ? 1 : 0);
        if (count < 5) text(canvas, "Partial · " + count + "/5 pollutants", x + dp(112), y + dp(109), 12,
                foreground, Paint.Align.LEFT, Typeface.NORMAL);
    }

    private void drawStationCard(Canvas canvas, float x, float y, float width, float height) {
        roundedShadow(canvas, x, y, x + width, y + height, dp(20), Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1A177A68);
        canvas.drawCircle(x + dp(39), y + dp(51), dp(24), paint);
        drawStationGlyph(canvas, x + dp(39), y + dp(51));
        String station = reading.stationName;
        if (station.length() > 26) station = station.substring(0, 25) + "…";
        text(canvas, station, x + dp(78), y + dp(38), 16,
                INK, Paint.Align.LEFT, Typeface.BOLD);
        String source = reading.sourceName;
        if (source.length() > 28) source = source.substring(0, 27) + "…";
        text(canvas, source, x + dp(78), y + dp(65), 12,
                MUTED, Paint.Align.LEFT, Typeface.NORMAL);
        text(canvas, String.format(Locale.getDefault(), "%.1f km", reading.distanceKm),
                x + width - dp(18), y + dp(88), 12, MUTED, Paint.Align.RIGHT, Typeface.NORMAL);
    }

    private float drawPollutants(Canvas canvas, float x, float y, float width) {
        float gap = dp(10);
        float containerHeight = dp(455);
        roundedShadow(canvas, x, y, x + width, y + containerHeight, dp(22), Color.WHITE);
        text(canvas, "Air pollutants", x + dp(20), y + dp(39), 20,
                INK, Paint.Align.LEFT, Typeface.BOLD);
        float tileWidth = (width - dp(50)) / 2;
        float tileHeight = dp(116);
        String[] labels = {"PM2.5", "PM10", "O3", "NO2", "CO", "SO2"};
        String[] names = {"Fine particles", "Coarse particles", "Ozone",
                "Nitrogen dioxide", "Carbon monoxide", "Sulphur dioxide"};
        for (int i = 0; i < labels.length; i++) {
            int column = i % 2;
            int row = i / 2;
            float left = x + dp(20) + column * (tileWidth + gap);
            float top = y + dp(59) + row * (tileHeight + gap);
            pollutantBounds[i].set(left, top, left + tileWidth, top + tileHeight);
            drawPollutantTile(canvas, left, top, tileWidth, tileHeight, labels[i], names[i]);
        }
        return y + containerHeight;
    }

    private void drawPollutantTile(Canvas canvas, float x, float y, float width, float height,
                                   String label, String name) {
        rounded(canvas, x, y, x + width, y + height, dp(17), TILE);
        text(canvas, label, x + dp(14), y + dp(30), 18, INK,
                Paint.Align.LEFT, Typeface.NORMAL);
        text(canvas, name, x + dp(14), y + dp(53), 12.5f, MUTED,
                Paint.Align.LEFT, Typeface.NORMAL);
        text(canvas, "›", x + width - dp(12), y + dp(30), 20, MUTED, Paint.Align.RIGHT, Typeface.NORMAL);
        double value = pollutantValue(label);
        HistorySeries.Metric metric = HistorySeries.Metric.forLabel(label);
        int band = HistorySeries.band(metric, value);
        paint.setColor(HistorySeries.color(metric, band));
        canvas.drawCircle(x + dp(18), y + dp(88), dp(5.5f), paint);
        text(canvas, reading.pollutantValue(label), x + dp(31), y + dp(94), 15,
                INK, Paint.Align.LEFT, Typeface.BOLD);
    }

    private double pollutantValue(String label) {
        return switch (label) {
            case "PM2.5" -> reading.pm25;
            case "PM10" -> reading.pm10;
            case "O3" -> reading.o3;
            case "NO2" -> reading.no2;
            case "CO" -> reading.co;
            case "SO2" -> reading.so2;
            default -> Double.NaN;
        };
    }

    private float drawHealthAdvice(Canvas canvas, float x, float y, float width) {
        int bodyWidth = Math.max(1, (int) (width - dp(40)));
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
            adviceLayout = StaticLayout.Builder.obtain(body, 0, body.length(), advicePaint, bodyWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).setLineSpacing(dp(3), 1f).build();
            adviceWidth = bodyWidth;
            adviceCheckedAt = now;
            setContentDescription(reading.locationName + ", US AQI " + reading.usAqi + ", "
                    + reading.usLevel + ", European AQI " + reading.euBand + ", " + reading.euLevel
                    + nowcastDescription() + ". " + body);
        }
        float height = adviceLayout.getHeight() + dp(40);
        roundedShadow(canvas, x, y, x + width, y + height, dp(22), Color.WHITE);
        int accent = advice.sensitive().isEmpty() ? MUTED : advice.caution() == 0 ? 0xFF177A68
                : advice.caution() <= 2 ? 0xFFC57819 : 0xFFC44050;
        rounded(canvas, x + dp(1), y + dp(22), x + dp(5), y + height - dp(22), dp(2), accent);
        canvas.save();
        canvas.translate(x + dp(20), y + dp(20));
        adviceLayout.draw(canvas);
        canvas.restore();
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
    private String nowcastDescription() {
        return reading.nowcast == null ? ", US NowCast not enough data" : ", US NowCast " + reading.nowcast.score()
                + ", " + reading.nowcast.level() + ", " + reading.nowcast.status();
    }

    private void drawEmptyHeader(Canvas canvas) {
        text(canvas, "Local Air Quality", dp(22), dp(52), 28, INK,
                Paint.Align.LEFT, Typeface.BOLD);
        contentHeight = getHeight();
    }

    private void drawStateOverlay(Canvas canvas) {
        if (stateMessage == null) return;
        float width = Math.min(getWidth() - dp(36), dp(390));
        float left = (getWidth() - width) / 2;
        float top = reading == null ? Math.max(dp(120), getHeight() * 0.34f) : dp(102);
        float bottom = top + dp(116);
        roundedShadow(canvas, left, top, left + width, bottom, dp(20), Color.WHITE);
        text(canvas, stateMessage, left + width / 2, top + dp(42), 18, INK,
                Paint.Align.CENTER, Typeface.BOLD);
        if (actionLabel != null) {
            float actionWidth = Math.min(dp(220), width - dp(48));
            actionBounds.set(left + (width - actionWidth) / 2, top + dp(60),
                    left + (width + actionWidth) / 2, top + dp(103));
            rounded(canvas, actionBounds.left, actionBounds.top, actionBounds.right,
                    actionBounds.bottom, dp(14), 0xFF177A68);
            text(canvas, actionLabel, actionBounds.centerX(), actionBounds.centerY() + dp(6),
                    15, Color.WHITE, Paint.Align.CENTER, Typeface.BOLD);
        }
    }

    private void drawRefreshIndicator(Canvas canvas) {
        float centerY = dp(16) + Math.min(dp(25), pullDistance * 0.25f);
        float rotation = refreshing ? (SystemClock.uptimeMillis() % 1000) * 0.36f
                : Math.min(270, pullDistance * 2);
        drawRefreshGlyph(canvas, getWidth() / 2f, centerY, dp(9), 0xFF177A68, rotation);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downY = lastY = event.getY();
                moved = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                float y = event.getY();
                float dy = lastY - y;
                if (Math.abs(y - downY) > dp(5)) moved = true;
                if (scrollOffset <= 0 && y > downY) {
                    pullDistance = Math.min(dp(130), (y - downY) * 0.7f);
                } else {
                    pullDistance = 0;
                    scrollOffset = clamp(scrollOffset + dy, 0,
                            Math.max(0, contentHeight - getHeight()));
                }
                lastY = y;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                pullDistance = 0;
                moved = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                float x = event.getX();
                float y = event.getY();
                if (pullDistance >= dp(72) && !refreshing && listener != null) {
                    listener.onRefreshRequested();
                } else if (!moved && updateBounds.contains(x, y + scrollOffset)
                        && !refreshing && listener != null) {
                    listener.onRefreshRequested();
                } else if (!moved && actionBounds.contains(x, y) && listener != null && action != null) {
                    listener.onActionRequested(action);
                } else if (!moved && stateMessage == null && reading != null && listener != null) {
                    if (nowcastBounds.contains(x, y + scrollOffset)) listener.onPollutantSelected("US NowCast");
                    else if (usBounds.contains(x, y + scrollOffset)) listener.onPollutantSelected("US AQI+");
                    else if (euBounds.contains(x, y + scrollOffset)) listener.onPollutantSelected("European AQI");
                    for (int i = 0; i < pollutantBounds.length; i++) {
                        if (pollutantBounds[i].contains(x, y + scrollOffset)) {
                            listener.onPollutantSelected(com.localairquality.app.data.PollutantHistory.LABELS[i]);
                            break;
                        }
                    }
                }
                if (!moved) performClick();
                pullDistance = 0;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (reading == null) return;
        String[] labels = {"US AQI+", "European AQI", "PM2.5", "PM10", "O3", "NO2", "CO", "SO2", "US NowCast"};
        for (int i = 0; i < labels.length; i++) {
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    0x01000000 + i, "Open " + labels[i] + " history"));
        }
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        String[] labels = {"US AQI+", "European AQI", "PM2.5", "PM10", "O3", "NO2", "CO", "SO2", "US NowCast"};
        int index = action - 0x01000000;
        if (reading != null && listener != null && index >= 0 && index < labels.length) {
            listener.onPollutantSelected(labels[index]);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
    private void drawLocationArrow(Canvas canvas, float cx, float cy) {
        Path path = new Path();
        path.moveTo(cx - dp(12), cy - dp(7));
        path.lineTo(cx + dp(14), cy - dp(15));
        path.lineTo(cx + dp(5), cy + dp(13));
        path.lineTo(cx, cy + dp(2));
        path.close();
        paint.setColor(0xFF627184);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStationGlyph(Canvas canvas, float cx, float cy) {
        paint.setColor(0xFF177A68);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        canvas.drawCircle(cx, cy, dp(5), paint);
        canvas.drawLine(cx, cy + dp(5), cx, cy + dp(13), paint);
        canvas.drawLine(cx - dp(10), cy + dp(13), cx + dp(10), cy + dp(13), paint);
        canvas.drawArc(new RectF(cx - dp(12), cy - dp(12), cx + dp(12), cy + dp(12)),
                205, 130, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawRefreshGlyph(Canvas canvas, float cx, float cy, float radius,
                                  int color, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.3f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(oval, -50, 285, false, paint);
        paint.setStyle(Paint.Style.FILL);
        Path arrow = new Path();
        arrow.moveTo(cx + radius - dp(1), cy - dp(5));
        arrow.lineTo(cx + radius + dp(5), cy - dp(4));
        arrow.lineTo(cx + radius + dp(1), cy + dp(1));
        arrow.close();
        canvas.drawPath(arrow, paint);
        canvas.restore();
    }

    private void rounded(Canvas canvas, float left, float top, float right, float bottom,
                         float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }

    private void roundedShadow(Canvas canvas, float left, float top, float right, float bottom,
                               float radius, int color) {
        paint.setShadowLayer(dp(9), 0, dp(3), 0x16000000);
        rounded(canvas, left, top, right, bottom, radius, color);
        paint.clearShadowLayer();
    }

    private void text(Canvas canvas, String value, float x, float baseline, float sizeSp,
                      int color, Paint.Align align, int style) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextAlign(align);
        paint.setTextSize(sizeSp * scaledDensity);
        paint.setTypeface(style == Typeface.BOLD ? BOLD_FONT : NORMAL_FONT);
        canvas.drawText(value == null ? "" : value, x, baseline, paint);
    }

    private String formatDateTime(long millis) {
        if (millis <= 0) return "—";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(millis));
    }

    private int darken(int color, float factor) {
        return Color.rgb((int) (Color.red(color) * factor),
                (int) (Color.green(color) * factor),
                (int) (Color.blue(color) * factor));
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * density;
    }
}



