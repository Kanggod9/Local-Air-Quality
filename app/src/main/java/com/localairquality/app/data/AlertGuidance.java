package com.localairquality.app.data;

import java.util.ArrayList;
import java.util.List;

/** Plain-language adaptations of the linked official guidance, not official alert declarations. */
public final class AlertGuidance {
    public record Section(String title, String text) {}
    public record Source(String title, String url) {}
    public record Details(List<Section> sections, List<Source> sources) {}
    private AlertGuidance() {}

    public static Details forReading(AirQualityReading reading, long now) {
        if (reading == null || !reading.alert.active()) return new Details(List.of(
                new Section("No active alert", "The current station no longer has an alert that meets the eight-hour rule."
                        + " Check the latest AQIs before planning outdoor activities.")), List.of());
        var alert = reading.alert;
        List<Section> sections = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        String reason = switch (alert.level()) {
            case RED -> "European AQI was Poor or higher in " + alert.europeanHours() + " recorded hours.";
            case DEEP_RED -> counts(alert, "Unhealthy", "Very Poor");
            case PURPLE -> counts(alert, "Very Unhealthy", "Extremely Poor");
            case BLACK -> "US AQI was Hazardous in " + alert.usHours() + " recorded hours.";
            default -> "";
        };
        sections.add(new Section("Why this alert is shown", reason + "\n\n"
                + (alert.windowHours()==8 ? "Window: the recent eight hourly slots at the current station."
                    + " A higher alert last reached its threshold eight or more recorded hours ago, so the recent window was reassessed."
                    : "Window: the last 24 hours at the current station. Eight or more distinct hours are required;"
                    + " they need not be consecutive.")
                + " Higher levels count toward lower thresholds."
                + " US and European hours are counted separately, never added together."
                + " The highest qualifying alert is shown. Missing hours are not counted as clean air."
                + "\n\nLast matching reading: "+alert.timeLabel()+". This time is relative to the latest recorded hour,"
                + " not the phone clock or the time of the last refresh. The source label uses the index or indices"
                + " that most recently reached this level and independently met the eight-hour requirement."
                + "\n\nThis is the app’s sustained-pollution reminder, not an official emergency declaration"
                + " or a measurement of your personal exposure."));

        var us = AqiCalculator.usAqi(reading);
        var eu = AqiCalculator.europeanAqi(reading);
        String current = "US AQI+: " + (reading.availablePollutants() == 0 ? "Unavailable" : us.aqi()+" · "+us.level())
                + "\nEuropean AQI: " + (eu.band() == 0 ? "Unavailable" : eu.band()+" of 6 · "+eu.level());
        current += "\n\nThe latest reading may be better or worse than the hours that triggered the alert."
                + " The advice below uses the more cautious of the alert level and the latest reported categories."
                + " Current main pollutants are not necessarily the pollutants that dominated earlier alert hours.";
        if (reading.measuredAtMillis <= 0 || now-reading.measuredAtMillis > 3*NowCast.HOUR
                || reading.measuredAtMillis > now+10*60_000L)
            current += "\n\nReadings need an update: conditions may have changed. Refresh the app and check local advisories.";
        current += "\n\nEuropean AQI applies EEA concentration bands to the station’s available readings."
                + " Averaging periods may differ; this is not the official hourly EEA index. CO is US-only.";
        if (reading.availablePollutants() < 6)
            current += "\n\nPartial coverage: some pollutant readings are missing."
                    + " Other pollutants may be worse than the available readings suggest.";
        sections.add(new Section("Latest conditions",current));

        if (reading.availablePollutants() > 0) {
            addPollutant(sections,sources,reading,us.pollutant(),"US AQI+",now);
            if (eu.band() > 0 && !eu.pollutant().equals(us.pollutant()))
                addPollutant(sections,sources,reading,eu.pollutant(),"European AQI",now);
            else if (eu.band() > 0) {
                int index = sections.size()-1;
                var section = sections.get(index);
                sections.set(index,new Section("Current main pollutant · US AQI+ and European AQI",section.text()));
            }
        }

        int severity = Math.max(alert.level().severity, currentSeverity(us.aqi(),eu.band()));
        sections.add(new Section("Advice for everyone", switch (severity) {
            case 1 -> "Cut back on strenuous outdoor activity if your eyes or throat feel irritated or you start coughing."
                    + " Choose an easier activity and take breaks. Check the latest readings before a long outdoor session.";
            case 2 -> "Shorten demanding outdoor activities and lower the intensity. Take extra breaks; move exercise"
                    + " to cleaner indoor air or postpone it if symptoms occur. Plan essential outdoor tasks for a better-air period.";
            case 3 -> "Avoid prolonged or strenuous outdoor exercise. Move workouts indoors where air is cleaner,"
                    + " or reschedule. Limit nonessential time outdoors and keep necessary trips brief.";
            default -> "Avoid outdoor physical activity. Spend time in a safe place with cleaner air and postpone"
                    + " nonessential outdoor tasks. Follow local public-health or emergency instructions.";
        }));
        sections.add(new Section("Extra care for sensitive groups", switch (severity) {
            case 1 -> "People with asthma, other breathing problems or heart conditions should reduce outdoor exertion,"
                    + " especially when symptoms appear. Check on children and older adults.";
            case 2 -> "Avoid long or demanding outdoor activity if you have heart or lung disease."
                    + " Move activities to cleaner indoor air. Children, older adults and outdoor workers may need extra breaks.";
            case 3 -> "Sensitive groups should avoid outdoor physical activity and use cleaner indoor space."
                    + " Arrange indoor alternatives for children and check on older people and those with heart or lung conditions.";
            default -> "Sensitive groups should remain in cleaner indoor air and keep activity light."
                    + " Ask your healthcare provider about precautions suited to your condition.";
        }));
        source(sources,"EEA health messages","https://airindex.eea.europa.eu/AQI/");
        source(sources,"AirNow AQI and health","https://www.airnow.gov/aqi/aqi-basics/");
        source(sources,"AirNow particle-pollution activity guide","https://document.airnow.gov/air-quality-guide-for-particle-pollution.pdf");

        sections.add(new Section("Precautions and when to get help",
                "Stop exertion and move to cleaner air if coughing, wheezing or breathing discomfort develops."
                        + " Follow your prescribed asthma action plan and keep prescribed reliever medicine available;"
                        + " do not change treatment based on this app."
                        + "\n\nGet urgent medical help for severe breathing difficulty, chest pain, fainting or confusion."
                        + " Do not wait for the alert level to increase."
                        + "\n\nIndoor air is not automatically cleaner. Keep cool; do not seal yourself in an overheated room."
                        + " During smoke, use a safe cleaner-air or cooling location if your home cannot stay cool or smoke keeps entering."
                        + " Always follow evacuation instructions. Station readings do not detect indoor CO leaks."));
        source(sources,"EPA cleaner-air room guidance","https://www.epa.gov/emergencies-iaq/create-clean-room-protect-indoor-air-quality-during-wildfire");
        source(sources,"MedlinePlus urgent chest-pain guidance","https://medlineplus.gov/ency/article/003079.htm");
        return new Details(List.copyOf(sections),List.copyOf(sources));
    }

