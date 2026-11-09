#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
BASE_ADB=${ADB:-adb}
SERIAL=${ADB_SERIAL:-}
if [ -z "$SERIAL" ]; then
  SERIAL=$("$BASE_ADB" devices | awk '$2 == "device" && $1 !~ /:/ { print $1; exit }')
fi
if [ -z "$SERIAL" ]; then
  SERIAL=$("$BASE_ADB" devices | awk '$2 == "device" { print $1; exit }')
fi
if [ -z "$SERIAL" ]; then
  echo "No hay dispositivo ADB conectado."
  exit 1
fi
ADB="$BASE_ADB -s $SERIAL"
PKG=com.zynolist.phonelink
echo "Dispositivo: $SERIAL"

$ADB install -r PhoneLink.apk
$ADB shell pm grant "$PKG" android.permission.READ_SMS || true
$ADB shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
$ADB shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION || true
$ADB shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES || true
$ADB shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
$ADB shell pm grant "$PKG" android.permission.CAMERA || true
$ADB shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
$ADB shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
$ADB shell cmd notification allow_listener "$PKG/$PKG.NotificationCollector" || true
$ADB shell dpm set-active-admin "$PKG/.DeviceAdminReceiver" || true
$ADB shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true

echo
echo "PhoneLink instalada y permisos concedidos por ADB."
echo "Abre PhoneLink en el teléfono, escribe la URL del túnel y el token, acepta el consentimiento y pulsa «Activar monitoreo»."
