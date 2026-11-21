package com.localairquality.app.data;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Unique station reports, ordered and expired by their reported measurement time. */
public final class PollutantHistory {
    public static final long WINDOW = 24L * 60 * 60 * 1000;
    public static final long OZONE_WINDOW = 14L * WINDOW;
    public static final String[] LABELS = {"PM2.5", "PM10", "O3", "NO2", "CO", "SO2"};
    public String countryId = "", countryName = "", stationName = "", locationName = "";
    public long startedAt;
    public String activeStationId = "";
    public long activeStationSince, lastRecoveryCompletedAt;
    // Chart/recovery start is independent of the latest station visit's request guard.
    public long activeHistorySince;
    public long lastOzoneRecoveryCompletedAt;
    public final List<Sample> samples = new ArrayList<>();
    public record BackupSample(String primaryStationId, Sample reading) {}
    public final List<BackupSample> backups = new ArrayList<>();
    public final List<NowCast.Snapshot> nowcasts = new ArrayList<>();
    public final List<Ozone> ozoneCalibration = new ArrayList<>();
    private boolean derivedDirty = true;
    public record Ozone(long measuredAt, String stationId, double value) {}

    public record OtherStationRecords(int records, int stations) {
        public String message() {
            if (records == 0) return "";
            return (records == 1 ? "There is " : "There are ") + records
                    + (records == 1 ? " record from " : " records from ")
                    + stations + (stations == 1 ? " other station" : " other stations")
                    + " in the last 24 hours.";
        }
    }

    /** One report per station/timestamp, not one count per pollutant or derived index. */
    public OtherStationRecords otherStationRecords(String currentStation, long now) {
        java.util.Map<String, java.util.Set<Long>> reports = new java.util.HashMap<>();
        for (Sample sample : samples) {
            if (sample.stationId.isBlank() || sample.stationId.equals(currentStation)
                    || sample.measuredAt <= 0 || sample.measuredAt < now-WINDOW || sample.measuredAt > now
                    || Arrays.stream(sample.values).noneMatch(PollutantHistory::present)) continue;
            reports.computeIfAbsent(sample.stationId, id -> new java.util.HashSet<>()).add(sample.measuredAt);
        }
        return new OtherStationRecords(reports.values().stream().mapToInt(java.util.Set::size).sum(), reports.size());
    }

    /** backup is display provenance, reconstructed from persisted BackupSample records. */
    public record Sample(long measuredAt, String stationId, String stationName, double[] values, int hourlyMask,
                         boolean backup) {
        public Sample(long time, String id, String name, double[] values, int hourlyMask) {
            this(time, id, name, values, hourlyMask, false);
        }
        /** Recovery reports are hourly; NEA exposes an hourly mean only for PM2.5. */
        public Sample(long time, String id, String name, double[] values) {
            this(time, id, name, values, id.startsWith("nea:") ? 1 : 63);
        }
        public boolean isHourly(int pollutant) { return (hourlyMask & (1 << pollutant)) != 0; }
    }

    public void addBackup(String primaryId, Sample reading, long now) {
        if(primaryId.isBlank() || !primaryId.equals(activeStationId) || !reading.stationId.startsWith("openaq:")
                || reading.measuredAt<=0 || reading.measuredAt>now || reading.measuredAt<now-WINDOW) return;
        for(int i=0;i<backups.size();i++) {
            var old=backups.get(i);
            if(old.primaryStationId.equals(primaryId) && old.reading.stationId.equals(reading.stationId)
                    && old.reading.measuredAt==reading.measuredAt) {
                double[] values=old.reading.values.clone(); int mask=old.reading.hourlyMask;
                for(int p=0;p<6;p++) if(present(reading.values[p])) {
                    if(!old.reading.isHourly(p) || reading.isHourly(p)) values[p]=reading.values[p];
                    mask |= reading.hourlyMask & (1<<p);
                }
                if(Arrays.equals(values,old.reading.values) && mask==old.reading.hourlyMask) return;
                backups.set(i,new BackupSample(primaryId,new Sample(reading.measuredAt,reading.stationId,reading.stationName,values,mask)));
                derivedDirty=true; return;
            }
        }
        if(Arrays.stream(reading.values).noneMatch(PollutantHistory::present)) return;
        backups.add(new BackupSample(primaryId,reading)); derivedDirty=true;
        activeHistorySince=Math.min(activeHistorySince,reading.measuredAt);
    }

