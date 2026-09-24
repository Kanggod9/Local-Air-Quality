package com.localairquality.app.data;

import java.util.ArrayList;
import java.util.List;

/** Uses the same index formulas as the home cards, without mixing stations or report times. */
public final class HistorySeries {
    private HistorySeries() {}
    public enum Metric {
        PM25("PM2.5", 0), PM10("PM10", 1), O3("O3", 2), NO2("NO2", 3), CO("CO", 4), SO2("SO2", 5),
        US_AQI("US AQI+", -1), EUROPEAN_AQI("European AQI", -1), US_NOWCAST("US NowCast", -1);
        public final String label;
        public final int pollutant;
        Metric(String label, int pollutant) { this.label = label; this.pollutant = pollutant; }
        public boolean isIndex() { return pollutant < 0; }
        public boolean usesUsLevels() { return this == US_AQI || this == US_NOWCAST || this == CO; }
        public static Metric forLabel(String label) {
            for (Metric metric : values()) if (metric.label.equals(label)) return metric;
            return null;
        }
    }
    public record Point(long measuredAt, String stationName, double value, int availablePollutants,
                        String mainPollutant, double mainConcentration, List<NowCast.Input> inputs) {
        public Point { inputs = List.copyOf(inputs); }
        public Point(long time, String station, double value, int count, String main, double concentration) {
            this(time, station, value, count, main, concentration, List.of());
        }
    }

    public static List<Point> points(PollutantHistory history, Metric metric) {
        List<Point> points = new ArrayList<>();
        if (metric == Metric.US_NOWCAST) {
            for (var snapshot : history.nowcasts) if (snapshot.aqi() >= 0) points.add(new Point(snapshot.measuredAt(),
                    snapshot.stationName(), snapshot.aqi(), snapshot.count(), snapshot.pollutant(), snapshot.concentration(), snapshot.inputs()));
            return points;
        }
        for (PollutantHistory.Sample sample : history.samples) {
            if (!metric.isIndex()) {
                double value = sample.values()[metric.pollutant];
                if (AirQualityReading.isPresent(value)) points.add(new Point(sample.measuredAt(), sample.stationName(), value, 1,
                        metric.label, value));
                continue;
            }
            AirQualityReading reading = new AirQualityReading();
            reading.stationId = sample.stationId();
            double[] v = sample.values().clone();
            if (metric == Metric.EUROPEAN_AQI) for (int p = 0; p < 6; p++)
                if (!NowCast.hourly(sample.stationId(), p) || p == 4) v[p] = Double.NaN;
            reading.pm25 = v[0]; reading.pm10 = v[1]; reading.o3 = v[2];
            reading.no2 = v[3]; reading.co = v[4]; reading.so2 = v[5];
            int count = reading.availablePollutants();
            double value;
            String mainPollutant;
            if (metric == Metric.US_AQI) {
                if (count == 0) continue;
                AqiCalculator.UsResult result = AqiCalculator.usAqi(reading);
                value = result.aqi();
                mainPollutant = result.pollutant();
            } else {
                if (AirQualityReading.isPresent(reading.co)) count--;
                AqiCalculator.EuResult result = AqiCalculator.europeanAqi(reading);
                value = result.band();
                mainPollutant = result.pollutant();
                if (value == 0) continue;
            }
            Metric mainMetric = Metric.forLabel(mainPollutant);
            double concentration = mainMetric != null && !mainMetric.isIndex()
                    ? v[mainMetric.pollutant] : AirQualityReading.MISSING;
            List<NowCast.Input> inputs = new ArrayList<>();
            for (int p = 0; p < 6; p++) inputs.add(new NowCast.Input(v[p],
                    AqiCalculator.europeanPollutantBand(PollutantHistory.LABELS[p], v[p]),
                    AirQualityReading.isPresent(v[p]) ? 1 : 0,
                    NowCast.hourly(sample.stationId(), p) ? "1h mean" : NowCast.HOURLY_NOT_SUPPLIED));
            points.add(new Point(sample.measuredAt(), sample.stationName(), value, count,
                    mainPollutant, concentration, inputs));
        }
        return points;
    }
    public static int band(Metric metric, double value) {
        if (!AirQualityReading.isPresent(value)) return 0;
        if (metric == Metric.CO) return band(Metric.US_AQI, AqiCalculator.coIndex(value));
        if (metric == Metric.EUROPEAN_AQI) return (int)value;
        if (metric == Metric.US_AQI || metric == Metric.US_NOWCAST) return value <= 50 ? 1 : value <= 100 ? 2 : value <= 150 ? 3
                : value <= 200 ? 4 : value <= 300 ? 5 : 6;
        return AqiCalculator.europeanPollutantBand(metric.label, value);
    }
    private static int representativeUsValue(int band) {
        return switch (band) { case 1 -> 0; case 2 -> 51; case 3 -> 101; case 4 -> 151; case 5 -> 201; default -> 301; };
    }
    public static int color(Metric metric, int band) {
        if (band < 1 || band > 6) return AqiCalculator.euColor(0);
        return metric.usesUsLevels() ? AqiCalculator.usColor(representativeUsValue(band)) : AqiCalculator.euColor(band);
    }
    public static String level(Metric metric, int band) {
        if (band < 1 || band > 6) return "Not rated";
        return metric.usesUsLevels() ? AqiCalculator.usLevel(representativeUsValue(band)) : AqiCalculator.europeanLevel(band);
    }
}
