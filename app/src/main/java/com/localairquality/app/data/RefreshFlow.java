package com.localairquality.app.data;

/** Main-thread state gate: late or duplicate callbacks cannot restart a completed refresh. */
public final class RefreshFlow {
    private enum Stage { LOCATION, GEOCODING, DOWNLOAD, FINISHED }
    private int generation;
    private Stage stage = Stage.FINISHED;

    public boolean isBusy() { return stage != Stage.FINISHED; }
    public int start() { stage = Stage.LOCATION; return ++generation; }
    public boolean isCurrent(int id) { return id == generation && stage != Stage.FINISHED; }
    public boolean acceptLocation(int id) {
        if (!isCurrent(id) || stage != Stage.LOCATION) return false;
        stage = Stage.GEOCODING;
        return true;
    }
    public boolean startDownload(int id) {
        if (!isCurrent(id) || (stage != Stage.LOCATION && stage != Stage.GEOCODING)) return false;
        stage = Stage.DOWNLOAD;
        return true;
    }
    public boolean finish(int id) {
        if (!isCurrent(id) || stage != Stage.DOWNLOAD) return false;
        stage = Stage.FINISHED;
        return true;
    }
    public void cancel() { generation++; stage = Stage.FINISHED; }
}

