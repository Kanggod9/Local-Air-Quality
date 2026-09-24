package com.localairquality.app.data;

import java.util.*;

/** Station-local current US AQI. Raw observations are never imputed or carried into missing hours. */
public final class NowCast {
    public static final long HOUR = 3_600_000L;
    private NowCast() {}

    public static final String HOURLY_NOT_SUPPLIED = "Required hourly measurement not supplied";
    public record Input(double concentration, int aqi, int hours, String method) {
        public boolean hourlyNotSupplied() {
            // Also recognize wording in already-recorded v1.4.0 snapshots.
            return HOURLY_NOT_SUPPLIED.equals(method) || "Hourly data unavailable".equals(method);
        }
        public String unavailableReason() {
            return hourlyNotSupplied() ? HOURLY_NOT_SUPPLIED : "Not enough hourly data";
        }
    }
    public record Snapshot(long measuredAt, String stationId, String stationName, List<Input> inputs, double ozoneEstimate) {
        public Snapshot { inputs = List.copyOf(inputs); }
        public Snapshot(long time, String station, String name, List<Input> inputs) {
            this(time, station, name, inputs, inputs.get(2).concentration());
        }
        public int main() {
            int best = -1;
            for (int p = 0; p < inputs.size(); p++)
                if (inputs.get(p).aqi >= 0 && (best < 0 || inputs.get(p).aqi > inputs.get(best).aqi)) best = p;
            return best;
        }
        public int aqi() { int p = main(); return p < 0 ? -1 : inputs.get(p).aqi; }
        public int count() { return (int) inputs.stream().filter(i -> i.aqi >= 0).count(); }
        public String pollutant() { int p = main(); return p < 0 ? "—" : PollutantHistory.LABELS[p]; }
        public double concentration() { int p = main(); return p < 0 ? Double.NaN : inputs.get(p).concentration; }
        public String level() { return aqi() < 0 ? "Not enough data" : AqiCalculator.usLevel(aqi()); }
        public String score() { return aqi() < 0 ? "—" : Integer.toString(aqi()); }
        public String status() { return count() < 6 ? "Partial · " + count() + "/6 pollutants" : "6/6 pollutants"; }
    }

    public static boolean hourly(String stationId, int pollutant) {
        // NEA PSI gases/PM10 are max/rolling statistics, not hourly means. Only its PM2.5 feed qualifies.
        return !stationId.startsWith("nea:") || pollutant == 0;
    }

    public static Snapshot calculate(List<PollutantHistory.Sample> samples, long time, String station, String name) {
        return calculate(samples, List.of(), List.of(), time, station, name);
    }
    public static Snapshot calculate(List<PollutantHistory.Sample> samples, List<PollutantHistory.Ozone> ozone,
                                     List<Snapshot> previous, long time, String station, String name) {
        double[][] hours = new double[6][24];
        long[][] reportTimes = new long[6][24];
        for (double[] row : hours) Arrays.fill(row, Double.NaN);
        for (var sample : samples) {
            if (!station.equals(sample.stationId()) || sample.measuredAt() > time) continue;
            long age = Math.floorDiv(time, HOUR) - Math.floorDiv(sample.measuredAt(), HOUR);
            if (age < 0 || age >= 24) continue;
            for (int p = 0; p < 6; p++) {
                if (hourly(station, p) && sample.isHourly(p) && AirQualityReading.isPresent(sample.values()[p])
                        && sample.measuredAt() >= reportTimes[p][(int)age]) {
                    hours[p][(int)age] = sample.values()[p];
                    reportTimes[p][(int)age] = sample.measuredAt();
                }
            }
        }
        List<Input> inputs = new ArrayList<>();
        double[] calibration = new double[OzoneNowCast.HOURS]; Arrays.fill(calibration, Double.NaN);
        long[] ozoneTimes = new long[OzoneNowCast.HOURS];
        for (var sample : ozone) if (sample.stationId().equals(station) && sample.measuredAt() <= time) {
            long age = Math.floorDiv(time,HOUR)-Math.floorDiv(sample.measuredAt(),HOUR);
            if (age >= 0 && age < OzoneNowCast.HOURS && sample.measuredAt() >= ozoneTimes[335-(int)age]) {
                calibration[335-(int)age] = sample.value()*24.45/48.0; ozoneTimes[335-(int)age] = sample.measuredAt();
            }
        }
        for (int age = 0; age < 24; age++) if (present(hours[2][age]) && reportTimes[2][age] >= ozoneTimes[335-age])
            calibration[335-age] = hours[2][age]*24.45/48.0;
        double previousOzone = Double.NaN, twoHoursOzone = Double.NaN;
        for (var snapshot : previous) if (station.equals(snapshot.stationId())) {
            long age = Math.floorDiv(time,HOUR)-Math.floorDiv(snapshot.measuredAt(),HOUR);
            if (age == 1) previousOzone = snapshot.ozoneEstimate()*24.45/48.0;
            if (age == 2) twoHoursOzone = snapshot.ozoneEstimate()*24.45/48.0;
        }
        OzoneNowCast.Result ozoneResult = OzoneNowCast.calculate(calibration, previousOzone, twoHoursOzone);
        for (int p = 0; p < 6; p++) {
            double c = Double.NaN;
            int count = 0;
            String method;
            if (p < 2) {
                method = "12h NowCast";
                c = particle(hours[p]); count = count(hours[p], 12);
            } else if (p == 2) {
                c = ozoneResult.ppb()*48.0/24.45; count = ozoneResult.validHours(); method = ozoneResult.method();
            } else if (p == 4) {
                method = "8h mean"; count = count(hours[p], 8);
                if (count >= 6) c = mean(hours[p], 8);
            } else {
                method = "1h mean"; c = hours[p][0]; count = present(c) ? 1 : 0;
                if (p == 5) {
                    int dailyCount = count(hours[p],24);
                    double daily = dailyCount >= 18 ? mean(hours[p],24) : Double.NaN;
                    if (present(daily) && Math.floor(daily*24.45/64.066) >= 305) {
                        c = daily; count = dailyCount; method = "24h mean";
                    } else if (present(c) && Math.floor(c*24.45/64.066) >= 305) {
                        if (present(daily)) {
                            inputs.add(new Input(c,200,dailyCount,"1h · 24h mean below 305 ppb")); continue;
                        }
                        c = Double.NaN; count = dailyCount; method = "Needs 18/24 hourly values";
                    }
                }
            }
            if (!hourly(station, p)) method = HOURLY_NOT_SUPPLIED;
            int index = index(p, c);
            if (p == 2 && present(hours[p][0])) {
                int oneHour = ozoneOneHour(hours[p][0]);
                if (oneHour > index) { c = hours[p][0]; index = oneHour; method = "1h high ozone"; count = 1; }
            }
            inputs.add(new Input(c, index, count, method));
        }
        return new Snapshot(time, station, name, inputs, ozoneResult.ppb()*48.0/24.45);
    }

