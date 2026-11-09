package com.zynolist.phonelink;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    private final String baseUrl;
    private final String token;

    public ApiClient(String baseUrl, String token) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            throw new IllegalArgumentException("La URL debe empezar por http:// o https://");
        }
        this.baseUrl = clean;
        this.token = token == null ? "" : token.trim();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public JSONObject get(String path, int readTimeoutMs) throws Exception {
        return request("GET", path, null, readTimeoutMs);
    }

    public JSONObject post(String path, JSONObject body, int readTimeoutMs) throws Exception {
        return request("POST", path, body, readTimeoutMs);
    }

    public JSONObject postBytes(String path, byte[] body, String contentType, int readTimeoutMs)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(Math.max(5000, readTimeoutMs));
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-phonedeck-token", token);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    contentType == null ? "application/octet-stream" : contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readAll(stream);
            if (code >= 400) {
                String message = text == null ? "" : text;
                try {
                    message = new JSONObject(text).optString("error", message);
                } catch (Exception ignored) {
                }
                throw new IOException("HTTP " + code + ": " + message);
            }
            if (text == null || text.trim().isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject request(String method, String path, JSONObject body, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(Math.max(5000, readTimeoutMs));
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-phonedeck-token", token);

            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readAll(stream);
            if (code >= 400) {
                String message = text == null ? "" : text;
                try {
                    message = new JSONObject(text).optString("error", message);
                } catch (Exception ignored) {}
                throw new IOException("HTTP " + code + ": " + message);
            }
            if (text == null || text.trim().isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
