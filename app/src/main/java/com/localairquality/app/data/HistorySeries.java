package com.localairquality.app.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                        String mainPollutant, double mainConcentration, List<NowCast.Input> inputs, boolean backup) {
        public Point { inputs = List.copyOf(inputs); }
        public Point(long time, String station, double value, int count, String main, double concentration,
                     List<NowCast.Input> inputs) {
            this(time, station, value, count, main, concentration, inputs, false);
        }
        public Point(long time, String station, double value, int count, String main, double concentration) {
            this(time, station, value, count, main, concentration, List.of());
        }
    }

    public record ChartSource(String stationId, String stationName, boolean backup) {}

    public static ChartSource chartSource(PollutantHistory history, AirQualityReading current) {
        if (current != null && current.isBackup() && current.historyStationId().equals(history.activeStationId))
            return new ChartSource(current.stationId,current.sourceLabel(),true);
        return new ChartSource(history.activeStationId,history.stationName,false);
    }

    /** Charts and alerts use only the current actual source, never a joined official/backup series. */
    public static List<Point> chartPoints(PollutantHistory history, Metric metric, ChartSource source) {
        List<PollutantHistory.Sample> samples = new ArrayList<>();
        if (source.backup()) {
            Map<Long,PollutantHistory.Sample> hours = new HashMap<>();
            for (var backup : history.backups) {
                var sample = backup.reading();
                if (!backup.primaryStationId().equals(history.activeStationId)
                        || !sample.stationId().equals(source.stationId())) continue;
                long hour = Math.floorDiv(sample.measuredAt(),NowCast.HOUR);
                var old = hours.get(hour);
                if (old == null || sample.measuredAt() >= old.measuredAt()) hours.put(hour,sample);
            }
            for (var sample : hours.values()) samples.add(new PollutantHistory.Sample(sample.measuredAt(),
                    sample.stationId(),sample.stationName()+" (OpenAQ, backup)",sample.values(),sample.hourlyMask(),true));
        } else {
            for (var sample : history.samples) if (visible(source.stationId(),sample.stationId())) samples.add(sample);
        }
        samples.sort(Comparator.comparingLong(PollutantHistory.Sample::measuredAt));
        List<NowCast.Snapshot> snapshots = new ArrayList<>();
        if (metric == Metric.US_NOWCAST) for (var snapshot : history.nowcasts) {
            if (!visible(source.stationId(),snapshot.stationId()) || samples.stream().noneMatch(s ->
                    s.stationId().equals(snapshot.stationId())
                    && Math.floorDiv(s.measuredAt(),NowCast.HOUR)==Math.floorDiv(snapshot.measuredAt(),NowCast.HOUR))) continue;
            snapshots.add(new NowCast.Snapshot(snapshot.measuredAt(),snapshot.stationId(),
                    snapshot.stationName()+(source.backup()?" (OpenAQ, backup)":""),
                    snapshot.inputs(),snapshot.ozoneEstimate(),source.backup()));
        }
        snapshots.sort(Comparator.comparingLong(NowCast.Snapshot::measuredAt));
        return points(samples,snapshots,metric,source.stationId());
    }

    /** Official station history. Backup history is selected explicitly through chartPoints(). */
    public static List<Point> points(PollutantHistory history, Metric metric) {
        return chartPoints(history,metric,new ChartSource(history.activeStationId,history.stationName,false));
    }
    private static List<Point> points(List<PollutantHistory.Sample> samples, List<NowCast.Snapshot> snapshots,
                                      Metric metric, String stationId) {
        List<Point> points = new ArrayList<>();
        if (metric == Metric.US_NOWCAST) {
            for (var snapshot : snapshots) if (visible(stationId, snapshot.stationId()) && snapshot.aqi() >= 0) points.add(new Point(snapshot.measuredAt(),
                    snapshot.stationName(), snapshot.aqi(), snapshot.count(), snapshot.pollutant(), snapshot.concentration(), snapshot.inputs(), snapshot.backup()));
            return points;
        }
        for (PollutantHistory.Sample sample : samples) {
            if (!visible(stationId, sample.stationId())) continue;
            if (!metric.isIndex()) {
                double value = sample.values()[metric.pollutant];
                if (AirQualityReading.isPresent(value)) points.add(new Point(sample.measuredAt(), sample.stationName(), value, 1,
                        metric.label, value, List.of(), sample.backup()));
                continue;
            }
            AirQualityReading reading = new AirQualityReading();
            reading.stationId = sample.stationId();
            double[] v = sample.values();
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
            points.add(new Point(sample.measuredAt(), sample.stationName(), value, count,
                    mainPollutant, concentration, List.of(), sample.backup()));
        }
        return points;
    }
    private static boolean visible(String activeStation, String station) {
        return activeStation.isEmpty() || activeStation.equals(station);
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
