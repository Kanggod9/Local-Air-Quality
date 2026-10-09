# AQI calculations — v1.5.3

## Display

The original US AQI+ and European AQI cards keep their size and order. Notifications and widgets still use those original indices. The additional US NowCast card is below them. Main-page pollutant values are unchanged.

Tap US NowCast to see its last 24 hours and the concentrations used for calculation. Tap a chart point to inspect that time, including its main pollutant. European AQI history matches the US AQI+ history page: a chart and a tapped-point summary with the main pollutant and concentration.

## US NowCast

This is a separate calculation from the existing hourly US AQI+ card. It uses station-local hourly data, not averages across different stations. Missing values remain missing, not zero. The largest available pollutant sub-index determines the result; incomplete coverage is marked Partial.

### Particles: PM2.5 and PM10

For up to 12 hourly concentrations, newest first, set `w = max(0.5, minimum / maximum)`. The concentration is `sum(c[i] * w^i) / sum(w^i)` over available hours. Actual hour positions are retained across gaps. At least two of the newest three hours must exist. An all-zero valid series returns zero.

The resulting concentration is converted using current EPA breakpoints, including the 2024 PM2.5 update. PM2.5 is truncated to one decimal and PM10 to an integer before linear interpolation; the sub-index is rounded to an integer. Values above AQI 500 are extrapolated rather than capped.

### Ozone

The implementation follows EPA's current ozone model, introduced in 2019, rather than the obsolete simple weighted-ozone formula. Its decision rules and regression inputs follow the [EPA reference implementation](https://github.com/USEPA/O3-Nowcast), including its later completeness check.

The app keeps 336 hourly slots for the same station. Model eligibility requires at least 252 valid hours, no missing run longer than seven hours, and no more than 25% missing centered targets among the 241 eligible rows. Targets are centered eight-hour means (offsets -4 through +3), each requiring six observations. End targets without a complete window are excluded.

The model predicts an eight-hour-equivalent concentration from 96 hourly predictors using centered, unscaled partial least squares. Java uses PLS1 score deflation, up to 96 components, with a numerical-rank stopping guard. Predictor gaps alone are filled with the reference exponential moving-neighbor procedure; stored measurements are never replaced. Negative predictions are clamped to zero.

If calibration is insufficient, the EPA surrogate uses the latest valid observation from the newest three hours: `0.85 * ozone_ppb + 4.5`. If an otherwise eligible series lacks the current hour, the reference decision tree uses the recorded previous-hour or two-hours-ago NowCast when the corresponding raw hour exists. Otherwise ozone is unavailable.

The estimated concentration uses eight-hour ozone breakpoints, which end at AQI 300. A qualifying one-hour ozone sub-index can override it; one-hour breakpoints supply higher categories. The numerical regression was checked against an independent calculation using EPA's example dataset, not certified by EPA.

### Other gases

CO uses an eight-hour mean with at least six valid hours. NO2 uses the current hour. SO2 uses hourly breakpoints through AQI 200; higher categories require a 24-hour mean with at least 18 hours. A high SO2 hour with a valid daily mean below 305 ppb is limited to 200.

Gas mass concentrations are converted to mixing ratios using 24.45 L/mol (25 °C, 1 atm) and molecular mass. Concentrations are truncated to the pollutant's EPA precision before interpolation. These conversions assume standard conditions, not measured local pressure and temperature.

Source: [EPA technical assistance document, May 2026](https://document.airnow.gov/technical-assistance-document-for-the-reporting-of-daily-air-quailty.pdf).

## European AQI: concentration-only app variant

The app applies the latest EEA concentration bands to every available PM2.5, PM10, O3, NO2 and SO2 reading from the station. The worst available category sets the index. CO is excluded because it has no EEA bands. Missing readings are not invented, and reports from different stations or times are not combined in a history point.

The app does not require these readings to be hourly means or calculate a new rolling European average. Station-reported rolling means and maxima may also contribute. This concentration-only variant is not the official hourly EEA index, whose methodology requires hourly means. This change does not relax the separate US NowCast hourly-data requirements.

Upper concentration limits in µg/m³:

| Pollutant | Good | Fair | Moderate | Poor | Very poor |
| --- | ---: | ---: | ---: | ---: | ---: |
| PM2.5 | 5 | 15 | 50 | 90 | 140 |
| PM10 | 15 | 45 | 120 | 195 | 270 |
| O3 | 60 | 100 | 120 | 160 | 180 |
| NO2 | 10 | 25 | 60 | 100 | 150 |
| SO2 | 20 | 40 | 125 | 190 | 275 |

Above the final limit is Extremely poor. The displayed 1–6 value is a category number, not a continuous US-style score. For example, PM2.5 of 70 µg/m³ is Poor under this latest standard.

Sources: [EEA methodology](https://airindex.eea.europa.eu/AQI/) and [EEA revision report](https://www.eionet.europa.eu/etcs/etc-he/products/etc-he-products/etc-he-reports/etc-he-report-2024-17-eeas-revision-of-the-european-air-quality-index-bands).

## Data and retention limits

- OpenAQ NowCast uses the verified `/hours` endpoint. Unverified `/latest` readings cannot replace verified hourly calculation inputs. See [OpenAQ measurements documentation](https://docs.openaq.org/resources/measurements).
- Singapore's current NEA feeds supply a true hourly mean for PM2.5, but the other supplied fields are rolling means or maxima. Those other fields remain excluded from NowCast; they contribute to the concentration-only European AQI and remain visible unchanged in the main pollutant tiles. Partial means a required supported reading is unavailable, not that European readings failed an hourly-mean check.
- All visible charts, non-ozone raw observations and calculated index snapshots expire after 24 hours. Only ozone calibration observations may remain up to 14 days; they are not shown in older chart history. Cleanup runs when history is read, recorded or updated. No background cleanup can run while Android force-stops the app.
- A recorded NowCast keeps its calculated inputs while its snapshot remains within 24 hours, even after older source observations expire. Charts show only the current station. A newly selected station starts a fresh chart; returning to a station with a record still within 24 hours resumes that station's chart and retrieves missed reports. If its last record has expired, it starts fresh. Other stations' retained records stay hidden and expire normally, including across country changes. Ozone calibration alone does not qualify a station's chart for resumption.
- Two weeks of storage does not guarantee model eligibility: the station must actually provide enough valid hourly ozone. Until then, the reference surrogate or an unavailable state is used.