    public boolean acceptBackupRecovery(Recovery request, String source, List<Sample> reports, long now) {
        if(!matches(request)) return false;
        for(var report:reports) {
            if(!report.stationId.equals(source) || report.measuredAt>request.to || report.measuredAt>now) continue;
            if(report.isHourly(2) && report.measuredAt>0 && report.measuredAt>=Math.max(now-OZONE_WINDOW,request.ozoneFrom)
                    && present(report.values[2])) mergeOzone(report.measuredAt,source,report.values[2]);
            if(report.measuredAt>=request.from) addBackup(request.stationId,report,now);
        }
        prune(now); return true;
    }

    private static int liveMask(String id) {
        // OpenAQ /latest has no averaging-period guarantee; /hours recovery verifies it.
        return id.startsWith("openaq:") ? 0 : id.startsWith("nea:") ? 1 : 63;
    }

    public void add(String country, String countryLabel, String id, String station, String location,
                    long[] measuredTimes, double[] values, long now) {
        if (values.length != LABELS.length || measuredTimes.length != LABELS.length) {
            throw new IllegalArgumentException("Six pollutants and timestamps required");
        }
        prune(now);
        if (!country.equals(countryId)) {
            // Retained station records remain hidden and may be resumed within 24 hours.
            startedAt = now;
            activeStationId = "";
        }
        if (!id.equals(activeStationId)) {
            activeHistorySince = samples.stream().filter(s -> s.stationId.equals(id))
                    .mapToLong(Sample::measuredAt).min().orElse(now);
            activeHistorySince=Math.min(activeHistorySince,backups.stream().filter(b -> b.primaryStationId.equals(id))
                    .mapToLong(b -> b.reading.measuredAt).min().orElse(now));
            activeStationId = id; activeStationSince = now; lastRecoveryCompletedAt = 0;
            lastOzoneRecoveryCompletedAt = 0;
        }
        countryId = country;
        countryName = countryLabel;
        stationName = station;
        locationName = location;
        for (int p = 0; p < LABELS.length; p++) {
            long time = measuredTimes[p];
            if (p == 2 && time > 0 && time <= now && time >= now-OZONE_WINDOW && present(values[p]) && (liveMask(id) & 4) != 0)
                mergeOzone(time,id,values[p]);
            if (time <= 0 || time < now - WINDOW || time > now || !present(values[p])) continue;
            merge(time, id, station, p, values[p], liveMask(id));
        }
        samples.sort(Comparator.comparingLong(Sample::measuredAt));
    }

    private static boolean present(double value) { return Double.isFinite(value) && value >= 0; }

