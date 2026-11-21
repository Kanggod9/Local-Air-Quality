package com.localairquality.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AqiCalculator;
import com.localairquality.app.data.HistorySeries;
import com.localairquality.app.data.HistoryStore;
import com.localairquality.app.data.NowCast;
import com.localairquality.app.data.PollutantHistory;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.ui.Palette;
import com.localairquality.app.ui.SystemBars;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class PollutantHistoryActivity extends Activity {
    private static final int BACKUP_RED = 0xFFD32F2F;
    /** Points further apart than this are not joined: the line must not imply missing reports. */
    private static final long LINE_GAP = 150L * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 60_000); }
    };
    private HistorySeries.Metric metric;
    private Palette palette;
    private LinearLayout content;
    private LinearLayout calculationInputs;
    private boolean resumed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        metric = HistorySeries.Metric.forLabel(getIntent().getStringExtra("pollutant"));
        if (metric == null) { finish(); return; }
        palette = Palette.of(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.background);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = SystemBars.insets(insets);
            view.setPadding(bars[0] + dp(16), bars[1], bars[2] + dp(16), bars[3]);
            return insets;
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(14), 0, dp(28));
        scroll.addView(content);
        setContentView(scroll);
        SystemBars.edgeToEdge(this, palette);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (content != null) update.run();
        com.localairquality.app.background.HistoryRecoveryCoordinator.recover(this)
                .whenComplete((complete, error) -> handler.post(() -> {
                    if (resumed && content != null) render();
                }));
    }

    @Override protected void onPause() { resumed = false; handler.removeCallbacks(update); super.onPause(); }

    private void render() {
        com.localairquality.app.background.RefreshCoordinator.refreshIfStale(this);
        PollutantHistory history = HistoryStore.load(this);
        HistorySeries.ChartSource source = HistorySeries.chartSource(history, ReadingStore.loadReading(this));
        long now = System.currentTimeMillis();
        content.removeAllViews();
        calculationInputs = null;

        content.addView(text(title(), 26, palette.ink, true));
        TextView subtitle = text("Station history · Past 24 hours · " + unit(), 14, palette.muted, false);
        subtitle.setPadding(0, dp(2), 0, 0);
        content.addView(subtitle);
        if (!source.stationName().isEmpty()) {
            LinearLayout station = card(palette.surface, true);
            station.setPadding(dp(16), dp(12), dp(16), dp(12));
            if (!history.countryName.isBlank()) station.addView(text(history.countryName, 12.5f, palette.muted, true));
            station.addView(text(source.stationName(), 15, palette.ink, true));
            addBlock(station, 14);
        }

        List<HistorySeries.Point> available = HistorySeries.chartPoints(history, metric, source);
        if (available.isEmpty()) {
            LinearLayout empty = card(palette.surface, true);
            empty.addView(text("No recorded readings yet", 18, palette.ink, true));
            TextView reason = text(metric == HistorySeries.Metric.US_NOWCAST
                    ? "Waiting for enough hourly data."
                    : "Readings will appear after a successful air quality update with this pollutant available.",
                    14.5f, palette.muted, false);
            reason.setPadding(0, dp(6), 0, 0);
            empty.addView(reason);
            addBlock(empty, 12);
            if (metric == HistorySeries.Metric.US_NOWCAST) {
                var latest = history.latestNowcast(source.stationId());
                if (latest != null) {
                    LinearLayout inputs = new LinearLayout(this);
                    inputs.setOrientation(LinearLayout.VERTICAL);
                    addBlock(inputs, 18);
                    showInputs(inputs, new HistorySeries.Point(latest.measuredAt(),
                            latest.stationName() + (source.backup() ? " (OpenAQ, backup)" : ""),
                            Double.NaN, 0, "—", Double.NaN, latest.inputs(), source.backup()));
                }
            }
            return;
        }

        long start = available.get(0).measuredAt();
        HistorySeries.Point latest = available.get(available.size() - 1);
        addBlock(summary(available, latest), 12);

        LinearLayout chart = card(palette.surface, true);
        chart.addView(text("Reported readings", 16, palette.ink, true));
        TextView range = text(date(start) + " – " + date(now), 12.5f, palette.muted, false);
        range.setPadding(0, dp(2), 0, dp(6));
        chart.addView(range);
        chart.addView(new Chart(available, start, now), new LinearLayout.LayoutParams(-1, dp(232)));
        TextView hint = text("Tap a point to view its reading", 12.5f, palette.muted, false);
        hint.setPadding(0, dp(6), 0, 0);
        chart.addView(hint);
        if (available.stream().anyMatch(HistorySeries.Point::backup)) {
            TextView backup = text("○ Red outline · OpenAQ backup", 12.5f, palette.dark ? 0xFFFF8A80 : BACKUP_RED, false);
            backup.setPadding(0, dp(4), 0, 0);
            chart.addView(backup);
        }
        addBlock(chart, 12);

        LinearLayout levels = card(palette.surface, true);
        String standard = metric == HistorySeries.Metric.CO ? "US AQI"
                : metric.usesUsLevels() ? metric.label : "European AQI";
        TextView heading = text("Pollution level · " + standard, 13, palette.muted, true);
        heading.setPadding(0, 0, 0, dp(6));
        levels.addView(heading);
        for (int band = 1; band <= 6; band += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);
            levels.addView(row, new LinearLayout.LayoutParams(-1, -2));
            addLevel(row, band);
            addLevel(row, band + 1);
        }
        addBlock(levels, 12);

        if (metric == HistorySeries.Metric.US_NOWCAST) {
            calculationInputs = new LinearLayout(this);
            calculationInputs.setOrientation(LinearLayout.VERTICAL);
            addBlock(calculationInputs, 20);
            showInputs(calculationInputs, latest);
        }
    }

    /** Latest value with its level, and the lowest, mean and highest report of the period. */
    private LinearLayout summary(List<HistorySeries.Point> points, HistorySeries.Point latest) {
        int band = band(latest.value());
        int color = HistorySeries.color(metric, band);
        LinearLayout card = card(palette.tint(color, palette.dark ? 0.16f : 0.14f), false);
        card.addView(text("Latest · " + date(latest.measuredAt()), 12.5f, palette.muted, true));

        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        headline.setPadding(0, dp(4), 0, dp(12));
        TextView value = text(value(latest.value()), 38, palette.ink, true);
        headline.addView(value);
        TextView units = text(" " + unit(), 15, palette.muted, false);
        units.setPadding(dp(4), dp(12), dp(12), 0);
        headline.addView(units);
        TextView level = text(HistorySeries.level(metric, band), 13.5f, Palette.on(color), true);
        level.setPadding(dp(12), dp(6), dp(12), dp(6));
        level.setBackground(shape(color, 100, false));
        headline.addView(level, new LinearLayout.LayoutParams(-2, -2));
        card.addView(headline);

        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE, total = 0;
        for (HistorySeries.Point point : points) {
            low = Math.min(low, point.value());
            high = Math.max(high, point.value());
            total += point.value();
        }
        LinearLayout stats = new LinearLayout(this);
        stats.setBaselineAligned(false);
        addStat(stats, "Lowest", value(low));
        addStat(stats, "Average", String.format(Locale.getDefault(),
                metric == HistorySeries.Metric.EUROPEAN_AQI || !metric.isIndex() ? "%.1f" : "%.0f",
                display(total / points.size())));
        addStat(stats, "Highest", value(high));
        card.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void addStat(LinearLayout row, String label, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.addView(text(label, 12, palette.muted, false));
        cell.addView(text(value, 17, palette.ink, true));
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void showInputs(LinearLayout parent, HistorySeries.Point point) {
        parent.removeAllViews();
        parent.addView(text("Calculation inputs", 19, palette.ink, true));
        TextView when = text(date(point.measuredAt()) + " · " + point.stationName(), 13, palette.muted, false);
        when.setPadding(0, dp(2), 0, dp(8));
        parent.addView(when);
        LinearLayout row = null;
        int column = 0;
        for (int p = 0; p < point.inputs().size(); p++) {
            if (metric == HistorySeries.Metric.EUROPEAN_AQI && p == 4) continue;
            if (column++ % 2 == 0) {
                row = new LinearLayout(this);
                row.setBaselineAligned(false);
                parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            NowCast.Input input = point.inputs().get(p);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(14), dp(12), dp(12), dp(12));
            tile.setBackground(shape(palette.surface, 18, true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            params.setMargins(column % 2 == 1 ? 0 : dp(5), dp(5), column % 2 == 1 ? dp(5) : 0, dp(5));
            row.addView(tile, params);
            tile.addView(text(symbol(PollutantHistory.LABELS[p]), 16, palette.ink, true));
            if (!input.hourlyNotSupplied()) tile.addView(text(input.method(), 12, palette.muted, false));
            boolean valid = metric == HistorySeries.Metric.US_NOWCAST ? input.aqi() >= 0 : input.aqi() > 0;
            int color = valid ? (metric == HistorySeries.Metric.US_NOWCAST ? AqiCalculator.usColor(input.aqi())
                    : AqiCalculator.euColor(input.aqi())) : AqiCalculator.euColor(0);
            TextView value = text("", 15, palette.ink, true);
            SpannableString label = new SpannableString("● "
                    + AirQualityReading.formatConcentration(PollutantHistory.LABELS[p], valid ? input.concentration() : Double.NaN));
            label.setSpan(new ForegroundColorSpan(color), 0, 1, 0);
            value.setText(label);
            value.setPadding(0, dp(6), 0, dp(2));
            tile.addView(value);
            if (!valid) tile.addView(text(input.unavailableReason(), 12, palette.muted, false));
            else if (metric == HistorySeries.Metric.US_NOWCAST) tile.addView(text(input.hours() + " valid hour(s)", 12, palette.muted, false));
        }
        if (row != null && column % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
    }

    private int band(double concentration) {
        return HistorySeries.band(metric, concentration);
    }

    private void addLevel(LinearLayout parent, int band) {
        TextView label = new TextView(this);
        SpannableString text = new SpannableString("●  " + HistorySeries.level(metric, band));
        text.setSpan(new ForegroundColorSpan(HistorySeries.color(metric, band)), 0, 1, 0);
        label.setText(text); label.setTextColor(palette.ink); label.setTextSize(14);
        label.setPadding(0, dp(5), dp(6), dp(5));
        parent.addView(label, parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
    }

    private String title() {
        return (metric.isIndex() ? metric.label : symbol(metric.label)) + " history";
    }
    private static String symbol(String label) {
        return switch (label) { case "O3" -> "O₃"; case "NO2" -> "NO₂"; case "SO2" -> "SO₂"; default -> label; };
    }
    private String unit() { return metric.isIndex() ? (metric.usesUsLevels() ? "AQI" : "of 6") : metric == HistorySeries.Metric.CO ? "mg/m³" : "µg/m³"; }
    private double display(double value) { return metric == HistorySeries.Metric.CO ? value / 1000 : value; }
    private String value(double value) { return String.format(Locale.getDefault(), metric.isIndex() ? "%.0f" : "%.1f", display(value)); }
    private String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return view;
    }
    private LinearLayout card(int color, boolean outlined) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(shape(color, 22, outlined));
        return card;
    }
    private GradientDrawable shape(int color, float radius, boolean outlined) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (outlined) drawable.setStroke(dp(1), palette.outline);
        return drawable;
    }
    private void addBlock(View view, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        content.addView(view, params);
    }
    private void addDetail(LinearLayout parent, String value, float size, int color, boolean bold) {
        TextView view = text(value, size, color, bold);
        view.setPadding(0, dp(5), 0, dp(5));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showPoint(HistorySeries.Point sample) {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(24), dp(8), dp(24), dp(16));
        addDetail(details, value(sample.value()) + " " + unit(), 26, palette.ink, true);
        addDetail(details, "Reported " + date(sample.measuredAt()), 16, palette.muted, false);
        if (metric == HistorySeries.Metric.CO) addDetail(details, "Pollution level · US AQI", 14, palette.muted, true);
        addLevel(details, band(sample.value()));
        addDetail(details, sample.stationName(), 16, palette.muted, false);
        if (metric.isIndex()) {
            int total = metric.usesUsLevels() ? 6 : 5;
            addDetail(details, (sample.availablePollutants() < total ? "Partial data · " : "")
                    + sample.availablePollutants() + " of " + total + " pollutants reported at this time", 14, palette.muted, false);
            addDetail(details, "Main pollutant: " + symbol(sample.mainPollutant()) + "\n"
                    + AirQualityReading.formatConcentration(sample.mainPollutant(), sample.mainConcentration()),
                    16, palette.ink, true);
        }
        if (calculationInputs != null && !sample.inputs().isEmpty()) showInputs(calculationInputs, sample);
        if (metric == HistorySeries.Metric.US_NOWCAST) {
            LinearLayout tiles = new LinearLayout(this);
            tiles.setOrientation(LinearLayout.VERTICAL);
            details.addView(tiles);
            showInputs(tiles, sample);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        new AlertDialog.Builder(this)
                .setTitle(metric.isIndex() ? metric.label : symbol(metric.label))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private final class Chart extends View {
        private static final int POINT_ACTION_BASE = 0x01000000;
        private final List<HistorySeries.Point> samples;
        private final long start, end;
        private final String[] timeLabels = new String[3];
        private final String[] axisLabels = new String[3];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path(), area = new Path();
        private final DashPathEffect dashes = new DashPathEffect(new float[]{dp(4), dp(4)}, 0);
        private final double max;
        private final int touchSlop;
        private int selected = -1, pressed = -1;
        private float downX, downY;
        private boolean moved;

        Chart(List<HistorySeries.Point> samples, long start, long end) {
            super(PollutantHistoryActivity.this);
            this.samples = samples; this.start = start; this.end = Math.max(start + 1, end);
            DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
            timeLabels[0] = time.format(new Date(start));
            timeLabels[1] = time.format(new Date(start + (this.end - start) / 2));
            timeLabels[2] = time.format(new Date(end));
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
                    + "Activate to view the latest reading, or choose a report from accessibility actions."
                    + (samples.stream().anyMatch(HistorySeries.Point::backup)
                    ? " Red outlines mark OpenAQ backup readings." : ""));
        }
        private float plotLeft() { return dp(44); }
        private float plotRight() { return getWidth() - dp(12); }
        private float plotTop() { return dp(14); }
        private float plotBottom() { return getHeight() - dp(30); }
        private float pointX(HistorySeries.Point sample) {
            return plotLeft() + (plotRight() - plotLeft()) * (sample.measuredAt() - start) / (end - start);
        }
        private float pointY(HistorySeries.Point sample) {
            return plotBottom() - (float) (display(sample.value()) / max) * (plotBottom() - plotTop());
        }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            line.rewind(); area.rewind();
            float bottom = plotBottom(), runStart = 0, previousX = 0;
            for (int i = 0; i < samples.size(); i++) {
                HistorySeries.Point sample = samples.get(i);
                float x = pointX(sample), y = pointY(sample);
                boolean joined = i > 0 && sample.measuredAt() - samples.get(i - 1).measuredAt() <= LINE_GAP;
                if (joined) {
                    line.lineTo(x, y); area.lineTo(x, y);
                } else {
                    if (i > 0) { area.lineTo(previousX, bottom); area.lineTo(runStart, bottom); area.close(); }
                    line.moveTo(x, y); area.moveTo(x, y);
                    runStart = x;
                }
                previousX = x;
            }
            if (!samples.isEmpty()) { area.lineTo(previousX, bottom); area.lineTo(runStart, bottom); area.close(); }
            areaPaint.setShader(new LinearGradient(0, plotTop(), 0, bottom,
                    Palette.alpha(palette.accent, palette.dark ? 70 : 56), Palette.alpha(palette.accent, 0),
                    Shader.TileMode.CLAMP));
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
            float left = plotLeft(), right = plotRight(), top = plotTop(), bottom = plotBottom();
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(11));
            paint.setStrokeWidth(dp(1));
            for (int i = 0; i < 3; i++) {
                float y = bottom - (bottom - top) * i / 2;
                paint.setColor(palette.outline);
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(palette.muted); paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(axisLabels[i], left - dp(8), y + dp(4), paint);
            }
            paint.setColor(palette.muted);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(timeLabels[0], left, bottom + dp(22), paint);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(timeLabels[1], (left + right) / 2, bottom + dp(22), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(timeLabels[2], right, bottom + dp(22), paint);

            canvas.drawPath(area, areaPaint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Palette.alpha(palette.accent, 190));
            canvas.drawPath(line, paint);

            if (selected >= 0 && selected < samples.size()) {
                float x = pointX(samples.get(selected));
                paint.setStrokeWidth(dp(1));
                paint.setColor(palette.muted);
                paint.setPathEffect(dashes);
                canvas.drawLine(x, top, x, bottom, paint);
                paint.setPathEffect(null);
            }
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < samples.size(); i++) {
                HistorySeries.Point sample = samples.get(i);
                float x = pointX(sample), y = pointY(sample);
                if (i == selected) {
                    paint.setColor(Palette.alpha(palette.accent, 70));
                    canvas.drawCircle(x, y, dp(11), paint);
                }
                paint.setColor(palette.surface);
                canvas.drawCircle(x, y, dp(6), paint);
                paint.setColor(HistorySeries.color(metric, band(sample.value())));
                canvas.drawCircle(x, y, dp(4.5f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(Palette.alpha(palette.ink, 90));
                canvas.drawCircle(x, y, dp(4.5f), paint);
                if (sample.backup()) {
                    paint.setStrokeWidth(dp(2));
                    paint.setColor(palette.dark ? 0xFFFF8A80 : BACKUP_RED);
                    canvas.drawCircle(x, y, dp(7.5f), paint);
                }
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
