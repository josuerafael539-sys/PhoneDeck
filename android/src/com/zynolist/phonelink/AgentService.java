package com.zynolist.phonelink;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AgentService extends Service {
    public static final String ACTION_START = "com.zynolist.phonelink.action.START";
    public static final String ACTION_STOP = "com.zynolist.phonelink.action.STOP";
    private static final String CHANNEL_ID = "phonelink_status";
    private static final int NOTIFICATION_ID = 4301;
    public static volatile boolean running = false;

    private volatile boolean shouldRun = false;
    private Thread worker;
    private long lastHeartbeatAt = 0;
    private String notificationText = "Iniciando…";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "PhoneLink", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Conexión con tu PC. La notificación permanece visible mientras el envío está activo.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (intent == null && (!Prefs.enabled(this) || !Prefs.consented(this))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(notificationText));
        if (!shouldRun) {
            shouldRun = true;
            running = true;
            worker = new Thread(this::runLoop, "phonelink-agent");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shouldRun = false;
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private void shutdown() {
        shouldRun = false;
        running = false;
        Prefs.setEnabled(this, false);
        if (worker != null) worker.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private List<ApiClient> clientsFromPrefs() {
        List<ApiClient> clients = new ArrayList<>();
        String token = Prefs.token(this);
        for (String url : Prefs.serverUrls(this)) {
            try {
                clients.add(new ApiClient(url, token));
            } catch (Exception e) {
                Log.w("PhoneLink", "URL inválida: " + url + " (" + e.getMessage() + ")");
            }
        }
        return clients;
    }

    private void runLoop() {
        int active = 0;
        int failures = 0;
        boolean registered = false;

        while (shouldRun) {
            List<ApiClient> clients = clientsFromPrefs();
            if (clients.isEmpty()) {
                setNotificationText("Configuración inválida");
                if (!sleepQuietly(15000)) break;
                continue;
            }
            if (active >= clients.size()) {
                active = 0;
                registered = false;
            }
            ApiClient api = clients.get(active);
            try {
                if (!registered) {
                    api.post("/api/agent/register", registerBody(), 20000);
                    registered = true;
                }
                long now = System.currentTimeMillis();
                JSONObject response;
                if (now - lastHeartbeatAt > 15000 || failures > 0) {
                    response = api.post("/api/agent/heartbeat", heartbeatBody(), 25000);
                    lastHeartbeatAt = now;
                } else {
                    response = api.get("/api/agent/poll?deviceId="
                            + Uri.encode(Prefs.deviceId(this)) + "&wait=20", 35000);
                }
                failures = 0;
                Prefs.get(this).edit().putLong("last_contact", System.currentTimeMillis()).apply();
                setNotificationText("Conectado · " + hostLabel(api));
                handleCommands(api, response.optJSONArray("commands"));
            } catch (Exception e) {
                failures++;
                Log.w("PhoneLink", "sin conexión (" + hostLabel(api) + "): " + e);
                if (failures >= 2 && clients.size() > 1) {
                    active = (active + 1) % clients.size();
                    registered = false;
                    failures = 0;
                    Log.i("PhoneLink", "cambiando a " + hostLabel(clients.get(active)));
                }
                setNotificationText(failures > 1 ? "Sin conexión · reintentando" : "Conectando…");
                if (!sleepQuietly(Math.min(60000L, 2000L * Math.max(1, failures)))) break;
            }
        }
        running = false;
        Log.i("PhoneLink", "bucle del agente detenido");
    }

    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException interrupted) {
            return false;
        }
    }

    private JSONObject registerBody() throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", Prefs.deviceId(this));
        body.put("info", infoJson(this));
        body.put("appVersion", "1.0");
        return body;
    }

    private JSONObject heartbeatBody() throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", Prefs.deviceId(this));
        body.put("info", infoJson(this));
        body.put("status", statusJson());
        return body;
    }

    public static JSONObject infoJson(Context context) {
        JSONObject info = new JSONObject();
        put(info, "model", Build.MODEL);
        put(info, "manufacturer", Build.MANUFACTURER);
        put(info, "android", Build.VERSION.RELEASE);
        put(info, "sdk", Build.VERSION.SDK_INT);
        put(info, "device", Build.DEVICE);
        put(info, "appVersion", "1.0");
        put(info, "name", (Build.MANUFACTURER + " " + Build.MODEL).trim());
        return info;
    }

    private JSONObject statusJson() {
        JSONObject status = new JSONObject();
        put(status, "battery", batteryJson());
        put(status, "ips", ipsJson());
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        put(status, "screenAwake", power != null && power.isInteractive());
        put(status, "uptimeSec", SystemClock.elapsedRealtime() / 1000);
        put(status, "location", lastLocationJson());
        put(status, "permissions", permissionsJson());
        put(status, "notificationAccess", NotificationCollector.isEnabled(this));
        return status;
    }

    private JSONObject batteryJson() {
        JSONObject battery = new JSONObject();
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int percent = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                put(battery, "level", percent);
                put(battery, "charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
                put(battery, "temperature", Math.round(temperature / 10.0 * 10.0) / 10.0);
            } else {
                BatteryManager manager = (BatteryManager) getSystemService(BATTERY_SERVICE);
                put(battery, "level", manager != null
                        ? manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : null);
                put(battery, "charging", manager != null && manager.isCharging());
            }
        } catch (Exception ignored) {
        }
        return battery;
    }

    private JSONArray ipsJson() {
        JSONArray array = new JSONArray();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) continue;
                    JSONObject entry = new JSONObject();
                    put(entry, "iface", network.getName());
                    put(entry, "ip", address.getHostAddress());
                    array.put(entry);
                }
            }
        } catch (Exception ignored) {
        }
        return array;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Location bestLastKnown() {
        if (!hasLocationPermission()) return null;
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (manager == null) return null;
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject lastLocationJson() {
        return locationToJson(bestLastKnown());
    }

    private JSONObject locationToJson(Location location) {
        if (location == null) return null;
        JSONObject object = new JSONObject();
        put(object, "lat", location.getLatitude());
        put(object, "lon", location.getLongitude());
        put(object, "accuracy", location.getAccuracy());
        put(object, "provider", location.getProvider());
        put(object, "time", location.getTime());
        return object;
    }

    private JSONObject freshLocationJson() {
        Location best = bestLastKnown();
        if (hasLocationPermission()) {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location location = requestSingleLocation(provider);
                if (location != null && (best == null || location.getTime() >= best.getTime())) {
                    best = location;
                }
            }
        }
        return locationToJson(best);
    }

    private Location requestSingleLocation(String provider) {
        if (!hasLocationPermission()) return null;
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null || !manager.isProviderEnabled(provider)) return null;
        final Location[] result = new Location[1];
        final CountDownLatch latch = new CountDownLatch(1);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                result[0] = location;
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String providerName, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String providerName) {
            }

            @Override
            public void onProviderDisabled(String providerName) {
                latch.countDown();
            }
        };
        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            latch.await(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                manager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        return result[0];
    }

    private JSONObject permissionsJson() {
        JSONObject permissions = new JSONObject();
        put(permissions, "sms",
                checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED);
        put(permissions, "location", hasLocationPermission());
        String mediaPermission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        put(permissions, "photos",
                checkSelfPermission(mediaPermission) == PackageManager.PERMISSION_GRANTED);
        if (Build.VERSION.SDK_INT >= 33) {
            put(permissions, "notifications",
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        } else {
            put(permissions, "notifications", true);
        }
        put(permissions, "notificationAccess", NotificationCollector.isEnabled(this));
        put(permissions, "allFiles", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager());
        put(permissions, "deviceAdmin", isDeviceAdmin());
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        put(permissions, "batteryUnrestricted",
                power != null && power.isIgnoringBatteryOptimizations(getPackageName()));
        return permissions;
    }

    private boolean isDeviceAdmin() {
        DevicePolicyManager manager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return manager != null && manager.isAdminActive(new ComponentName(this, DeviceAdminReceiver.class));
    }

    private void handleCommands(ApiClient api, JSONArray commands) {
        if (commands == null) return;
        for (int i = 0; i < commands.length(); i++) {
            JSONObject command = commands.optJSONObject(i);
            if (command != null) handleCommand(api, command);
        }
    }

    private void handleCommand(ApiClient api, JSONObject command) {
        String id = command.optString("id", "");
        String type = command.optString("type", "");
        JSONObject args = command.optJSONObject("args");
        if (args == null) args = new JSONObject();
        Log.d("PhoneLink", "comando " + type + " #" + id);
        try {
            if ("stop".equals(type)) {
                JSONObject stop = new JSONObject();
                put(stop, "stopped", true);
                sendResult(api, id, stop, null);
                shutdown();
                return;
            }

            JSONObject result = new JSONObject();
            switch (type) {
                case "status":
                    result = statusJson();
                    break;
                case "sms": {
                    put(result, "messages", smsJson());
                    break;
                }
                case "notifications": {
                    put(result, "notifications", NotificationCollector.snapshot());
                    put(result, "access", NotificationCollector.isEnabled(this));
                    break;
                }
                case "location": {
                    put(result, "location", freshLocationJson());
                    break;
                }
                case "photos": {
                    put(result, "photos", photosJson());
                    break;
                }
                case "thumb": {
                    put(result, "thumb", thumbnailJson(args.optLong("id")));
                    break;
                }
                case "photo": {
                    put(result, "photo", photoJson(args.optLong("id")));
                    break;
                }
                case "files": {
                    result = filesJson(args.optString("path", ""));
                    break;
                }
                case "file": {
                    put(result, "file", fileJson(args.optString("path", "")));
                    break;
                }
                case "apps": {
                    put(result, "apps", appsJson());
                    break;
                }
                case "launch": {
                    String packageName = args.optString("package", "");
                    launchApp(packageName);
                    put(result, "launched", packageName);
                    break;
                }
                case "lock": {
                    lockScreen();
                    put(result, "locked", true);
                    break;
                }
                case "vibrate": {
                    vibrate();
                    put(result, "vibrated", true);
                    break;
                }
                case "toast": {
                    String message = args.optString("text", "PhoneLink");
                    showToast(message);
                    put(result, "shown", message);
                    break;
                }
                case "permissions": {
                    put(result, "permissions", permissionsJson());
                    break;
                }
                case "ping": {
                    put(result, "pong", true);
                    break;
                }
                default:
                    throw new Exception("Comando desconocido: " + type);
            }
            sendResult(api, id, result, null);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.w("PhoneLink", "comando " + type + " falló: " + message);
            sendResult(api, id, null, message);
        }
    }

    private void sendResult(ApiClient api, String commandId, JSONObject data, String error) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("deviceId", Prefs.deviceId(this));
            payload.put("commandId", commandId);
            payload.put("ok", error == null);
            if (data != null) payload.put("data", data);
            if (error != null) payload.put("error", error);
            api.post("/api/agent/result", payload, 60000);
        } catch (Exception e) {
            Log.w("PhoneLink", "no se pudo enviar el resultado #" + commandId + ": " + e);
        }
    }

    private JSONArray smsJson() throws Exception {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw new Exception("Permiso de SMS no concedido. Concédelo desde ADB o en Ajustes > Apps > PhoneLink > Permisos.");
        }
        JSONArray array = new JSONArray();
        Cursor cursor = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date", "type"},
                null, null, "date DESC");
        if (cursor == null) throw new Exception("No se pudo consultar los SMS");
        try {
            while (cursor.moveToNext() && array.length() < 100) {
                JSONObject item = new JSONObject();
                put(item, "address", cursor.getString(0));
                put(item, "body", cursor.getString(1));
                put(item, "date", cursor.getLong(2));
                put(item, "type", cursor.getInt(3));
                array.put(item);
            }
        } finally {
            cursor.close();
        }
        return array;
    }

    private JSONArray photosJson() throws Exception {
        String mediaPermission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(mediaPermission) != PackageManager.PERMISSION_GRANTED) {
            throw new Exception("Permiso de fotos no concedido. Concédelo en Ajustes > Apps > PhoneLink > Permisos.");
        }
        JSONArray array = new JSONArray();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
        };
        Cursor cursor = getContentResolver().query(
                collection, projection, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC");
        if (cursor == null) throw new Exception("No se pudo consultar la galería");
        try {
            while (cursor.moveToNext() && array.length() < 60) {
                long id = cursor.getLong(0);
                JSONObject item = new JSONObject();
                put(item, "id", id);
                put(item, "name", cursor.getString(1));
                put(item, "date", cursor.getLong(2) * 1000L);
                put(item, "size", cursor.getLong(3));
                put(item, "width", cursor.getInt(4));
                put(item, "height", cursor.getInt(5));
                array.put(item);
            }
        } finally {
            cursor.close();
        }
        return array;
    }

    private JSONObject thumbnailJson(long id) throws Exception {
        if (id <= 0) throw new Exception("Falta el id de la foto");
        String thumb = thumbnailBase64(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        if (thumb == null) throw new Exception("No se pudo generar la miniatura");
        JSONObject item = new JSONObject();
        put(item, "id", id);
        put(item, "thumb", thumb);
        return item;
    }

    private String thumbnailBase64(Uri collection, long id) {
        Bitmap bitmap = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                bitmap = getContentResolver().loadThumbnail(
                        ContentUris.withAppendedId(collection, id), new Size(200, 200), null);
            } else {
                bitmap = MediaStore.Images.Thumbnails.getThumbnail(
                        getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
            }
            if (bitmap == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private JSONObject photoJson(long id) throws Exception {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = ContentUris.withAppendedId(collection, id);
        String name = "foto-" + id + ".jpg";
        Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.DISPLAY_NAME}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String value = cursor.getString(0);
                    if (value != null && !value.isEmpty()) name = value;
                }
            } finally {
                cursor.close();
            }
        }
        Bitmap bitmap = decodeSampled(uri, 1600);
        if (bitmap == null) throw new Exception("No se pudo leer la foto");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            JSONObject photo = new JSONObject();
            put(photo, "name", name);
            put(photo, "mime", "image/jpeg");
            put(photo, "data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            return photo;
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap decodeSampled(Uri uri, int maxSize) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    private JSONObject filesJson(String requested) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        String rootPath = root.getCanonicalPath();
        File directory = requested == null || requested.trim().isEmpty()
                ? root : new File(requested.trim());
        String canonical = directory.getCanonicalPath();
        if (!canonical.equals(rootPath) && !canonical.startsWith(rootPath + File.separator)) {
            throw new Exception("Ruta fuera del almacenamiento permitido");
        }
        File[] list = directory.listFiles();
        if (list == null) {
            throw new Exception("No se puede leer la carpeta. Activa «Acceso a todos los archivos» en la app.");
        }
        Arrays.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        JSONObject result = new JSONObject();
        put(result, "path", canonical);
        put(result, "parent", canonical.equals(rootPath) ? null : directory.getParentFile().getCanonicalPath());
        JSONArray entries = new JSONArray();
        int count = 0;
        for (File file : list) {
            if (count++ >= 400) break;
            JSONObject entry = new JSONObject();
            put(entry, "name", file.getName());
            put(entry, "path", file.getCanonicalPath());
            put(entry, "size", file.isDirectory() ? 0 : file.length());
            put(entry, "mtime", file.lastModified());
            put(entry, "isDir", file.isDirectory());
            entries.put(entry);
        }
        put(result, "entries", entries);
        return result;
    }

    private JSONObject fileJson(String requested) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        String rootPath = root.getCanonicalPath();
        File file = new File(requested).getCanonicalFile();
        String canonical = file.getCanonicalPath();
        if (!canonical.startsWith(rootPath + File.separator)) {
            throw new Exception("Ruta fuera del almacenamiento permitido");
        }
        if (!file.isFile()) throw new Exception("El archivo no existe");
        if (file.length() > 15L * 1024 * 1024) {
            throw new Exception("Archivo demasiado grande (máximo 15 MB para enviar a la PC)");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        String mime = URLConnection.guessContentTypeFromName(file.getName());
        JSONObject result = new JSONObject();
        put(result, "name", file.getName());
        put(result, "size", file.length());
        put(result, "mime", mime == null ? "application/octet-stream" : mime);
        put(result, "data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return result;
    }

    private JSONArray appsJson() {
        JSONArray array = new JSONArray();
        try {
            PackageManager manager = getPackageManager();
            List<ApplicationInfo> apps = manager.getInstalledApplications(0);
            Collections.sort(apps, new Comparator<ApplicationInfo>() {
                @Override
                public int compare(ApplicationInfo a, ApplicationInfo b) {
                    return manager.getApplicationLabel(a).toString()
                            .compareToIgnoreCase(manager.getApplicationLabel(b).toString());
                }
            });
            for (ApplicationInfo app : apps) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (array.length() >= 300) break;
                JSONObject item = new JSONObject();
                put(item, "package", app.packageName);
                put(item, "label", manager.getApplicationLabel(app).toString());
                put(item, "launchable", manager.getLaunchIntentForPackage(app.packageName) != null);
                array.put(item);
            }
        } catch (Exception ignored) {
        }
        return array;
    }

    private void launchApp(String packageName) throws Exception {
        if (packageName == null || !packageName.matches("[a-zA-Z0-9_.]+")) {
            throw new Exception("Paquete inválido");
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) throw new Exception("La app no está instalada o no tiene pantalla de inicio");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void lockScreen() throws Exception {
        DevicePolicyManager manager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, DeviceAdminReceiver.class);
        if (manager == null || !manager.isAdminActive(admin)) {
            throw new Exception("Activa el administrador de PhoneLink en el teléfono para poder bloquearlo");
        }
        manager.lockNow();
    }

    private void vibrate() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, AgentService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("PhoneLink activo")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification),
                        "Detener", stop).build())
                .build();
    }

    private void setNotificationText(String text) {
        if (text.equals(notificationText)) return;
        notificationText = text;
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private String hostLabel(ApiClient api) {
        try {
            String host = Uri.parse(api.baseUrl()).getHost();
            return host == null ? "tu PC" : host;
        } catch (Exception e) {
            return "tu PC";
        }
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value == null ? JSONObject.NULL : value);
        } catch (Exception ignored) {
        }
    }
}