    private void merge(long time, String id, String station, int pollutant, double value, int hourlyMask) {
        int index = -1;
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            if (sample.measuredAt == time && sample.stationId.equals(id)) { index = i; break; }
        }
        mergeAt(index, time, id, station, pollutant, value, hourlyMask);
    }

    /** @param index position of this station/time report, or -1 when it is not stored yet.
     *  @return the position of the report after merging. */
    private int mergeAt(int index, long time, String id, String station, int pollutant, double value, int hourlyMask) {
        int bit = hourlyMask & (1 << pollutant);
        if (pollutant == 2 && bit != 0 && NowCast.hourly(id,pollutant)) mergeOzone(time,id,value);
        if (index >= 0) {
            Sample sample = samples.get(index);
            if (sample.isHourly(pollutant) && bit == 0) return index;
            if (Double.compare(sample.values[pollutant], value) == 0 && (sample.hourlyMask & (1 << pollutant)) == bit) return index;
            derivedDirty = true;
            double[] updated = sample.values.clone();
            updated[pollutant] = value;
            samples.set(index, new Sample(time, id, station, updated, sample.hourlyMask | bit));
            return index;
        }
        double[] values = new double[LABELS.length];
        Arrays.fill(values, Double.NaN);
        values[pollutant] = value;
        derivedDirty = true;
        samples.add(new Sample(time, id, station, values, bit));
        return samples.size() - 1;
    }
    private void mergeOzone(long time, String station, double value) {
        for (int i = 0; i < ozoneCalibration.size(); i++) {
            Ozone old = ozoneCalibration.get(i);
            if (old.measuredAt == time && old.stationId.equals(station)) {
                if (Double.compare(old.value,value) != 0) { ozoneCalibration.set(i,new Ozone(time,station,value)); derivedDirty = true; }
                return;
            }
        }
        ozoneCalibration.add(new Ozone(time,station,value)); derivedDirty = true;
    }

    public record Recovery(String countryId, long sessionStart, String stationId, long stationSince,
                           long from, long to, long ozoneFrom) {
        public Recovery(String country, long session, String station, long since, long from, long to) {
            this(country,session,station,since,from,to,from);
        }
    }

    public boolean needsRecovery(long now) {
        return !activeStationId.isEmpty() && (lastRecoveryCompletedAt <= 0
                || Math.floorDiv(now, NowCast.HOUR) > Math.floorDiv(lastRecoveryCompletedAt, NowCast.HOUR)
                || now < lastRecoveryCompletedAt || now - lastRecoveryCompletedAt >= 15L * 60 * 1000);
    }

    public Recovery beginRecovery(long now) {
        if (!needsRecovery(now)) return null;
        long hour = 60L * 60 * 1000;
        long from = Math.max(now - WINDOW, Math.floorDiv(activeHistorySince, hour) * hour);
        long ozoneFrom = lastOzoneRecoveryCompletedAt <= 0 ? now-OZONE_WINDOW
                : Math.max(now-OZONE_WINDOW, lastOzoneRecoveryCompletedAt-2*hour);
        return new Recovery(countryId, startedAt, activeStationId, activeStationSince, from, now, ozoneFrom);
    }

    public boolean completeRecovery(Recovery request) {
        if (!matches(request)) return false;
        // Failed/interrupted attempts never advance this watermark, including across process death.
        lastRecoveryCompletedAt = Math.max(lastRecoveryCompletedAt, request.to);
        lastOzoneRecoveryCompletedAt = Math.max(lastOzoneRecoveryCompletedAt, request.to);
        return true;
    }

    private boolean matches(Recovery request) {
        return countryId.equals(request.countryId) && startedAt == request.sessionStart
                && activeStationId.equals(request.stationId) && activeStationSince == request.stationSince;
    }

    public boolean acceptRecovery(Recovery request, List<Sample> reports, long now) {
        if (!matches(request)) return false;
        for (Sample report : reports) {
            // Only O3 may be imported before the visible station-session/24h window.
            if (report.stationId.equals(request.stationId) && report.isHourly(2) && NowCast.hourly(report.stationId,2)
                    && report.measuredAt > 0 && report.measuredAt <= request.to && report.measuredAt <= now
                    && report.measuredAt >= Math.max(now-OZONE_WINDOW,request.ozoneFrom) && present(report.values[2])) {
                mergeOzone(report.measuredAt,report.stationId,report.values[2]);
            }
            if (!report.stationId.equals(request.stationId) || report.measuredAt < request.from
                    || report.measuredAt > request.to || report.measuredAt < now - WINDOW
                    || report.measuredAt <= 0 || report.measuredAt > now) continue;
            for (int p = 0; p < LABELS.length; p++) {
                if (present(report.values[p])) merge(report.measuredAt, report.stationId, report.stationName, p, report.values[p], report.hourlyMask);
            }
        }
        prune(now);
        samples.sort(Comparator.comparingLong(Sample::measuredAt));
        return true;
    }
    public void prune(long now) {
        backups.removeIf(b -> b.reading.measuredAt<=0 || b.reading.measuredAt<now-WINDOW || b.reading.measuredAt>now);
        samples.removeIf(sample -> sample.measuredAt <= 0 || sample.measuredAt < now - WINDOW || sample.measuredAt > now);
        nowcasts.removeIf(sample -> sample.measuredAt() <= 0 || sample.measuredAt() < now - WINDOW || sample.measuredAt() > now);
        ozoneCalibration.removeIf(sample -> sample.measuredAt <= 0 || sample.measuredAt < now-OZONE_WINDOW || sample.measuredAt > now);
    }

    /** Preserve calculated inputs after their older raw measurements expire; no >24h raw data is retained. */
    public void updateNowcasts(long now) {
        prune(now);
        if (!derivedDirty) return;
        java.util.Map<String, Sample> anchors = new java.util.LinkedHashMap<>();
        List<Sample> actual=new ArrayList<>(samples);
        for(var backup:backups) actual.add(backup.reading);
        actual.sort(Comparator.comparingLong(Sample::measuredAt));
        for (Sample sample : actual) {
            String key = sample.stationId + ":" + Math.floorDiv(sample.measuredAt, NowCast.HOUR);
            Sample existing = anchors.get(key);
            if (existing == null || sample.measuredAt > existing.measuredAt) anchors.put(key, sample);
        }
        for (Sample sample : anchors.values()) {
            NowCast.Snapshot fresh = NowCast.calculate(actual, ozoneCalibration, nowcasts, sample.measuredAt, sample.stationId, sample.stationName);
            int previous = -1;
            for (int i = 0; i < nowcasts.size(); i++) {
                var old = nowcasts.get(i);
                if (old.stationId().equals(sample.stationId) && Math.floorDiv(old.measuredAt(), NowCast.HOUR)
                        == Math.floorDiv(sample.measuredAt, NowCast.HOUR)) { previous = i; break; }
            }
            if (previous >= 0) {
                var old = nowcasts.get(previous);
                List<NowCast.Input> inputs = new ArrayList<>(fresh.inputs());
                int[] windows = {12,12,336,1,8,24};
                double ozoneEstimate = fresh.ozoneEstimate();
                for (int p = 0; p < 6; p++) {
                    long first = Math.floorDiv(sample.measuredAt, NowCast.HOUR) * NowCast.HOUR - (windows[p]-1L)*NowCast.HOUR;
                    long cutoff = now - (p == 2 ? OZONE_WINDOW : WINDOW);
                    if (first < cutoff && old.inputs().get(p).aqi() >= 0) {
                        inputs.set(p, old.inputs().get(p));
                        if (p == 2) ozoneEstimate = old.ozoneEstimate();
                    }
                }
                nowcasts.set(previous, new NowCast.Snapshot(fresh.measuredAt(), fresh.stationId(), fresh.stationName(), inputs, ozoneEstimate));
            } else nowcasts.add(fresh);
        }
        nowcasts.sort(Comparator.comparingLong(NowCast.Snapshot::measuredAt));
        derivedDirty = false;
    }

    public NowCast.Snapshot latestNowcast(String stationId) {
        for (int i = nowcasts.size()-1; i >= 0; i--) if (nowcasts.get(i).stationId().equals(stationId)) return nowcasts.get(i);
        return null;
    }

    public void write(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(9);
        out.writeUTF(countryId); out.writeUTF(countryName); out.writeUTF(stationName); out.writeUTF(locationName);
        out.writeLong(startedAt);
        out.writeUTF(activeStationId); out.writeLong(activeStationSince); out.writeLong(lastRecoveryCompletedAt);
        out.writeInt(samples.size());
        for (Sample sample : samples) {
            out.writeLong(sample.measuredAt);
            out.writeUTF(sample.stationId); out.writeUTF(sample.stationName);
            out.writeInt(sample.hourlyMask);
            for (double value : sample.values) out.writeDouble(value);
        }
        out.writeInt(nowcasts.size());
        for (var snapshot : nowcasts) {
            out.writeLong(snapshot.measuredAt()); out.writeUTF(snapshot.stationId()); out.writeUTF(snapshot.stationName());
            for (var input : snapshot.inputs()) {
                out.writeDouble(input.concentration()); out.writeInt(input.aqi()); out.writeInt(input.hours()); out.writeUTF(input.method());
            }
            out.writeDouble(snapshot.ozoneEstimate());
        }
        out.writeInt(ozoneCalibration.size());
        for (var ozone : ozoneCalibration) { out.writeLong(ozone.measuredAt); out.writeUTF(ozone.stationId); out.writeDouble(ozone.value); }
        out.writeLong(lastOzoneRecoveryCompletedAt);
        out.writeLong(activeHistorySince);
        out.writeInt(backups.size());
        for(var backup:backups) {
            var s=backup.reading;
            out.writeUTF(backup.primaryStationId); out.writeLong(s.measuredAt);
            out.writeUTF(s.stationId); out.writeUTF(s.stationName); out.writeInt(s.hourlyMask);
            for(double value:s.values) out.writeDouble(value);
        }
        out.flush();
    }

    public static PollutantHistory read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int version = in.readInt();
        if (version < 1 || version > 9) throw new IOException("Unknown history version");
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
        // Decoding runs on every history access: look reports up instead of scanning per value.
        java.util.Map<String, Integer> positions = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            // Migrate v1 refresh snapshots using their stored report time, discarding receipt time.
            if (version == 1) in.readLong();
            long measured = in.readLong();
            String stationId = in.readUTF(), stationName = in.readUTF();
            int hourlyMask = version >= 7 ? in.readInt() : liveMask(stationId);
            String key = measured + "|" + stationId;
            Integer known = positions.get(key);
            int position = known == null ? -1 : known;
            for (int p = 0; p < LABELS.length; p++) {
                double value = in.readDouble();
                if (measured > 0 && present(value)) {
                    position = history.mergeAt(position, measured, stationId, stationName, p, value, hourlyMask);
                }
            }
            if (position >= 0) positions.put(key, position);
        }
        if (version >= 5) {
            int snapshots = in.readInt();
            if (snapshots < 0 || snapshots > 100000) throw new IOException("Invalid index history length");
            for (int i = 0; i < snapshots; i++) {
                long time = in.readLong(); String station = in.readUTF(), name = in.readUTF();
                List<NowCast.Input> inputs = new ArrayList<>();
                for (int p = 0; p < 6; p++) inputs.add(new NowCast.Input(in.readDouble(), in.readInt(), in.readInt(), in.readUTF()));
                double estimate = version >= 6 ? in.readDouble() : inputs.get(2).concentration();
                if (version >= 7 || !station.startsWith("openaq:"))
                    history.nowcasts.add(new NowCast.Snapshot(time, station, name, inputs, estimate));
            }
        }
        if (version >= 6) {
            int ozoneCount = in.readInt();
            if (ozoneCount < 0 || ozoneCount > 100000) throw new IOException("Invalid ozone calibration length");
            for (int i = 0; i < ozoneCount; i++) {
                long time = in.readLong(); String station = in.readUTF(); double value = in.readDouble();
                if (time > 0 && present(value) && NowCast.hourly(station,2) && (version >= 7 || !station.startsWith("openaq:"))) history.mergeOzone(time,station,value);
            }
            history.derivedDirty = version < 7;
            history.lastOzoneRecoveryCompletedAt = in.readLong();
        }
        history.samples.sort(Comparator.comparingLong(Sample::measuredAt));
        if (version < 7 && history.activeStationId.startsWith("openaq:")) {
            history.lastRecoveryCompletedAt = 0;
            history.lastOzoneRecoveryCompletedAt = 0;
        }
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
        if (version >= 8) history.activeHistorySince = in.readLong();
        else {
            history.activeHistorySince = history.samples.stream()
                    .filter(s -> s.stationId.equals(history.activeStationId))
                    .mapToLong(Sample::measuredAt).min().orElse(history.activeStationSince);
            // Older releases recovered only the most recent visit. Repair that gap once.
            history.lastRecoveryCompletedAt = 0;
        }
        if(version>=9) {
            int backups=in.readInt();
            if(backups<0 || backups>100000) throw new IOException("Invalid backup history length");
            for(int i=0;i<backups;i++) {
                String primary=in.readUTF(); long time=in.readLong();
                String source=in.readUTF(),name=in.readUTF(); int mask=in.readInt(); double[] values=new double[6];
                for(int p=0;p<6;p++) values[p]=in.readDouble();
                if(time>0 && source.startsWith("openaq:")) history.backups.add(new BackupSample(primary,new Sample(time,source,name,values,mask)));
            }
        }
        return history;
    }
}

