package com.localairquality.app.data;

import java.util.*;

/** EPA 2019 ozone NowCast decision tree, with the 2021 centered-mean completeness check.
 * See USEPA/O3-Nowcast GetNowcast.r and NowcastFun.r. Input/output: ppb.
 * All imputation is local to model predictors; observed concentrations are never altered.
 */
public final class OzoneNowCast {
    public static final int HOURS = 336, LAGS = 96;
    private OzoneNowCast() {}
    public record Result(double ppb, int validHours, String method) {}

    public static Result calculate(double[] chronological, double previous, double twoHoursAgo) {
        if (chronological.length != HOURS) throw new IllegalArgumentException("336 fixed hourly slots required");
        double[] h = chronological.clone();
        int valid = 0, gap = 0, longest = 0;
        double sum = 0;
        for (int i = 0; i < h.length; i++) {
            if (present(h[i])) { valid++; sum += h[i]; gap = 0; }
            else { h[i] = Double.NaN; longest = Math.max(longest, ++gap); }
        }
        if (valid == 0) return new Result(Double.NaN, 0, "O3 hourly data unavailable");
        double[] target = centeredMeans(h);
        int missingTargets = 0;
        for (int i = LAGS-1; i < HOURS; i++) if (!present(target[i])) missingTargets++;
        if (valid < 252 || longest > 7 || missingTargets > (HOURS-LAGS+1)*0.25) {
            for (int age = 0; age < 3; age++) if (present(h[HOURS-1-age]))
                return new Result(.85*h[HOURS-1-age]+4.5, valid, "EPA surrogate · " + age + "h ago");
            return new Result(Double.NaN, valid, "O3 needs a recent hour");
        }
        if (sum == 0) return new Result(0, valid, "O3 NowCast · zero series");
        if (!present(h[HOURS-1])) {
            if (present(h[HOURS-2])) return new Result(previous, valid, "O3 NowCast · previous hour");
            if (present(h[HOURS-3])) return new Result(twoHoursAgo, valid, "O3 NowCast · 2h ago");
            return new Result(Double.NaN, valid, "O3 needs a recent hour");
        }
        double[] imputed = impute(h);
        List<double[]> rows = new ArrayList<>();
        List<Double> responses = new ArrayList<>();
        for (int i = LAGS-1; i < HOURS; i++) if (present(target[i])) {
            rows.add(Arrays.copyOfRange(imputed, i-LAGS+1, i+1)); responses.add(target[i]);
        }
        double[] response = new double[responses.size()];
        for (int i = 0; i < response.length; i++) response[i] = responses.get(i);
        double prediction = predict(rows.toArray(new double[0][]), response, Arrays.copyOfRange(imputed, HOURS-LAGS, HOURS));
        return new Result(Double.isFinite(prediction) ? Math.max(0, prediction) : Double.NaN, valid, "O3 NowCast · PLS");
    }

    static double[] centeredMeans(double[] h) {
        double[] means = new double[HOURS]; Arrays.fill(means, Double.NaN);
        // R rollapply list(-4:3), not a trailing 8h average. End targets are unavailable.
        for (int i = 4; i < HOURS-3; i++) {
            double sum = 0; int n = 0;
            for (int j = i-4; j <= i+3; j++) if (present(h[j])) { sum += h[j]; n++; }
            if (n >= 6) means[i] = sum/n;
        }
        return means;
    }
    static double[] impute(double[] h) {
        double[] result = h.clone();
        for (int i = 0; i < h.length; i++) if (!present(h[i])) {
            for (int radius = 4; radius < h.length; radius++) {
                int count = 0; double sum = 0, weights = 0;
                for (int j = Math.max(0,i-radius); j <= Math.min(h.length-1,i+radius); j++) if (present(h[j])) {
                    double w = Math.scalb(1.0, -Math.abs(i-j)); count++; sum += w*h[j]; weights += w;
                }
                if (count >= 2) { result[i] = sum/weights; break; }
            }
        }
        return result;
    }

    /** Centered, unscaled PLS1 by orthogonal score deflation (NIPALS), up to all 96 components.
     * Equivalent to the reference mvr default kernel-PLS fit for one response. Stops at numerical
     * rank for constant/collinear streams instead of dividing by zero in exhausted components.
     */
    static double predict(double[][] observations, double[] responses, double[] current) {
        int n = observations.length, p = current.length;
        if (n < 2 || responses.length != n) return Double.NaN;
        double[][] x = new double[n][p]; double[] y = responses.clone(), query = current.clone();
        double meanY = Arrays.stream(y).average().orElse(0);
        for (int j = 0; j < p; j++) {
            double mean = 0; for (double[] row : observations) mean += row[j]/n;
            for (int i = 0; i < n; i++) x[i][j] = observations[i][j]-mean;
            query[j] -= mean;
        }
        for (int i = 0; i < n; i++) y[i] -= meanY;
        double prediction = meanY, initialNorm = -1;
        for (int component = 0; component < Math.min(p,n-1); component++) {
            double[] w = new double[p];
            for (int i = 0; i < n; i++) for (int j = 0; j < p; j++) w[j] += x[i][j]*y[i];
            double norm = Math.sqrt(dot(w,w));
            if (initialNorm < 0) initialNorm = norm;
            if (!Double.isFinite(norm) || norm <= Math.max(1e-12,initialNorm*1e-12)) break;
            for (int j = 0; j < p; j++) w[j] /= norm;
            double[] scores = new double[n];
            for (int i = 0; i < n; i++) scores[i] = dot(x[i],w);
            double ss = dot(scores,scores);
            if (ss < 1e-20) break;
            double q = dot(scores,y)/ss;
            double currentScore = dot(query,w);
            prediction += currentScore*q;
            for (int j = 0; j < p; j++) {
                double loading = 0;
                for (int i = 0; i < n; i++) loading += x[i][j]*scores[i]/ss;
                for (int i = 0; i < n; i++) x[i][j] -= scores[i]*loading;
                query[j] -= currentScore*loading;
            }
            for (int i = 0; i < n; i++) y[i] -= scores[i]*q;
        }
        return prediction;
    }
    private static double dot(double[] a, double[] b) { double result = 0; for (int i = 0; i < a.length; i++) result += a[i]*b[i]; return result; }
    private static boolean present(double value) { return AirQualityReading.isPresent(value); }
}
