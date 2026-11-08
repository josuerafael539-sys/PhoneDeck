package com.zynolist.phonelink;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class NotificationCollector extends NotificationListenerService {
    private static final int MAX_ITEMS = 80;
    private static final LinkedHashMap<String, JSONObject> ITEMS = new LinkedHashMap<>();
    public static volatile boolean connected = false;

    @Override
    public void onListenerConnected() {
        connected = true;
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Bundle extras = sbn.getNotification().extras;
            CharSequence titleValue = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textValue = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (textValue == null) textValue = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            String title = titleValue == null ? "" : titleValue.toString().trim();
            String text = textValue == null ? "" : textValue.toString().trim();
            if (title.isEmpty() && text.isEmpty()) return;

            JSONObject item = new JSONObject();
            item.put("package", sbn.getPackageName());
            item.put("title", title);
            item.put("text", text);
            item.put("time", sbn.getPostTime());

            synchronized (ITEMS) {
                ITEMS.remove(sbn.getKey());
                ITEMS.put(sbn.getKey(), item);
                while (ITEMS.size() > MAX_ITEMS) {
                    String first = ITEMS.keySet().iterator().next();
                    ITEMS.remove(first);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        synchronized (ITEMS) {
            ITEMS.remove(sbn.getKey());
        }
    }

    public static JSONArray snapshot() {
        JSONArray array = new JSONArray();
        List<JSONObject> copy;
        synchronized (ITEMS) {
            copy = new ArrayList<>(ITEMS.values());
        }
        for (int i = copy.size() - 1; i >= 0; i--) {
            array.put(copy.get(i));
        }
        return array;
    }

    public static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
