package com.localairquality.app;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.localairquality.app.background.RefreshScheduler;
import com.localairquality.app.notification.AirQualityNotification;

public final class LocalAirQualityApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        com.localairquality.app.data.HistoryStore.load(this);
        AirQualityNotification.createChannel(this);
        RefreshScheduler.schedule(this);
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            private boolean connected;
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                boolean usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (usable && !connected) {
                    com.localairquality.app.background.RefreshCoordinator.refreshIfStale(LocalAirQualityApp.this);
                }
                connected = usable;
            }
            @Override public void onLost(Network network) { connected = false; }
        });
    }
}


