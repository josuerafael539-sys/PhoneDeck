package com.zynolist.phonelink;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class Prefs {
    private static final String NAME = "phonelink";

    private Prefs() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String serverUrl(Context context) {
        return get(context).getString("server_url", "").trim();
    }

    public static List<String> serverUrls(Context context) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : serverUrl(context).split("[,\\n\\s]+")) {
            String value = part.trim();
            if (!value.isEmpty()) unique.add(value);
        }
        return new ArrayList<>(unique);
    }

    public static String token(Context context) {
        return get(context).getString("token", "").trim();
    }

    public static boolean consented(Context context) {
        return get(context).getBoolean("consented", false);
    }

    public static boolean enabled(Context context) {
        return get(context).getBoolean("enabled", false);
    }

    public static void saveConfig(Context context, String url, String token, boolean consent) {
        get(context).edit()
                .putString("server_url", url.trim())
                .putString("token", token.trim())
                .putBoolean("consented", consent)
                .apply();
    }

    public static void setEnabled(Context context, boolean enabled) {
        get(context).edit().putBoolean("enabled", enabled).apply();
    }

    public static String deviceId(Context context) {
        SharedPreferences prefs = get(context);
        String id = prefs.getString("device_id", "");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static long lastContact(Context context) {
        return get(context).getLong("last_contact", 0L);
    }
}
