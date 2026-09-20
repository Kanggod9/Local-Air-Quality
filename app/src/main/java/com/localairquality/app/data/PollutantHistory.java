package com.localairquality.app.data;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Unique station reports, ordered and expired by their reported measurement time. */
public final class PollutantHistory {
    public static final long WINDOW = 24L * 60 * 60 * 1000;
    public static final String[] LABELS = {"PM2.5", "PM10", "O3", "NO2", "CO", "SO2"};
    public String countryId = "", countryName = "", stationName = "", locationName = "";
    public long startedAt;
    public String activeStationId = "";
    public long activeStationSince, lastRecoveryCompletedAt;
    public final List<Sample> samples = new ArrayList<>();

    public record Sample(long measuredAt, String stationId, String stationName, double[] values) {}

    public void add(String country, String countryLabel, String id, String station, String location,
                    long[] measuredTimes, double[] values, long now) {
        if (values.length != LABELS.length || measuredTimes.length != LABELS.length) {
            throw new IllegalArgumentException("Six pollutants and timestamps required");
        }
        prune(now);
        if (!country.equals(countryId)) {
            samples.clear();
            startedAt = now;
            activeStationId = "";
        }
        if (!id.equals(activeStationId)) {
            activeStationId = id; activeStationSince = now; lastRecoveryCompletedAt = 0;
        }
        countryId = country;
        countryName = countryLabel;
        stationName = station;
        locationName = location;
        for (int p = 0; p < LABELS.length; p++) {
            long time = measuredTimes[p];
            if (time <= 0 || time < now - WINDOW || time > now || !present(values[p])) continue;
            merge(time, id, station, p, values[p]);
        }
        samples.sort(Comparator.comparingLong(Sample::measuredAt));
    }

    private static boolean present(double value) { return Double.isFinite(value) && value >= 0; }

    private void merge(long time, String id, String station, int pollutant, double value) {
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            if (sample.measuredAt == time && sample.stationId.equals(id)) {
                double[] updated = sample.values.clone();
                updated[pollutant] = value;
                samples.set(i, new Sample(time, id, station, updated));
                return;
            }
        }
        double[] values = new double[LABELS.length];
        Arrays.fill(values, Double.NaN);
        values[pollutant] = value;
        samples.add(new Sample(time, id, station, values));
    }

    public record Recovery(String countryId, long sessionStart, String stationId, long stationSince,
                           long from, long to) {}

    public boolean needsRecovery(long now) {
        return !activeStationId.isEmpty() && (lastRecoveryCompletedAt <= 0
                || now < lastRecoveryCompletedAt || now - lastRecoveryCompletedAt >= 15L * 60 * 1000);
    }

    public Recovery beginRecovery(long now) {
        if (!needsRecovery(now)) return null;
        long hour = 60L * 60 * 1000;
        long from = Math.max(now - WINDOW, Math.floorDiv(activeStationSince, hour) * hour);
        return new Recovery(countryId, startedAt, activeStationId, activeStationSince, from, now);
    }

    public boolean completeRecovery(Recovery request) {
        if (!matches(request)) return false;
        // Failed/interrupted attempts never advance this watermark, including across process death.
        lastRecoveryCompletedAt = Math.max(lastRecoveryCompletedAt, request.to);
        return true;
    }

    private boolean matches(Recovery request) {
        return countryId.equals(request.countryId) && startedAt == request.sessionStart
                && activeStationId.equals(request.stationId) && activeStationSince == request.stationSince;
    }

    public boolean acceptRecovery(Recovery request, List<Sample> reports, long now) {
        if (!matches(request)) return false;
        for (Sample report : reports) {
            if (!report.stationId.equals(request.stationId) || report.measuredAt < request.from
                    || report.measuredAt > request.to || report.measuredAt < now - WINDOW
                    || report.measuredAt <= 0 || report.measuredAt > now) continue;
            for (int p = 0; p < LABELS.length; p++) {
                if (present(report.values[p])) merge(report.measuredAt, report.stationId, report.stationName, p, report.values[p]);
            }
        }
        prune(now);
        samples.sort(Comparator.comparingLong(Sample::measuredAt));
        return true;
    }
    public void prune(long now) {
        samples.removeIf(sample -> sample.measuredAt <= 0 || sample.measuredAt < now - WINDOW || sample.measuredAt > now);
    }

    public void write(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(4);
        out.writeUTF(countryId); out.writeUTF(countryName); out.writeUTF(stationName); out.writeUTF(locationName);
        out.writeLong(startedAt);
        out.writeUTF(activeStationId); out.writeLong(activeStationSince); out.writeLong(lastRecoveryCompletedAt);
        out.writeInt(samples.size());
        for (Sample sample : samples) {
            out.writeLong(sample.measuredAt);
            out.writeUTF(sample.stationId); out.writeUTF(sample.stationName);
            for (double value : sample.values) out.writeDouble(value);
        }
        out.flush();
    }

    public static PollutantHistory read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int version = in.readInt();
        if (version < 1 || version > 4) throw new IOException("Unknown history version");
        PollutantHistory history = new PollutantHistory();
        history.countryId = in.readUTF(); history.countryName = in.readUTF();
        history.stationName = in.readUTF(); history.locationName = in.readUTF();
        history.startedAt = in.readLong();
        if (version >= 3) {
            history.activeStationId = in.readUTF();
            history.activeStationSince = in.readLong();
            long recoveryTime = in.readLong();
            // v3 saved attempts, which do not prove a download ever finished.
            if (version >= 4) history.lastRecoveryCompletedAt = recoveryTime;
        }
        int count = in.readInt();
        if (count < 0 || count > 100000) throw new IOException("Invalid history length");
        for (int i = 0; i < count; i++) {
            // Migrate v1 refresh snapshots using their stored report time, discarding receipt time.
            if (version == 1) in.readLong();
            long measured = in.readLong();
            String stationId = in.readUTF(), stationName = in.readUTF();
            for (int p = 0; p < LABELS.length; p++) {
                double value = in.readDouble();
                if (measured > 0 && present(value)) history.merge(measured, stationId, stationName, p, value);
            }
        }
        history.samples.sort(Comparator.comparingLong(Sample::measuredAt));
        if (version < 3 && !history.samples.isEmpty()) {
            Sample latest = history.samples.get(history.samples.size() - 1);
            history.activeStationId = latest.stationId;
            history.activeStationSince = Math.max(history.startedAt, latest.measuredAt);
            // Extend only over the final uninterrupted run of the same known station.
            for (int i = history.samples.size() - 1; i >= 0; i--) {
                Sample sample = history.samples.get(i);
                if (!sample.stationId.equals(latest.stationId)) break;
                history.activeStationSince = Math.max(history.startedAt, sample.measuredAt);
            }
        }
        return history;
    }
}

