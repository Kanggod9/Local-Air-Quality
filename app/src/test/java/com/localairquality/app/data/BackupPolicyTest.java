package com.localairquality.app.data;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public class BackupPolicyTest {
    private static final long NOW=2000*NowCast.HOUR;
    private static AirQualityReading reading(String id,long time,double pm) {
        AirQualityReading r=new AirQualityReading(); r.stationId=id; r.stationName=id; r.countryCode="SG";
        r.measuredAtMillis=time; r.pm25=pm; r.pollutantMeasuredAtMillis[0]=time; r.calculateIndices(); return r;
    }
    private static final BackupPolicy.Fetch FAIL=() -> { throw new IOException("offline"); };
    @Test public void freshPrimaryDoesNotCallBackup() throws Exception {
        var primary=reading("nea:west",NOW,12);
        var result=BackupPolicy.fetch(primary,null,true,()->primary,()->{ fail("Unexpected backup"); return null; },NOW);
        assertSame(primary,result); assertFalse(result.isBackup());
    }
    @Test public void staleHttpSuccessSwitchesToFreshBackup() throws Exception {
        var primary=reading("nea:west",NOW-4*NowCast.HOUR,12);
        var result=BackupPolicy.fetch(primary,null,true,()->primary,()->reading("openaq:1",NOW,160),NOW);
        assertTrue(result.isBackup()); assertEquals("nea:west",result.historyStationId());
        assertEquals("openaq:1",result.stationId); assertEquals(160,result.pm25,0);
        assertEquals("Official readings delayed",result.backupStatus);
    }
    @Test public void outageChecksBackupAndSubsequentRefreshChecksPrimaryAgain() throws Exception {
        var primary=reading("nea:west",NOW-NowCast.HOUR,12);
        var backup=BackupPolicy.fetch(primary,null,true,FAIL,()->reading("openaq:1",NOW,160),NOW);
        assertTrue(backup.isBackup());
        var recovered=reading("nea:west",NOW+NowCast.HOUR,1);
        var result=BackupPolicy.fetch(primary,backup,true,()->recovered,FAIL,NOW+NowCast.HOUR);
        assertFalse(result.isBackup()); assertSame(recovered,result);
    }
    @Test public void missingKeyKeepsClearlyMarkedOldOfficialReading() throws Exception {
        var primary=reading("nea:west",NOW-4*NowCast.HOUR,12);
        var result=BackupPolicy.fetch(primary,primary,false,()->primary,FAIL,NOW);
        assertFalse(result.isBackup()); assertTrue(result.backupKeyRequired);
        assertTrue(result.backupNotice().contains("Add OpenAQ key")); assertEquals(NOW-4*NowCast.HOUR,result.measuredAtMillis);
    }
    @Test public void firstLaunchWithoutKeyAndWithoutOfficialDataRequestsKey() throws Exception {
        var target=reading("nea:west",0,Double.NaN);
        try { BackupPolicy.fetch(target,null,false,FAIL,FAIL,NOW); fail(); }
        catch(AirQualityRepository.ApiKeyRequiredException expected) { assertTrue(expected.getMessage().contains("backup")); }
    }
    @Test public void staleBackupCannotMasqueradeAsNewReading() throws Exception {
        var primary=reading("nea:west",NOW-4*NowCast.HOUR,12);
        var result=BackupPolicy.fetch(primary,null,true,()->primary,()->reading("openaq:1",NOW-5*NowCast.HOUR,160),NOW);
        assertFalse(result.isBackup()); assertEquals(12,result.pm25,0);
        assertTrue(result.backupStatus.contains("No fresh backup"));
    }
    @Test public void trimsStalePollutantsEvenWhenOnePollutantIsFresh() {
        var r=reading("openaq:1",NOW,12); r.no2=400; r.pollutantMeasuredAtMillis[3]=NOW-4*NowCast.HOUR;
        BackupPolicy.freshValues(r,NOW);
        assertEquals(1,r.availablePollutants()); assertTrue(Double.isNaN(r.no2)); assertEquals(2,r.euBand);
    }
    @Test public void futureAndMissingTimestampsAreNotFresh() {
        assertFalse(BackupPolicy.fresh(NOW+1,NOW)); assertFalse(BackupPolicy.fresh(0,NOW));
        assertTrue(BackupPolicy.fresh(NOW-3*NowCast.HOUR,NOW)); assertFalse(BackupPolicy.fresh(NOW-3*NowCast.HOUR-1,NOW));
    }
    @Test public void cancellationNeverStartsFailover() throws Exception {
        var primary=reading("nea:west",NOW,1);
        try { BackupPolicy.fetch(primary,null,true,()->{throw new CancellationException();},()->{fail("Backup after cancellation");return null;},NOW); fail(); }
        catch(CancellationException expected) {}
    }
    @Test public void anOldDifferentLocationIsNeverReused() throws Exception {
        try { BackupPolicy.fetch(reading("nea:west",0,Double.NaN),reading("nea:east",NOW,1),true,FAIL,FAIL,NOW); fail(); }
        catch(IOException expected) {}
    }
    @Test public void backupIdentitySurvivesJsonRestart() throws Exception {
        var r=reading("openaq:1",NOW,160); r.primary=reading("nea:west",NOW-4*NowCast.HOUR,1);
        r.backupStatus="Official source unavailable";
        var restored=AirQualityReading.fromJson(r.toJson().toString());
        assertTrue(restored.isBackup()); assertEquals("nea:west",restored.historyStationId());
        assertEquals("openaq:1",restored.stationId); assertTrue(restored.sourceLabel().contains("backup"));
        assertEquals(160,restored.pm25,0);
    }
}
