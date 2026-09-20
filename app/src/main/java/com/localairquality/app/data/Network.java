package com.localairquality.app.data;

import org.json.JSONObject;
import com.localairquality.app.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Network {
    private Network() {}

    static JSONObject getJson(String address, Map<String, String> headers) throws Exception {
        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancelled();
            try {
                return getJsonOnce(address, headers);
            } catch (AirQualityRepository.ApiKeyRequiredException error) {
                throw error;
            } catch (IOException error) {
                checkCancelled();
                if (error instanceof HttpFailure failure && !failure.retryable()) throw error;
                lastError = error;
                if (attempt == 0) {
                    try {
                        Thread.sleep(600L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw error;
                    }
                }
            }
        }
        throw lastError == null ? new IOException("Air quality service is unavailable") : lastError;
    }

    private static JSONObject getJsonOnce(String address, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Local-Air-Quality-Android/" + BuildConfig.VERSION_NAME);
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = read(stream);
            if ((status == 401 || status == 403) && headers != null && headers.containsKey("X-API-Key")) {
                throw new AirQualityRepository.ApiKeyRequiredException("OpenAQ API key is not valid");
            }
            if (status < 200 || status >= 300) {
                throw new HttpFailure(status);
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkCancelled();
                body.append(line);
            }
        }
        return body.toString();
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Refresh cancelled");
        }
    }

    private static final class HttpFailure extends IOException {
        private final int status;
        HttpFailure(int status) { super("Air quality service returned " + status); this.status = status; }
        boolean retryable() { return status == 408 || status == 429 || status >= 500; }
    }
}
