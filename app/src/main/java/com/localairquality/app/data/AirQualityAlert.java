package com.localairquality.app.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** User-defined sustained-pollution alerts, derived from the displayed hourly indices. */
public final class AirQualityAlert {
    public static final int REQUIRED_HOURS = 8;
    public static final int REASSESS_AFTER_HOURS = 8;
    public static final Result NONE = new Result(Level.NONE, false, false, 0, 0);

    public enum Level {
        NONE(0, 0, ""), RED(1, 0xFFD32F2F, "Red alert"),
        DEEP_RED(2, 0xFF960032, "Deep red alert"),
        PURPLE(3, 0xFF7D2181, "Purple alert"), BLACK(4, 0xFF101014, "Black alert");
        public final int severity, color;
        public final String title;
        Level(int severity, int color, String title) {
            this.severity = severity; this.color = color; this.title = title;
        }
    }

    public record Result(Level level, boolean us, boolean european, int usHours, int europeanHours,
                         int ageHours, int windowHours) {
        public Result(Level level, boolean us, boolean european, int usHours, int europeanHours) {
            this(level,us,european,usHours,europeanHours,0,24);
        }
        public boolean active() { return level != Level.NONE; }
        public String source() {
            return us && european ? "US AQI + European AQI" : us ? "US AQI" : "European AQI";
        }
        public String message() {
            if (!active()) return "";
            return (level == Level.BLACK ? "Air quality level 4 (highest)"
                    : "Air quality level " + level.severity + " alert")
                    + " in the current location (" + source() + ") - " + timeLabel();
        }
        public String timeLabel() {
            return ageHours == 0 ? "Now" : ageHours + (ageHours == 1 ? " hour ago" : " hours ago");
        }
    }

    private AirQualityAlert() {}

    public static Result calculate(PollutantHistory history, String station, long now) {
        if (station == null || station.isBlank() || !station.equals(history.activeStationId)) return NONE;
        return fromPoints(HistorySeries.points(history, HistorySeries.Metric.US_AQI),
                HistorySeries.points(history, HistorySeries.Metric.EUROPEAN_AQI), now);
    }

    static Result fromPoints(List<HistorySeries.Point> us, List<HistorySeries.Point> european, long now) {
        Map<Long, Double> usHours = hours(us, now), euHours = hours(european, now);
        long latest = Math.max(latestHour(usHours), latestHour(euHours));
        if (latest == Long.MIN_VALUE) return NONE;
        Result result = highest(usHours,euHours,latest,24);
        if (!result.active() || result.ageHours() < REASSESS_AFTER_HOURS) return result;
        // Reassess using actual hourly slots, never the last eight available records across gaps.
        return highest(recent(usHours,latest),recent(euHours,latest),latest,REASSESS_AFTER_HOURS);
    }

    private static Result highest(Map<Long,Double> us,Map<Long,Double> eu,long latest,int window) {
        for (Level level : new Level[]{Level.BLACK,Level.PURPLE,Level.DEEP_RED,Level.RED}) {
            double usThreshold = switch(level) { case BLACK -> 301; case PURPLE -> 201; case DEEP_RED -> 151; default -> Double.POSITIVE_INFINITY; };
            double euThreshold = switch(level) { case PURPLE -> 6; case DEEP_RED -> 5; case RED -> 4; default -> Double.POSITIVE_INFINITY; };
            int usCount=count(us,usThreshold), euCount=count(eu,euThreshold);
            boolean usEligible=usCount>=REQUIRED_HOURS, euEligible=euCount>=REQUIRED_HOURS;
            if (!usEligible && !euEligible) continue;
            long usLast=usEligible ? lastReached(us,usThreshold) : Long.MIN_VALUE;
            long euLast=euEligible ? lastReached(eu,euThreshold) : Long.MIN_VALUE;
            long last=Math.max(usLast,euLast);
            // Same-level sources are determined by the most recent qualifying hour.
            // Both outranks one only when both qualifying indices reached it in that hour.
            return new Result(level,usEligible && usLast==last,euEligible && euLast==last,
                    usCount,euCount,(int)(latest-last),window);
        }
        return NONE;
    }
    private static long latestHour(Map<Long,Double> hours) {
        return hours.keySet().stream().mapToLong(Long::longValue).max().orElse(Long.MIN_VALUE);
    }
    private static long lastReached(Map<Long,Double> hours,double threshold) {
        return hours.entrySet().stream().filter(entry -> entry.getValue()>=threshold)
                .mapToLong(Map.Entry::getKey).max().orElse(Long.MIN_VALUE);
    }
    private static Map<Long,Double> recent(Map<Long,Double> hours,long latest) {
        Map<Long,Double> result=new HashMap<>();
        hours.forEach((hour,value) -> { if(hour>latest-REASSESS_AFTER_HOURS) result.put(hour,value); });
        return result;
    }

    private static Map<Long, Double> hours(List<HistorySeries.Point> points, long now) {
        Map<Long, Double> hours = new HashMap<>();
        for (var point : points) {
            long time = point.measuredAt();
            if (time <= 0 || time <= now - PollutantHistory.WINDOW || time > now
                    || !AirQualityReading.isPresent(point.value())) continue;
            // Repeated refreshes/sub-hour reports never manufacture extra hours.
            // One qualifying reading is sufficient for that clock hour; no gap interpolation.
            hours.merge(Math.floorDiv(time, NowCast.HOUR), point.value(), Math::max);
        }
        return hours;
    }

    private static int count(Map<Long, Double> hours, double threshold) {
        return (int) hours.values().stream().filter(value -> value >= threshold).count();
    }
}