    private static String counts(AirQualityAlert.Result alert, String usLevel, String euLevel) {
        return "US AQI: " + alert.usHours()+" hours at "+usLevel+" or higher"
                + qualification(alert.usHours(),alert.us())
                + "\nEuropean AQI: "+alert.europeanHours()+" hours at "+euLevel+" or higher"
                + qualification(alert.europeanHours(),alert.european());
    }
    private static String qualification(int count,boolean selected) {
        return selected ? " — qualifies." : count<8 ? " — fewer than 8 hours."
                : " — eight-hour requirement met, but not the most recent triggering source.";
    }
    static int currentSeverity(int us, int eu) {
        int usSeverity = us>=301 ? 4 : us>=201 ? 3 : us>=151 ? 2 : 0;
        int euSeverity = eu>=6 ? 3 : eu>=5 ? 2 : eu>=4 ? 1 : 0;
        return Math.max(usSeverity,euSeverity);
    }
    private static void addPollutant(List<Section> sections,List<Source> sources,AirQualityReading r,
                                     String pollutant,String index,long now) {
        String body = pollutant+" · "+r.pollutantValue(pollutant);
        int position = java.util.Arrays.asList(PollutantHistory.LABELS).indexOf(pollutant);
        long time = position<0 ? 0 : r.pollutantMeasuredAtMillis[position];
        if (time > 0) body += "\nReported "+java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new java.util.Date(time));
        if (time > 0 && (now-time>3*NowCast.HOUR || time>now+10*60_000L)) body += "\nThis pollutant needs an update.";
        body += "\n\n";
        body += switch (pollutant) {
            case "PM2.5", "PM10" -> "Particles can irritate the lungs and aggravate heart or lung conditions."
                    + " Reduce exposure to smoke and dust. Avoid indoor smoking, candles and incense."
                    + " Use a suitably sized, non-ozone-producing particle air cleaner."
                    + "\n\nIf an outdoor trip is unavoidable during particle pollution, a well-fitting NIOSH-approved N95"
                    + " may reduce particle exposure, but does not filter gases or make hazardous air safe."
                    + " People with heart or lung disease should ask a clinician about respirator use.";
            case "O3" -> "Ozone can irritate airways and aggravate asthma, particularly during outdoor exercise."
                    + " Reschedule exercise for lower-ozone periods; mornings are often better, but check current readings."
                    + " Indoor ozone is often lower. Avoid devices that generate ozone."
                    + " N95 particle respirators do not protect against ozone gas.";
            case "NO2" -> "Nitrogen dioxide irritates airways and can worsen asthma. Children, older adults and people"
                    + " with asthma are more vulnerable. Avoid exertion near heavy traffic or fuel-burning sources;"
                    + " choose a route away from busy roads. N95 particle respirators do not filter this gas.";
            case "SO2" -> "Sulphur dioxide can make breathing difficult, especially for people with asthma and children."
                    + " Reduce exertion and avoid affected areas near combustion or industrial emissions."
                    + " Follow local warnings during industrial incidents or volcanic emissions."
                    + " N95 particle respirators do not filter this gas.";
            case "CO" -> "Carbon monoxide reduces oxygen delivery to the heart and brain. People with heart disease"
                    + " are especially vulnerable during exertion. Avoid exhaust and fuel-burning equipment."
                    + " CO has no smell: do not rely on odour, a particle filter or an N95."
                    + "\n\nIf a CO alarm sounds or poisoning is suspected, leave for fresh air immediately and call local"
                    + " emergency services. Do not simply close windows and stay inside. Never operate a generator"
                    + " or charcoal grill in a home or garage.";
            default -> "Check official local advice before planning outdoor activity.";
        };
        sections.add(new Section("Current main pollutant · "+index,body));
        switch (pollutant) {
            case "PM2.5", "PM10" -> source(sources,"EPA cleaner-air room guidance","https://www.epa.gov/emergencies-iaq/create-clean-room-protect-indoor-air-quality-during-wildfire");
            case "O3" -> source(sources,"AirNow ozone activity guide","https://document.airnow.gov/air-quality-guide-for-ozone.pdf");
            case "NO2" -> source(sources,"EPA nitrogen dioxide health effects","https://www.epa.gov/no2-pollution/basic-information-about-no2");
            case "SO2" -> source(sources,"EPA sulphur dioxide health effects","https://www.epa.gov/so2-pollution/sulfur-dioxide-basics");
            case "CO" -> {
                source(sources,"EPA outdoor carbon monoxide","https://www.epa.gov/co-pollution/basic-information-about-carbon-monoxide-co-outdoor-air-pollution");
                source(sources,"EPA indoor carbon monoxide safety","https://www.epa.gov/indoor-air-quality-iaq/carbon-monoxides-impact-indoor-air-quality");
            }
        }
        source(sources,"AirNow respirator limitations","https://www.airnow.gov/sites/default/files/2021-09/wildfire-smoke-guide.pdf");
    }
    private static void source(List<Source> sources,String title,String url) {
        if (sources.stream().noneMatch(item -> item.url().equals(url))) sources.add(new Source(title,url));
    }
}
