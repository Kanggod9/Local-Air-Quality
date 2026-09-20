package com.localairquality.app.data;

/** Activity guidance adapted from EEA health messages and EPA/AirNow activity guides.
 * Compare activity restrictions, not the numerical values of the two different indices.
 */
public final class HealthAdvice {
    private static final long MAX_AGE = 3L * 60 * 60 * 1000;
    private static final String[] ACTIONS = {
            "Enjoy your usual outdoor activities.",
            "Ease back on strenuous outdoor activity if you notice coughing or irritation.",
            "Keep outdoor exercise shorter and gentler. Take more breaks.",
            "Avoid long or strenuous outdoor exercise. Move your workout indoors.",
            "Avoid outdoor physical activity. Choose activities in cleaner indoor air.",
            "Stay in cleaner indoor air and keep activity light."
    };

    private HealthAdvice() {}

    public static Advice forReading(AirQualityReading reading, long now) {
        if (reading == null || reading.availablePollutants() == 0) {
            return new Advice("Current readings unavailable", "Refresh to check local air quality.", "", 0);
        }
        if (reading.measuredAtMillis <= 0 || now - reading.measuredAtMillis > MAX_AGE
                || reading.measuredAtMillis - now > 10 * 60 * 1000L) {
            return new Advice("Readings need an update",
                    "Refresh before planning outdoor exercise. Conditions may have changed.", "", 0);
        }
        int us = AqiCalculator.usAqi(reading).aqi();
        int eu = AqiCalculator.europeanAqi(reading).band();
        int everyone = us > 300 ? 4 : us > 200 ? 3 : us > 150 ? 2 : 0;
        int sensitive = us > 300 ? 5 : us > 200 ? 4 : us > 150 ? 3
                : us > 100 ? 2 : us > 50 ? 1 : 0;
        everyone = Math.max(everyone, eu == 6 ? 2 : eu >= 4 ? 1 : 0);
        sensitive = Math.max(sensitive, eu == 6 ? 4 : eu >= 4 ? 2 : eu == 3 ? 1 : 0);
        String title = reading.availablePollutants() < 6 ? "Health advice · partial data" : "Health advice";
        return new Advice(title, ACTIONS[everyone], ACTIONS[sensitive], Math.max(everyone, sensitive));
    }

    public record Advice(String title, String everyone, String sensitive, int caution) {}
}
