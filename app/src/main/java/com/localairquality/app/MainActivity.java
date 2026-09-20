package com.localairquality.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.localairquality.app.background.RefreshCoordinator;
import com.localairquality.app.background.RefreshScheduler;
import com.localairquality.app.data.AirQualityReading;
import com.localairquality.app.data.AirQualityRepository;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.data.RefreshFlow;
import com.localairquality.app.notification.AirQualityNotification;
import com.localairquality.app.ui.AirQualityView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity implements AirQualityView.Listener {
    private static final String TAG = "LocalAirQuality";
    private static final int REQUEST_LOCATION = 100;
    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final long RECENT_LOCATION_MILLIS = 15L * 60L * 1000L;
    private static final long LOCATION_TIMEOUT_MILLIS = 12_000L;
    private static final long GEOCODER_TIMEOUT_MILLIS = 5_000L;
    private static final long REFRESH_TIMEOUT_MILLIS = 45_000L;

    private final ExecutorService geocoderExecutor = Executors.newSingleThreadExecutor();
    private final RefreshFlow flow = new RefreshFlow();
    private final List<CancellationSignal> locationCancellations = new ArrayList<>();
    private AirQualityView airQualityView;
    private LocationManager locationManager;
    private Handler mainHandler;
    private LocationListener legacyLocationListener;
    private Runnable locationTimeout;
    private Runnable geocoderTimeout;
    private Runnable refreshTimeout;
    private Future<?> download;
    private Future<?> geocoding;
    private final Runnable automaticUpdate = new Runnable() {
        @Override public void run() {
            if (!flow.isBusy()) {
                RefreshCoordinator.refreshIfStale(MainActivity.this);
                AirQualityReading cached = ReadingStore.loadReading(MainActivity.this);
                if (cached != null && !airQualityView.needsRetry()) airQualityView.showReading(cached, false);
            }
            mainHandler.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        airQualityView = new AirQualityView(this);
        airQualityView.setListener(this);
        setContentView(airQualityView);
        configureWindow();
        mainHandler = new Handler(Looper.getMainLooper());
        locationManager = getSystemService(LocationManager.class);
        RefreshScheduler.schedule(this);

        AirQualityReading cached = ReadingStore.loadReading(this);
        if (cached != null) airQualityView.showReading(cached, false);
        startLocationFlow();
    }

    @Override
    protected void onDestroy() {
        flow.cancel();
        cancelPendingFlow();
        geocoderExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRefreshRequested() {
        startLocationFlow();
    }

    @Override
    public void onActionRequested(AirQualityView.Action action) {
        switch (action) {
            case REQUEST_LOCATION -> requestLocationPermission();
            case ENABLE_LOCATION -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (ActivityNotFoundException ignored) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            }
            case ENTER_API_KEY -> showApiKeyDialog();
            case RETRY -> startLocationFlow();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RefreshScheduler.scheduleRecovery(this);
        mainHandler.post(automaticUpdate);
        if (airQualityView != null && hasLocationPermission() && locationEnabled()
                && airQualityView.needsRetry()) {
            requestCurrentLocation();
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(automaticUpdate);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            if (hasLocationPermission()) requestCurrentLocation();
            else airQualityView.showState("Location access required", "Allow location",
                    AirQualityView.Action.REQUEST_LOCATION);
        } else if (requestCode == REQUEST_NOTIFICATIONS
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            AirQualityReading cached = ReadingStore.loadReading(this);
            if (cached != null) AirQualityNotification.show(this, cached);
        }
    }

    @Override
    public void onPollutantSelected(String pollutant) {
        startActivity(new Intent(this, PollutantHistoryActivity.class).putExtra("pollutant", pollutant));
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(244, 247, 250));
        window.setNavigationBarColor(Color.rgb(244, 247, 250));
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void startLocationFlow() {
        if (!hasLocationPermission()) {
            AirQualityReading cached = ReadingStore.loadReading(this);
            if (cached == null) {
                airQualityView.showState("Location access required", "Allow location",
                        AirQualityView.Action.REQUEST_LOCATION);
            }
            requestLocationPermission();
            return;
        }
        if (!locationEnabled()) {
            airQualityView.showState("Turn on location", "Open location settings",
                    AirQualityView.Action.ENABLE_LOCATION);
            return;
        }
        requestCurrentLocation();
    }

    private void requestLocationPermission() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQUEST_LOCATION);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationEnabled() {
        if (locationManager == null) return false;
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @SuppressWarnings("deprecation")
    private void requestCurrentLocation() {
        if (!hasLocationPermission()) return;
        if (locationManager == null) {
            showLocationUnavailable();
            return;
        }

        cancelPendingFlow();
        int requestId = flow.start();
        airQualityView.showProgress("Finding current location");
        try {
            Location lastKnown = newestLastKnownLocation();
            if (isRecent(lastKnown)) {
                handleLocation(requestId, lastKnown);
                return;
            }

            List<String> providers = enabledLocationProviders();
            if (providers.isEmpty()) {
                useSavedLocationOrError(requestId);
                return;
            }

            if (Build.VERSION.SDK_INT >= 30) {
                for (String provider : providers) {
                    CancellationSignal cancellation = new CancellationSignal();
                    locationCancellations.add(cancellation);
                    locationManager.getCurrentLocation(provider, cancellation, getMainExecutor(),
                            location -> {
                                if (location != null) handleLocation(requestId, location);
                            });
                }
            } else {
                legacyLocationListener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        handleLocation(requestId, location);
                    }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onProviderDisabled(String provider) {}
                };
                for (String provider : providers) {
                    locationManager.requestSingleUpdate(provider, legacyLocationListener, getMainLooper());
                }
            }

            Location fallback = lastKnown;
            locationTimeout = () -> {
                if (!flow.isCurrent(requestId)) return;
                cancelLocationRequests();
                if (fallback != null) handleLocation(requestId, fallback);
                else useSavedLocationOrError(requestId);
            };
            mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MILLIS);
        } catch (SecurityException error) {
            airQualityView.showState("Location access required", "Allow location",
                    AirQualityView.Action.REQUEST_LOCATION);
        } catch (Exception error) {
            useSavedLocationOrError(requestId);
        }
    }

    private List<String> enabledLocationProviders() {
        List<String> providers = new ArrayList<>();
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER);
        }
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER);
        }
        return providers;
    }

    @SuppressLint("MissingPermission")
    private Location newestLastKnownLocation() {
        Location newest = null;
        for (String provider : locationManager.getProviders(true)) {
            Location candidate = locationManager.getLastKnownLocation(provider);
            if (candidate != null && (newest == null || candidate.getTime() > newest.getTime())) {
                newest = candidate;
            }
        }
        return newest;
    }

    private boolean isRecent(Location location) {
        if (location == null || location.getTime() <= 0) return false;
        return Math.abs(System.currentTimeMillis() - location.getTime()) <= RECENT_LOCATION_MILLIS;
    }

    private void handleLocation(int requestId, Location location) {
        if (!flow.isCurrent(requestId)) return;
        if (location == null) {
            useSavedLocationOrError(requestId);
            return;
        }
        if (!flow.acceptLocation(requestId)) return;
        cancelLocationRequests();
        airQualityView.showProgress("Finding local station");
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        String fallbackName = String.format(Locale.getDefault(), "%.3f, %.3f", latitude, longitude);

        geocoderTimeout = () -> beginRefresh(requestId, latitude, longitude, fallbackName);
        mainHandler.postDelayed(geocoderTimeout, GEOCODER_TIMEOUT_MILLIS);
        geocoding = geocoderExecutor.submit(() -> {
            String name = resolveLocationName(latitude, longitude);
            runOnUiThread(() -> beginRefresh(requestId, latitude, longitude, name));
        });
    }

    private void beginRefresh(int requestId, double latitude, double longitude, String name) {
        if (!flow.startDownload(requestId)) return;
        if (geocoderTimeout != null) mainHandler.removeCallbacks(geocoderTimeout);
        geocoderTimeout = null;
        airQualityView.showProgress("Updating air quality");
        refreshData(requestId, latitude, longitude, name);
    }

    private void useSavedLocationOrError(int requestId) {
        if (!flow.isCurrent(requestId)) return;
        cancelLocationRequests();
        ReadingStore.SavedLocation saved = ReadingStore.loadLocation(this);
        if (saved != null) beginRefresh(requestId, saved.latitude(), saved.longitude(), saved.name());
        else showLocationUnavailable();
    }

    private void showLocationUnavailable() {
        airQualityView.showState("Location unavailable", "Try again", AirQualityView.Action.RETRY);
    }

    private void refreshData(int requestId, double latitude, double longitude, String locationName) {

        refreshTimeout = () -> {
            if (!flow.finish(requestId)) return;
            if (download != null) download.cancel(true);
            handleRefreshError(new Exception("Air quality request timed out"));
        };
        mainHandler.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MILLIS);
        download = RefreshCoordinator.refresh(this, latitude, longitude, locationName, new RefreshCoordinator.Callback() {
            @Override
            public void onSuccess(AirQualityReading reading) {
                runOnUiThread(() -> {
                    if (!flow.finish(requestId)) return;
                    finishRefreshTimeout();
                    airQualityView.showReading(reading, false);
                    requestNotificationPermissionIfNeeded();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    if (!flow.finish(requestId)) return;
                    finishRefreshTimeout();
                    handleRefreshError(error);
                });
            }
        });
    }

    private void finishRefreshTimeout() {
        if (refreshTimeout != null) mainHandler.removeCallbacks(refreshTimeout);
        refreshTimeout = null;
    }

    private void cancelPendingFlow() {
        cancelLocationRequests();
        if (download != null) download.cancel(true);
        if (geocoding != null) geocoding.cancel(true);
        download = null;
        geocoding = null;
        if (mainHandler == null) return;
        if (geocoderTimeout != null) mainHandler.removeCallbacks(geocoderTimeout);
        if (refreshTimeout != null) mainHandler.removeCallbacks(refreshTimeout);
        geocoderTimeout = null;
        refreshTimeout = null;
    }

    private void cancelLocationRequests() {
        if (mainHandler != null && locationTimeout != null) {
            mainHandler.removeCallbacks(locationTimeout);
        }
        locationTimeout = null;
        for (CancellationSignal cancellation : locationCancellations) cancellation.cancel();
        locationCancellations.clear();
        if (locationManager != null && legacyLocationListener != null) {
            try {
                locationManager.removeUpdates(legacyLocationListener);
            } catch (SecurityException ignored) {
            }
        }
        legacyLocationListener = null;
    }

    private void handleRefreshError(Exception error) {
        Log.e(TAG, "Air quality refresh failed", error);
        airQualityView.setRefreshing(false);
        if (error instanceof AirQualityRepository.ApiKeyRequiredException) {
            airQualityView.clearReading();
            airQualityView.showState("OpenAQ access required", "Add OpenAQ key",
                    AirQualityView.Action.ENTER_API_KEY);
        } else if (error instanceof AirQualityRepository.NoStationException) {
            airQualityView.clearReading();
            airQualityView.showState(error.getMessage(), "Try again", AirQualityView.Action.RETRY);
        } else {
            airQualityView.showState("Unable to update", "Try again", AirQualityView.Action.RETRY);
        }
    }

    @SuppressWarnings("deprecation")
    private String resolveLocationName(double latitude, double longitude) {
        try {
            List<Address> addresses = new Geocoder(this, Locale.getDefault())
                    .getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                String first = firstNonBlank(address.getSubLocality(), address.getLocality(),
                        address.getSubAdminArea(), address.getAdminArea());
                String country = firstNonBlank(address.getCountryName(), address.getCountryCode());
                if (!first.isBlank() && !country.isBlank() && !first.equalsIgnoreCase(country)) {
                    return first + ", " + country;
                }
                if (!first.isBlank()) return first;
                if (!country.isBlank()) return country;
            }
        } catch (Exception ignored) {
        }
        return String.format(Locale.getDefault(), "%.3f, %.3f", latitude, longitude);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private void showApiKeyDialog() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("OpenAQ API key");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(ReadingStore.openAqKey(this));
        LinearLayout container = new LinearLayout(this);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("OpenAQ API key")
                .setView(container)
                .setPositiveButton("Save", null)
                .setNeutralButton("Get key", (ignored, which) -> openOpenAqRegistration())
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String key = input.getText().toString().trim();
                    if (key.isEmpty()) {
                        input.setError("Required");
                        return;
                    }
                    ReadingStore.saveOpenAqKey(this, key);
                    dialog.dismiss();
                    startLocationFlow();
                }));
        dialog.show();
    }

    private void openOpenAqRegistration() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://explore.openaq.org/register")));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }
}



