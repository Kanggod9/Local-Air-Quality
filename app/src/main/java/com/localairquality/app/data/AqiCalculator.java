package com.localairquality.app.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AqiCalculator {
    private static final int[] AQI_LOW = {0, 51, 101, 151, 201, 301};
    private static final int[] AQI_HIGH = {50, 100, 150, 200, 300, 500};
    private static final String[] US_LEVELS = {
            "Good", "Moderate", "Unhealthy for Sensitive Groups",
            "Unhealthy", "Very Unhealthy", "Hazardous"
    };
    private static final String[] EU_LEVELS = {
            "Good", "Fair", "Moderate", "Poor", "Very Poor", "Extremely Poor"
    };
    private static final double[] EU_PM25 = {5, 15, 50, 90, 140};
    private static final double[] EU_PM10 = {15, 45, 120, 195, 270};
    private static final double[] EU_O3 = {60, 100, 120, 160, 180};
    private static final double[] EU_NO2 = {10, 25, 60, 100, 150};
    private static final double[] EU_SO2 = {20, 40, 125, 190, 275};

    private AqiCalculator() {}

    public static UsResult usAqi(AirQualityReading r) {
        List<SubIndex> values = new ArrayList<>();
        // AQI+ is the hourly, real-time variant. Every sub-index must use the same
        // current concentration shown to the user instead of a hidden NowCast value.
        add(values, "PM2.5", pm25Index(r.pm25));
        add(values, "PM10", pm10Index(r.pm10));
        add(values, "O3", ozoneIndex(r.o3));
        add(values, "NO2", no2Index(r.no2));
        add(values, "CO", coIndex(r.co));
        add(values, "SO2", so2Index(r.so2));

        if (values.isEmpty()) return new UsResult(0, "No data", "—");
        SubIndex worst = Collections.max(values, (a, b) -> Integer.compare(a.value, b.value));
        int aqi = Math.max(0, Math.min(500, worst.value));
        return new UsResult(aqi, usLevel(aqi), worst.pollutant);
    }

    public static EuResult europeanAqi(AirQualityReading r) {
        int band = 0;
        String pollutant = "—";
        int candidate = europeanPollutantBand("PM2.5", r.pm25);
        if (candidate > band) { band = candidate; pollutant = "PM2.5"; }
        candidate = europeanPollutantBand("PM10", r.pm10);
        if (candidate > band) { band = candidate; pollutant = "PM10"; }
        candidate = europeanPollutantBand("O3", r.o3);
        if (candidate > band) { band = candidate; pollutant = "O3"; }
        candidate = europeanPollutantBand("NO2", r.no2);
        if (candidate > band) { band = candidate; pollutant = "NO2"; }
        candidate = europeanPollutantBand("SO2", r.so2);
        if (candidate > band) { band = candidate; pollutant = "SO2"; }
        return band == 0
                ? new EuResult(0, "No data", "—")
                : new EuResult(band, EU_LEVELS[band - 1], pollutant);
    }

    public static double pmNowCast(List<Double> newestFirst) {
        List<Double> values = new ArrayList<>();
        for (Double value : newestFirst) {
            if (value != null && AirQualityReading.isPresent(value)) values.add(value);
            if (values.size() == 12) break;
        }
        if (values.size() < 2) return values.isEmpty() ? Double.NaN : values.get(0);
        double minimum = Collections.min(values);
        double maximum = Collections.max(values);
        if (maximum <= 0) return 0;
        double weight = Math.max(0.5, 1.0 - ((maximum - minimum) / maximum));
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < values.size(); i++) {
            double factor = Math.pow(weight, i);
            numerator += values.get(i) * factor;
            denominator += factor;
        }
        return numerator / denominator;
    }

    public static double rollingAverage(List<Double> newestFirst, int hours) {
        double total = 0;
        int count = 0;
        for (Double value : newestFirst) {
            if (value != null && AirQualityReading.isPresent(value)) {
                total += value;
                count++;
                if (count == hours) break;
            }
        }
        return count == 0 ? Double.NaN : total / count;
    }

    public static String usLevel(int aqi) {
        if (aqi <= 50) return US_LEVELS[0];
        if (aqi <= 100) return US_LEVELS[1];
        if (aqi <= 150) return US_LEVELS[2];
        if (aqi <= 200) return US_LEVELS[3];
        if (aqi <= 300) return US_LEVELS[4];
        return US_LEVELS[5];
    }

    public static int usColor(int aqi) {
        if (aqi <= 50) return 0xFF4BC6A6;
        if (aqi <= 100) return 0xFFF2CF4A;
        if (aqi <= 150) return 0xFFFF9651;
        if (aqi <= 200) return 0xFFEE5A5A;
        if (aqi <= 300) return 0xFF9B62C8;
        return 0xFF8B3F5B;
    }

    public static String europeanLevel(int band) {
        return band >= 1 && band <= EU_LEVELS.length ? EU_LEVELS[band - 1] : "Not rated";
    }

    public static int euColor(int band) {
        return switch (band) {
            case 1 -> 0xFF50F0E6;
            case 2 -> 0xFF50CCAA;
            case 3 -> 0xFFF0E641;
            case 4 -> 0xFFFF5050;
            case 5 -> 0xFF960032;
            case 6 -> 0xFF7D2181;
            default -> 0xFFB8C2CC;
        };
    }

    public static int europeanPollutantBand(String pollutant, double concentration) {
        return switch (pollutant) {
            case "PM2.5" -> europeanBand(concentration, EU_PM25);
            case "PM10" -> europeanBand(concentration, EU_PM10);
            case "O3" -> europeanBand(concentration, EU_O3);
            case "NO2" -> europeanBand(concentration, EU_NO2);
            case "SO2" -> europeanBand(concentration, EU_SO2);
            // CO has no EEA band; do not invent an EEA classification for its tile.
            default -> 0;
        };
    }

    private static int pm25Index(double value) {
        if (!AirQualityReading.isPresent(value)) return -1;
        double c = Math.floor(value * 10.0) / 10.0;
        return interpolate(c,
                new double[]{0.0, 9.1, 35.5, 55.5, 125.5, 225.5},
                new double[]{9.0, 35.4, 55.4, 125.4, 225.4, 325.4});
    }

    private static int pm10Index(double value) {
        if (!AirQualityReading.isPresent(value)) return -1;
        double c = Math.floor(value);
        return interpolate(c,
                new double[]{0, 55, 155, 255, 355, 425},
                new double[]{54, 154, 254, 354, 424, 604});
    }

    private static int ozoneIndex(double micrograms) {
        if (!AirQualityReading.isPresent(micrograms)) return -1;
        double ppm = truncate(micrograms * 24.45 / 48.00 / 1000.0, 3);
        return interpolate(ppm,
                new double[]{0.000, 0.055, 0.071, 0.086, 0.106, 0.201},
                new double[]{0.054, 0.070, 0.085, 0.105, 0.200, 0.604});
    }

    /** US CO thresholds applied to the supplied concentration, normalized to µg/m³. */
    public static int coIndex(double micrograms) {
        if (!AirQualityReading.isPresent(micrograms)) return -1;
        double ppm = truncate((micrograms / 1000.0) * 24.45 / 28.01, 1);
        return interpolate(ppm,
                new double[]{0.0, 4.5, 9.5, 12.5, 15.5, 30.5},
                new double[]{4.4, 9.4, 12.4, 15.4, 30.4, 50.4});
    }

    private static int so2Index(double micrograms) {
        if (!AirQualityReading.isPresent(micrograms)) return -1;
        double ppb = Math.floor(micrograms * 24.45 / 64.066);
        return interpolate(ppb,
                new double[]{0, 36, 76, 186, 305, 605},
                new double[]{35, 75, 185, 304, 604, 1004});
    }

    private static int no2Index(double micrograms) {
        if (!AirQualityReading.isPresent(micrograms)) return -1;
        double ppb = Math.floor(micrograms * 24.45 / 46.0055);
        return interpolate(ppb,
                new double[]{0, 54, 101, 361, 650, 1250},
                new double[]{53, 100, 360, 649, 1249, 2049});
    }

    private static int interpolate(double concentration, double[] lows, double[] highs) {
        int index = highs.length - 1;
        for (int i = 0; i < highs.length; i++) {
            if (concentration <= highs[i]) { index = i; break; }
        }
        double capped = Math.min(concentration, highs[index]);
        double result = ((AQI_HIGH[index] - AQI_LOW[index]) / (highs[index] - lows[index]))
                * (capped - lows[index]) + AQI_LOW[index];
        return (int) Math.round(result);
    }

    private static int europeanBand(double concentration, double[] upperBounds) {
        if (!AirQualityReading.isPresent(concentration)) return 0;
        for (int i = 0; i < upperBounds.length; i++) {
            if (concentration <= upperBounds[i]) return i + 1;
        }
        return 6;
    }

    private static void add(List<SubIndex> values, String pollutant, int value) {
        if (value >= 0) values.add(new SubIndex(pollutant, value));
    }

    private static double truncate(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.floor(value * scale) / scale;
    }

    private record SubIndex(String pollutant, int value) {}
    public record UsResult(int aqi, String level, String pollutant) {}
    public record EuResult(int band, String level, String pollutant) {}
}

