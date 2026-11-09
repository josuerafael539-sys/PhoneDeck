package com.zynolist.phonelink;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 2001;
    private static final int COLOR_BG = Color.parseColor("#0A0F1C");
    private static final int COLOR_CARD = Color.parseColor("#111A2E");
    private static final int COLOR_LINE = Color.parseColor("#223052");
    private static final int COLOR_TEXT = Color.parseColor("#E8EEFB");
    private static final int COLOR_MUTED = Color.parseColor("#8EA3C7");
    private static final int COLOR_ACCENT = Color.parseColor("#38BDF8");
    private static final int COLOR_OK = Color.parseColor("#34D399");
    private static final int COLOR_BAD = Color.parseColor("#F87171");

    private EditText urlField;
    private EditText tokenField;
    private CheckBox consentBox;
    private TextView statusView;
    private TextView permissionsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        buildUi();
        loadPrefs();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(18), top + dp(14), dp(18), bottom + dp(20));
            return insets;
        });

        root.addView(text("PhoneLink", 28, COLOR_TEXT, true));
        TextView subtitle = text("Acceso remoto consentido a este teléfono", 14, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        TextView banner = text(
                "Esta app es visible. Mientras esté activa verás una notificación permanente y podrás pulsar «Detener» para dejar de enviar datos al instante.",
                13, COLOR_TEXT, false);
        banner.setBackground(rounded(Color.parseColor("#0F2438"), 12, Color.parseColor("#1E4B66")));
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bannerParams.bottomMargin = dp(12);
        root.addView(banner, bannerParams);

        LinearLayout serverCard = card();
        serverCard.addView(sectionTitle("Servidor"));
        serverCard.addView(label("URL del dashboard (Tailscale o túnel; opcional: otra separada por coma)"));
        urlField = input("http://100.x.x.x:8787,https://xxxx.trycloudflare.com", false);
        serverCard.addView(urlField);
        serverCard.addView(label("Token de acceso"));
        tokenField = input("Token generado por PhoneDeck", true);
        serverCard.addView(tokenField);
        Button testButton = button("Probar conexión", false);
        testButton.setOnClickListener(v -> testConnection());
        serverCard.addView(testButton);
        root.addView(serverCard);

        LinearLayout consentCard = card();
        consentCard.addView(sectionTitle("Qué se comparte"));
        consentCard.addView(text(
                "Mientras esté activo y con tu permiso, la app puede enviar a tu PC: batería, IP, pantalla encendida, ubicación, notificaciones, SMS, fotos, archivos, lista de apps y estado de permisos. También acepta comandos como bloquear la pantalla, vibrar o mostrar un aviso. La cámara y el micrófono solo se activan cuando los pulsas desde el panel y mientras la notificación siga visible.",
                13, COLOR_MUTED, false));
        consentBox = new CheckBox(this);
        consentBox.setText("Entiendo y acepto que este teléfono envíe esos datos a mi PC mientras esté activo.");
        consentBox.setTextColor(COLOR_TEXT);
        consentBox.setTextSize(13);
        consentBox.setPadding(0, dp(10), 0, 0);
        consentCard.addView(consentBox);
        root.addView(consentCard);

        LinearLayout permissionCard = card();
        permissionCard.addView(sectionTitle("Permisos"));
        permissionsView = text("", 13, COLOR_MUTED, false);
        permissionsView.setPadding(0, 0, 0, dp(8));
        permissionCard.addView(permissionsView);
        Button dataPermissions = button("Permisos de datos (ubicación, SMS, fotos, notificaciones, cámara, micrófono)", false);
        dataPermissions.setOnClickListener(v -> requestDataPermissions());
        permissionCard.addView(dataPermissions);
        Button notificationAccess = button("Acceso a notificaciones", false);
        notificationAccess.setOnClickListener(v -> openNotificationAccess());
        permissionCard.addView(notificationAccess);
        Button allFilesAccess = button("Acceso a todos los archivos", false);
        allFilesAccess.setOnClickListener(v -> openAllFilesAccess());
        permissionCard.addView(allFilesAccess);
        Button adminAccess = button("Activar administrador (para bloquear)", false);
        adminAccess.setOnClickListener(v -> requestDeviceAdmin());
        permissionCard.addView(adminAccess);
        Button batteryAccess = button("Quitar optimización de batería", false);
        batteryAccess.setOnClickListener(v -> requestBatteryExemption());
        permissionCard.addView(batteryAccess);
        root.addView(permissionCard);

        LinearLayout controlCard = card();
        controlCard.addView(sectionTitle("Control"));
        Button startButton = button("Activar monitoreo", true);
        startButton.setOnClickListener(v -> startMonitoring());
        controlCard.addView(startButton);
        Button stopButton = button("Detener y dejar de enviar", false);
        stopButton.setBackground(rounded(Color.parseColor("#3A1520"), 12, Color.parseColor("#7F1D1D")));
        stopButton.setOnClickListener(v -> stopMonitoring());
        controlCard.addView(stopButton);
        statusView = text("", 13, COLOR_MUTED, false);
        statusView.setPadding(0, dp(10), 0, 0);
        controlCard.addView(statusView);
        root.addView(controlCard);

        TextView footer = text(
                "PhoneLink no se oculta; la cámara y el micrófono solo funcionan bajo demanda, con la notificación visible y el botón «Detener» disponible. No puede leer datos privados de otras apps sin permiso. Puedes desinstalarla cuando quieras.",
                12, COLOR_MUTED, false);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);

        setContentView(scroll);
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value.toUpperCase(), 12, COLOR_MUTED, true);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 12, COLOR_MUTED, false);
        view.setPadding(0, dp(6), 0, dp(4));
        return view;
    }

    private EditText input(String hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(COLOR_TEXT);
        field.setHintTextColor(Color.parseColor("#5B6B8C"));
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setBackground(rounded(Color.parseColor("#16213A"), 10, COLOR_LINE));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        field.setLayoutParams(params);
        return field;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setBackground(primary
                ? rounded(Color.parseColor("#0EA5E9"), 12, Color.parseColor("#0EA5E9"))
                : rounded(Color.parseColor("#16213A"), 12, COLOR_LINE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD, 14, COLOR_LINE));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void loadPrefs() {
        urlField.setText(Prefs.serverUrl(this));
        tokenField.setText(Prefs.token(this));
        consentBox.setChecked(Prefs.consented(this));
        updateStatus();
        updatePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updatePermissions();
        maybeResumeMonitoring();
    }

    private void maybeResumeMonitoring() {
        if (!Prefs.enabled(this) || !Prefs.consented(this) || AgentService.running) {
            return;
        }
        if (Prefs.serverUrls(this).isEmpty() || Prefs.token(this).trim().length() < 8) {
            return;
        }
        try {
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
            Log.i("PhoneLink", "monitoreo reanudado al abrir la app");
        } catch (Exception e) {
            Log.w("PhoneLink", "no se pudo reanudar el monitoreo: " + e.getMessage());
        }
    }

    private void updateStatus() {
        boolean active = Prefs.enabled(this) && AgentService.running;
        if (active) {
            statusView.setText("Activo · enviando a " + Prefs.serverUrl(this));
            statusView.setTextColor(COLOR_OK);
        } else if (Prefs.enabled(this)) {
            statusView.setText("Activado pero sin conexión. Abre la app o revisa la URL/token.");
            statusView.setTextColor(COLOR_BAD);
        } else {
            statusView.setText("Detenido. No se envía ningún dato.");
            statusView.setTextColor(COLOR_MUTED);
        }
        long last = Prefs.lastContact(this);
        if (last > 0) {
            statusView.append("\nÚltimo contacto: "
                    + DateFormat.getDateTimeInstance().format(new Date(last)));
        }
    }

    private void updatePermissions() {
        StringBuilder builder = new StringBuilder();
        builder.append(statusLine("Ubicación", checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED));
        builder.append(statusLine("SMS", checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED));
        builder.append(statusLine("Cámara", checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED));
        builder.append(statusLine("Micrófono", checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED));
        String mediaPermission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        builder.append(statusLine("Fotos", checkSelfPermission(mediaPermission)
                == PackageManager.PERMISSION_GRANTED));
        if (Build.VERSION.SDK_INT >= 33) {
            builder.append(statusLine("Notificaciones", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED));
        } else {
            builder.append(statusLine("Notificaciones", true));
        }
        builder.append(statusLine("Acceso a notificaciones", NotificationCollector.isEnabled(this)));
        builder.append(statusLine("Todos los archivos",
                Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()));
        builder.append(statusLine("Administrador (bloquear)", isDeviceAdmin()));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        builder.append(statusLine("Batería sin restricciones",
                power != null && power.isIgnoringBatteryOptimizations(getPackageName())));
        permissionsView.setText(builder.toString());
    }

    private String statusLine(String label, boolean ready) {
        return (ready ? "OK · " : "Pendiente · ") + label + "\n";
    }

    private boolean isDeviceAdmin() {
        DevicePolicyManager manager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return manager != null && manager.isAdminActive(new ComponentName(this, DeviceAdminReceiver.class));
    }

    private void requestDataPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.READ_SMS);
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            updatePermissions();
            toast("Permisos actualizados. Si SMS sigue pendiente, concédelo con el instalador del PC.");
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            toast("Abre Ajustes > Notificaciones > Acceso a notificaciones y activa PhoneLink");
        }
    }

    private void openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestDataPermissions();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                toast("Activa «Acceso a todos los archivos» en Ajustes > Apps > PhoneLink");
            }
        }
    }

    private void requestDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(this, DeviceAdminReceiver.class));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Permite que tu PC bloquee la pantalla de este teléfono de forma remota.");
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("No se pudo abrir el administrador de dispositivos");
        }
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("Abre Ajustes > Batería > Sin restricciones para PhoneLink");
            }
        }
    }

    private List<String> splitUrls(String raw) {
        List<String> urls = new ArrayList<>();
        for (String part : raw.split("[,\\n\\s]+")) {
            String value = part.trim();
            if (!value.isEmpty() && !urls.contains(value)) urls.add(value);
        }
        return urls;
    }

    private void testConnection() {
        final String rawUrl = urlField.getText().toString().trim();
        final String token = tokenField.getText().toString().trim();
        final List<String> urls = splitUrls(rawUrl);
        if (urls.isEmpty()) {
            toast("Escribe la URL del dashboard");
            return;
        }
        for (String url : urls) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                toast("Cada URL debe empezar por http:// o https://");
                return;
            }
        }
        if (token.isEmpty()) {
            toast("Escribe el token de acceso");
            return;
        }
        toast("Probando conexión…");
        new Thread(() -> {
            String lastError = null;
            for (String url : urls) {
                try {
                    ApiClient api = new ApiClient(url, token);
                    JSONObject body = new JSONObject();
                    body.put("deviceId", Prefs.deviceId(this));
                    body.put("info", AgentService.infoJson(this));
                    body.put("appVersion", "1.0");
                    api.post("/api/agent/register", body, 15000);
                    final String okUrl = url;
                    runOnUiThread(() -> toast("Conexión correcta con " + okUrl));
                    return;
                } catch (Exception e) {
                    lastError = url + ": " + e.getMessage();
                    Log.w("PhoneLink", "prueba fallida " + lastError);
                }
            }
            final String message = lastError;
            runOnUiThread(() -> toast("Error: " + message));
        }).start();
    }

    private void startMonitoring() {
        String url = urlField.getText().toString().trim();
        String token = tokenField.getText().toString().trim();
        List<String> urls = splitUrls(url);
        if (urls.isEmpty()) {
            toast("Escribe la URL del dashboard");
            return;
        }
        for (String value : urls) {
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                toast("Cada URL debe empezar por http:// o https://");
                return;
            }
        }
        if (token.length() < 8) {
            toast("Escribe el token de acceso de PhoneDeck");
            return;
        }
        if (!consentBox.isChecked()) {
            toast("Marca la casilla de consentimiento para continuar");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestDataPermissions();
            toast("Concede las notificaciones y vuelve a pulsar «Activar»");
            return;
        }
        Prefs.saveConfig(this, url, token, true);
        Prefs.setEnabled(this, true);
        try {
            startForegroundService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_START));
        } catch (Exception e) {
            Prefs.setEnabled(this, false);
            toast("No se pudo iniciar el servicio: " + e.getMessage());
            updateStatus();
            return;
        }
        toast("Monitoreo activado. La notificación permanente debe seguir visible.");
        updateStatus();
        statusView.postDelayed(this::updateStatus, 2500);
    }

    private void stopMonitoring() {
        Prefs.setEnabled(this, false);
        try {
            startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP));
        } catch (Exception ignored) {
        }
        stopService(new Intent(this, AgentService.class));
        AgentService.running = false;
        toast("Detenido. Ya no se envían datos.");
        updateStatus();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
