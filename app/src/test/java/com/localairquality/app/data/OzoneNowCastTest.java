package com.localairquality.app.data;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class OzoneNowCastTest {
    private static double[] constant(double value) { double[] h=new double[336]; Arrays.fill(h,value); return h; }
    private static OzoneNowCast.Result calculate(double[] h) { return OzoneNowCast.calculate(h,Double.NaN,Double.NaN); }
    @Test public void officialEpaFixtureMatchesIndependentFullRankSvdReference() throws Exception {
        double[] h=new double[336]; int i=0;
        try(var stream=getClass().getResourceAsStream("/history/epa-ozone-example.csv");
            var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8))) {
            reader.readLine(); String line;
            while((line=reader.readLine())!=null) { String value=line.split(",")[3]; h[i++]=value.equals("NULL")?Double.NaN:Double.parseDouble(value); }
        }
        assertEquals(336,i);
        var result=calculate(h);
        assertEquals("O3 NowCast · PLS",result.method());
        // NumPy SVD on EPA's example, independent of this Java PLS implementation; rank96.
        assertEquals(19.584490914156245,result.ppb(),1e-6);
        assertEquals(333,result.validHours());
    }
    @Test public void constantAndAllZeroSeriesAreStable() {
        assertEquals(40,calculate(constant(40)).ppb(),1e-8);
        assertEquals(0,calculate(constant(0)).ppb(),0);
        assertTrue(Double.isNaN(calculate(constant(Double.NaN)).ppb()));
    }
    @Test public void incompleteHistoryUsesOfficialSurrogateAndLatestOfThreeHours() {
        double[] h=constant(Double.NaN); h[335]=40;
        assertEquals(38.5,calculate(h).ppb(),1e-9);
        h[335]=Double.NaN; h[334]=40;
        assertTrue(calculate(h).method().contains("1h ago"));
        h[334]=Double.NaN; h[333]=40;
        assertEquals(38.5,calculate(h).ppb(),1e-9);
        h[333]=Double.NaN; h[332]=40;
        assertTrue(Double.isNaN(calculate(h).ppb()));
    }
    @Test public void eightConsecutiveMissingHoursUseSurrogate() {
        double[] h=constant(40); Arrays.fill(h,100,108,Double.NaN);
        assertTrue(calculate(h).method().startsWith("EPA surrogate"));
        h[107]=40;
        assertEquals("O3 NowCast · PLS",calculate(h).method());
    }
    @Test public void insufficientCenteredMeansUseSurrogateEvenWithEnoughHourlyData() {
        double[] h=constant(40);
        for(int i=96;i<300;i++) if(i%8<3) h[i]=Double.NaN;
        assertTrue(Arrays.stream(h).filter(Double::isFinite).count()>=252);
        assertTrue(calculate(h).method().startsWith("EPA surrogate"));
    }
    @Test public void completeHistoryMissingCurrentUsesRecordedNowcastNotRawHour() {
        double[] h=constant(40); h[335]=Double.NaN;
        assertEquals(27,OzoneNowCast.calculate(h,27,26).ppb(),0);
        h[334]=Double.NaN;
        assertEquals(26,OzoneNowCast.calculate(h,27,26).ppb(),0);
        h[333]=Double.NaN;
        assertTrue(Double.isNaN(OzoneNowCast.calculate(h,27,26).ppb()));
    }
    @Test public void centeredTargetNeedsSixActualValuesAndDoesNotInventFutureHours() {
        double[] h=constant(40); h[100]=Double.NaN; h[101]=Double.NaN;
        assertEquals(40,OzoneNowCast.centeredMeans(h)[100],0);
        h[102]=Double.NaN;
        assertTrue(Double.isNaN(OzoneNowCast.centeredMeans(h)[100]));
        assertTrue(Double.isNaN(OzoneNowCast.centeredMeans(h)[335]));
    }
    @Test public void imputationDoesNotMutateObservationsOrUseImputedNeighbors() {
        double[] h=constant(10); h[100]=Double.NaN; h[101]=Double.NaN; h[102]=30;
        double[] filled=OzoneNowCast.impute(h);
        assertTrue(Double.isNaN(h[100])); assertTrue(Double.isNaN(h[101]));
        double expected=(10*.5+30*.25+10*.25+10*.125+10*.125+10*.0625+10*.0625)/1.375;
        assertEquals(expected,filled[100],1e-9);
    }
}
