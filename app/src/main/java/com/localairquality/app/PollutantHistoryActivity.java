package com.localairquality.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.localairquality.app.data.*;
import java.text.DateFormat;
import java.util.*;

public final class PollutantHistoryActivity extends Activity {
    private final Handler handler = new Handler();
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 60_000); }
    };
    private HistorySeries.Metric metric;
    private LinearLayout content;
    private LinearLayout calculationInputs;
    private static final int INK = 0xFF1F2834, MUTED = 0xFF5B6777, GREEN = 0xFF177A68;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String label = getIntent().getStringExtra("pollutant");
        metric = HistorySeries.Metric.forLabel(label);
        if (metric == null) { finish(); return; }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF4F7FA);
        root.setPadding(dp(18), 0, dp(18), 0);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(18), insets.getSystemWindowInsetTop(), dp(18), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    @Override protected void onResume() { super.onResume(); if (content != null) update.run(); }
    @Override protected void onPause() { handler.removeCallbacks(update); super.onPause(); }

    private void render() {
        com.localairquality.app.background.RefreshCoordinator.refreshIfStale(this);
        PollutantHistory history = HistoryStore.load(this);
        long now = System.currentTimeMillis();
        content.removeAllViews();
        addText(content, metric.label + " history", 28, INK, true);
        addText(content, "Past 24 hours · " + unit(), 16, MUTED, false);
        if (!history.stationName.isEmpty()) {
            addText(content, history.countryName + "\nCurrent station: " + history.stationName, 16, INK, true);
        }
        List<HistorySeries.Point> available = HistorySeries.points(history, metric);
        calculationInputs = null;
        if (available.isEmpty()) {
            addText(content, "No recorded readings yet", 20, INK, true);
            addText(content, metric == HistorySeries.Metric.US_NOWCAST
                    ? "Waiting for enough hourly data."
                    : "Readings will appear after a successful air quality update with this pollutant available.", 16, MUTED, false);
            if (metric == HistorySeries.Metric.US_NOWCAST) {
                var latest = history.latestNowcast(history.activeStationId);
                if (latest != null) {
                    LinearLayout inputs = new LinearLayout(this); inputs.setOrientation(LinearLayout.VERTICAL);
                    content.addView(inputs);
                    showInputs(inputs, new HistorySeries.Point(latest.measuredAt(),latest.stationName(),Double.NaN,0,"—",Double.NaN,latest.inputs()));
                }
            }
            return;
        }
        long start = available.get(0).measuredAt();
        addText(content, date(start) + " – " + date(now), 14, MUTED, false);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE); background.setCornerRadius(dp(20));
        card.setBackground(background);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        HistorySeries.Point latest = available.get(available.size() - 1);
        addText(card, value(latest.value()) + " " + unit(), 26, GREEN, true);
        addText(card, "Tap a point to view its reading", 14, MUTED, false);
        card.addView(new Chart(available, start, now), new LinearLayout.LayoutParams(-1, dp(220)));
        String standard = metric == HistorySeries.Metric.CO ? "US AQI"
                : metric.usesUsLevels() ? metric.label : "European AQI";
        addText(card, "Pollution level · " + standard, 14, MUTED, true);
        for (int band = 1; band <= 6; band += 2) {
            LinearLayout row = new LinearLayout(this);
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            addLevel(row, band);
            addLevel(row, band + 1);
        }
        if (metric == HistorySeries.Metric.US_NOWCAST || metric == HistorySeries.Metric.EUROPEAN_AQI) {
            calculationInputs = new LinearLayout(this);
            calculationInputs.setOrientation(LinearLayout.VERTICAL);
            content.addView(calculationInputs, new LinearLayout.LayoutParams(-1, -2));
            showInputs(calculationInputs, latest);
        }
    }
    private void showInputs(LinearLayout parent, HistorySeries.Point point) {
        parent.removeAllViews();
        addText(parent, "Calculation inputs", 21, INK, true);
        addText(parent, date(point.measuredAt()) + " · " + point.stationName(), 14, MUTED, false);
        LinearLayout row = null;
        int column = 0;
        for (int p = 0; p < point.inputs().size(); p++) {
            if (metric == HistorySeries.Metric.EUROPEAN_AQI && p == 4) continue;
            if (column++ % 2 == 0) {
                row = new LinearLayout(this);
                row.setBaselineAligned(false);
                parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            var input = point.inputs().get(p);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(12), dp(8), dp(10), dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xFFF0F3F5); background.setCornerRadius(dp(16)); tile.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(3), dp(4), dp(3), dp(4)); row.addView(tile, params);
            addText(tile, PollutantHistory.LABELS[p], 18, INK, false);
            if (!input.hourlyNotSupplied()) addText(tile, input.method(), 12, MUTED, false);
            boolean valid = metric == HistorySeries.Metric.US_NOWCAST ? input.aqi() >= 0 : input.aqi() > 0;
            int color = valid ? (metric == HistorySeries.Metric.US_NOWCAST ? AqiCalculator.usColor(input.aqi())
                    : AqiCalculator.euColor(input.aqi())) : AqiCalculator.euColor(0);
            TextView value = new TextView(this);
            android.text.SpannableString label = new android.text.SpannableString("● "
                    + AirQualityReading.formatConcentration(PollutantHistory.LABELS[p], valid ? input.concentration() : Double.NaN));
            label.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
            value.setText(label); value.setTextSize(15); value.setTextColor(INK); value.setTypeface(null, Typeface.BOLD);
            tile.addView(value);
            if (!valid) addText(tile, input.unavailableReason(), 12, MUTED, false);
            else if (metric == HistorySeries.Metric.US_NOWCAST) addText(tile, input.hours() + " valid hour(s)", 12, MUTED, false);
        }
    }
    private int band(double concentration) {
        return HistorySeries.band(metric, concentration);
    }
    private void addLevel(LinearLayout parent, int band) {
        TextView label = new TextView(this);
        android.text.SpannableString text = new android.text.SpannableString("●  " + HistorySeries.level(metric, band));
        text.setSpan(new android.text.style.ForegroundColorSpan(HistorySeries.color(metric, band)), 0, 1, 0);
        label.setText(text); label.setTextColor(INK); label.setTextSize(14);
        label.setPadding(0, dp(4), dp(6), dp(4));
        parent.addView(label, parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
    }
    private String unit() { return metric.isIndex() ? (metric.usesUsLevels() ? "AQI" : "of 6") : metric == HistorySeries.Metric.CO ? "mg/m³" : "µg/m³"; }
    private double display(double value) { return metric == HistorySeries.Metric.CO ? value / 1000 : value; }
    private String value(double value) { return String.format(Locale.getDefault(), metric.isIndex() ? "%.0f" : "%.1f", display(value)); }
    private String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void addText(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(6), 0, dp(6));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    private void showPoint(HistorySeries.Point sample) {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(24), dp(8), dp(24), dp(16));
        addText(details, value(sample.value()) + " " + unit(), 26, INK, true);
        addText(details, "Reported " + date(sample.measuredAt()), 16, MUTED, false);
        if (metric == HistorySeries.Metric.CO) addText(details, "Pollution level · US AQI", 14, MUTED, true);
        addLevel(details, band(sample.value()));
        addText(details, sample.stationName(), 16, MUTED, false);
        if (metric.isIndex()) {
            int total = metric.usesUsLevels() ? 6 : 5;
            addText(details, (sample.availablePollutants() < total ? "Partial data · " : "")
                    + sample.availablePollutants() + " of " + total + " pollutants reported at this time", 14, MUTED, false);
            addText(details, "Main pollutant: " + sample.mainPollutant() + "\n"
                    + AirQualityReading.formatConcentration(sample.mainPollutant(), sample.mainConcentration()),
                    16, INK, true);
        }
        if (calculationInputs != null && !sample.inputs().isEmpty()) showInputs(calculationInputs, sample);
        if (metric == HistorySeries.Metric.US_NOWCAST || metric == HistorySeries.Metric.EUROPEAN_AQI) {
            LinearLayout tiles = new LinearLayout(this);
            tiles.setOrientation(LinearLayout.VERTICAL);
            details.addView(tiles);
            showInputs(tiles, sample);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        new AlertDialog.Builder(this)
                .setTitle(metric.label)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private final class Chart extends View {
        private static final int POINT_ACTION_BASE = 0x01000000;
        private final List<HistorySeries.Point> samples;
        private final long start, end;
        private final String startLabel, endLabel;
        private final String[] axisLabels = new String[3];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final double max;
        private final int touchSlop;
        private int selected = -1, pressed = -1;
        private float downX, downY;
        private boolean moved;

        Chart(List<HistorySeries.Point> samples, long start, long end) {
            super(PollutantHistoryActivity.this);
            this.samples = samples; this.start = start; this.end = Math.max(start + 1, end);
            DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
            startLabel = time.format(new Date(start));
            endLabel = time.format(new Date(end));
            double highest = metric == HistorySeries.Metric.EUROPEAN_AQI ? 6 : 1;
            for (HistorySeries.Point sample : samples) {
                if (metric != HistorySeries.Metric.EUROPEAN_AQI) highest = Math.max(highest, display(sample.value()) * 1.15);
            }
            max = highest;
            for (int i = 0; i < axisLabels.length; i++) {
                axisLabels[i] = String.format(Locale.getDefault(), metric.isIndex() ? "%.0f" : "%.1f", max * i / 2);
            }
            touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            setClickable(true);
            setFocusable(true);
            setContentDescription("Concentration chart. Tap a point to view its concentration, reported time, and pollution level. "
                    + "Activate to view the latest reading, or choose a report from accessibility actions.");
        }
        private float pointX(HistorySeries.Point sample) {
            float left = dp(48), right = getWidth() - dp(10);
            return left + (right - left) * (sample.measuredAt() - start) / (end - start);
        }
        private float pointY(HistorySeries.Point sample) {
            float top = dp(15), bottom = getHeight() - dp(32);
            return bottom - (float)(display(sample.value()) / max) * (bottom - top);
        }
        private int pointAt(float x, float y) {
            // Expand tiny dots to a finger-sized target; choose the nearest actual point.
            double nearest = (double) dp(24) * dp(24);
            int index = -1;
            for (int i = 0; i < samples.size(); i++) {
                double dx = x - pointX(samples.get(i)), dy = y - pointY(samples.get(i));
                double distance = dx * dx + dy * dy;
                if (distance <= nearest) { nearest = distance; index = i; }
            }
            return index;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    pressed = pointAt(event.getX(), event.getY());
                    if (pressed < 0) return false;
                    downX = event.getX(); downY = event.getY(); moved = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) moved = true;
                    return pressed >= 0;
                }
                case MotionEvent.ACTION_UP -> {
                    if (!moved && pressed >= 0 && pointAt(event.getX(), event.getY()) == pressed
                            && Math.hypot(event.getX() - downX, event.getY() - downY) <= touchSlop) {
                        selected = pressed;
                        performClick();
                    }
                    pressed = -1;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> { pressed = -1; moved = true; return true; }
            }
            return super.onTouchEvent(event);
        }
        @Override public boolean performClick() {
            super.performClick();
            if (selected < 0) selected = samples.size() - 1;
            if (selected >= 0) { invalidate(); showPoint(samples.get(selected)); }
            return true;
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            for (int i = 0; i < samples.size(); i++) {
                HistorySeries.Point sample = samples.get(i);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(POINT_ACTION_BASE + i,
                        date(sample.measuredAt()) + ", " + value(sample.value()) + " " + unit()
                                + ", " + HistorySeries.level(metric, band(sample.value()))
                                + ", " + sample.stationName()));
            }
        }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            int index = action - POINT_ACTION_BASE;
            if (index >= 0 && index < samples.size()) { selected = index; return performClick(); }
            return super.performAccessibilityAction(action, arguments);
        }
        @Override protected void onDraw(Canvas canvas) {
            float left = dp(48), right = getWidth() - dp(10), top = dp(15), bottom = getHeight() - dp(32);
            paint.setTextSize(dp(11)); paint.setStrokeWidth(dp(1));
            for (int i = 0; i < 3; i++) {
                float y = bottom - (bottom - top) * i / 2;
                paint.setColor(0xFFE3E9EC); canvas.drawLine(left, y, right, y, paint);
                paint.setColor(MUTED); paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(axisLabels[i], left - dp(6), y + dp(4), paint);
            }
            paint.setColor(MUTED); paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(startLabel, left, bottom + dp(23), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(endLabel, right, bottom + dp(23), paint);
            for (int i = 0; i < samples.size(); i++) {
                HistorySeries.Point sample = samples.get(i);
                float x = pointX(sample), y = pointY(sample);
                if (i == selected) {
                    paint.setColor(0x33177A68);
                    canvas.drawCircle(x, y, dp(10), paint);
                }
                paint.setColor(0xFF627184);
                canvas.drawCircle(x, y, dp(5), paint);
                paint.setColor(HistorySeries.color(metric, band(sample.value())));
                canvas.drawCircle(x, y, dp(4), paint);
            }
        }
    }
}