    /** Newest first, fixed hour slots: missing slots retain their exponent. */
    public static double particle(double[] hourly) {
        int recent = count(hourly, Math.min(3, hourly.length));
        if (recent < 2) return Double.NaN;
        double min = Double.POSITIVE_INFINITY, max = 0;
        for (int i = 0; i < Math.min(12, hourly.length); i++) if (present(hourly[i])) {
            min = Math.min(min, hourly[i]); max = Math.max(max, hourly[i]);
        }
        if (max == 0) return 0;
        double w = Math.max(0.5, min / max), numerator = 0, denominator = 0;
        for (int i = 0; i < Math.min(12, hourly.length); i++) if (present(hourly[i])) {
            double weight = Math.pow(w, i);
            numerator += hourly[i] * weight; denominator += weight;
        }
        return numerator / denominator;
    }
    private static boolean present(double c) { return AirQualityReading.isPresent(c); }
    private static int count(double[] h, int n) { int count = 0; for (int i = 0; i < n; i++) if (present(h[i])) count++; return count; }
    private static double mean(double[] h, int n) { double sum = 0; for (int i = 0; i < n; i++) if (present(h[i])) sum += h[i]; return sum / count(h, n); }

    public static int index(int pollutant, double c) {
        if (!present(c)) return -1;
        return switch (pollutant) {
            case 0 -> interpolate(Math.floor(c * 10 + 1e-9) / 10, new double[]{0,9.1,35.5,55.5,125.5,225.5}, new double[]{9,35.4,55.4,125.4,225.4,325.4}, 0);
            case 1 -> interpolate(Math.floor(c), new double[]{0,55,155,255,355,425}, new double[]{54,154,254,354,424,604}, 0);
            case 2 -> {
                double ppm = Math.floor(c * 24.45 / 48 + 1e-9) / 1000;
                // EPA 8h ozone is not defined above 0.200 ppm. No fabricated hazardous segment.
                yield ppm > .200 ? -1 : interpolate(ppm, new double[]{0,.055,.071,.086,.106}, new double[]{.054,.070,.085,.105,.200}, 0);
            }
            case 3 -> interpolate(Math.floor(c * 24.45 / 46.0055 + 1e-9), new double[]{0,54,101,361,650,1250}, new double[]{53,100,360,649,1249,2049}, 0);
            case 4 -> interpolate(Math.floor(c * 24.45 / 28.01 / 100 + 1e-9) / 10, new double[]{0,4.5,9.5,12.5,15.5,30.5}, new double[]{4.4,9.4,12.4,15.4,30.4,50.4}, 0);
            case 5 -> interpolate(Math.floor(c * 24.45 / 64.066 + 1e-9), new double[]{0,36,76,186,305,605}, new double[]{35,75,185,304,604,1004}, 0);
            default -> -1;
        };
    }
    private static int ozoneOneHour(double c) {
        double ppm = Math.floor(c * 24.45 / 48 + 1e-9) / 1000;
        return ppm < .125 ? -1 : interpolate(ppm, new double[]{.125,.165,.205,.405}, new double[]{.164,.204,.404,.604}, 2);
    }
    private static int interpolate(double c, double[] low, double[] high, int offset) {
        int[] lo = {0,51,101,151,201,301}, hi = {50,100,150,200,300,500};
        int i = 0;
        while (i < high.length - 1 && c > high[i]) i++;
        return (int)Math.round(lo[i+offset] + (c-low[i]) * (hi[i+offset]-lo[i+offset]) / (high[i]-low[i]));
    }
}
