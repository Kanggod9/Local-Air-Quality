# Local Air Quality

Android app that displays air quality for your current location, with US AQI+, European AQI, six pollutants, health advice, notifications, a home-screen widget, and 24-hour pollutant/index history. No location search.

## Install

Download the APK from [Releases](https://github.com/Kanggod9/Local-Air-Quality/releases). Version **1.3.3** (version code 11) requires Android 8.0 or newer. Allow location access and, optionally, notifications.

The published APK is the original v1.3.3 build. It uses an Android debug signing certificate, not a production release key. A self-built APK uses your own signing certificate and may not install over this APK. No private signing keys are included.

## Data and history

- Singapore: official NEA reporting-region data via data.gov.sg. These regions are not individual physical station addresses.
- Elsewhere: OpenAQ v3, selecting nearby fixed government/reference monitors within 25 km. Enter your own OpenAQ API key in the app; availability depends on local coverage.
- History uses reported timestamps and retains up to 24 hours. It does not combine concentrations from different stations or report times. Historical recovery is limited to the current known station session; changing countries resets history.
- Background jobs request updates every 15 minutes; actual timing is controlled by Android, network availability, and battery restrictions. Providers may report less frequently.

The US AQI+ calculation applies the 2024 EPA breakpoints to displayed concentrations. European bands use the revised EEA thresholds; PM2.5 of 70 µg/m³ is US AQI+ 161 (Unhealthy) and European band 4 (Poor).

NEA PM10/SO2 are 24-hour values, O3/CO are eight-hour maxima, PM2.5 is one-hourly, and NO2 is a one-hour maximum. Applying thresholds to these available values does not make every value a fully hourly EEA observation. Partial history indices use only pollutants reported at that timestamp. CO is not part of the European index; its tile/history uses US levels.

Health advice is adapted from [EEA health messages](https://airindex.eea.europa.eu/AQI/) and [EPA/AirNow guidance](https://www.airnow.gov/aqi/aqi-basics/). It is general activity guidance, not medical advice.

## Privacy

No developer API key or account credentials are bundled. Location is used to select a nearby monitor; Android's geocoding service and OpenAQ may receive coordinates when used. The OpenAQ key and current-location cache are stored in app-private preferences excluded from backup; history is kept in the app's no-backup directory. The repository excludes device data, local build caches, logs, signing keys, and personal machine paths.

## Build and test

Requirements: JDK 17, Gradle 8.13, Android SDK platform 36 and Build Tools 36.0.0. Configure `ANDROID_HOME` or a local, untracked `local.properties` file with your SDK path. Android Gradle Plugin 8.13.2 is declared in the project.

```sh
gradle testDebugUnitTest lintRelease assembleRelease
```

The APK is generated at `app/build/outputs/apk/release/app-release.apk`. The release build is minified and uses local debug signing for setup without distributing a private key. Build dependencies require internet access on first use; use `--offline` once cached. No Gradle wrapper binary is included.

The repository contains the synced v1.3.3 application sources and tests. Publication-only changes make output paths portable and exclude local/private files; application behavior is unchanged.
