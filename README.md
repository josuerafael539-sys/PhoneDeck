# PhoneDeck

**by ZynoList**

Panel web autohospedado para controlar tu propio teléfono Android desde el navegador, con una app companion (**PhoneLink**) para acceso remoto consentido.

> Proyecto personal by ZynoList. Úsalo únicamente con dispositivos propios o con consentimiento explícito de la persona que los usa.

## Características

### Dashboard
- Pantalla en vivo y control táctil por ADB.
- Botones de navegación, volumen, energía y entrada de texto.
- Archivos: explorar, descargar y subir.
- Notificaciones, SMS, ubicación, aplicaciones instaladas e información del sistema.
- Terminal ADB (comandos como usuario `shell`, sin root).
- Pestaña **Remoto (APK)** para hablar con PhoneLink aunque el teléfono no esté por USB.

### PhoneLink (Android)
- Servicio en primer plano con notificación permanente y botón **Detener**.
- Consentimiento explícito antes de enviar datos.
- Comandos remotos: estado, SMS, notificaciones, ubicación, fotos, archivos, apps, abrir apps, bloquear, vibrar, avisos y ping.
- Varias URLs de servidor separadas por coma, con rotación automática si una falla.
- Reanudación automática al abrir la app si el monitoreo estaba activado.
- Compatible con Tailscale y con túneles de Cloudflare.

## Arquitectura

```
PC                                     Teléfono
┌──────────────────────────┐          ┌──────────────────────┐
│ dashboard (Node.js)      │  ADB     │ Android              │
│  - API y UI web          │◄────────►│  - PhoneLink (APK)   │
│  - scrcpy-style screen   │          │  - Servicio y consent│
│  - Agente remoto         │◄────────►│  - API de comandos   │
└──────────────────────────┘  Tailscale└──────────────────────┘
```

- El dashboard usa ADB para las funciones locales.
- PhoneLink se conecta al dashboard por HTTP y recoge comandos pendientes.
- El servidor escucha en `127.0.0.1` y, si Tailscale está disponible, también en la IP de Tailscale. El acceso que no sea local exige token.

## Requisitos

- Linux (probado en Debian/Ubuntu).
- Node.js 18 o superior. El dashboard no tiene dependencias externas.
- ADB (`android-tools-adb`) y un teléfono con depuración USB activada.
- Para compilar PhoneLink: Android SDK con plataforma 35, build-tools 34.0.0, `javac`, `keytool`, `zip` y `python3`.
- Opcional: Tailscale para acceso remoto cifrado; Cloudflare Tunnel como respaldo.

## Inicio rápido

### 1. Dashboard

```bash
cd dashboard
npm start
# o: node server.js
```

La primera ejecución crea `.phonedeck-token` y muestra en consola la URL de acceso:

```
http://127.0.0.1:8787/login?token=...
```

Variables de entorno útiles:

| Variable | Descripción |
| --- | --- |
| `PORT` | Puerto HTTP (por defecto `8787`). |
| `HOST` | Interfaz local (por defecto `127.0.0.1`). |
| `ADB_SERIAL` | Serial del dispositivo si hay más de uno. |
| `PHONEDECK_TOKEN` | Token fijo; si no se define, se usa `.phonedeck-token`. |
| `PHONEDECK_TOKEN_FILE` | Ruta alternativa del archivo de token. |
| `EXTRA_HOST` | IP adicional donde escuchar (por ejemplo, la de Tailscale). |

### 2. Conectar el teléfono

```bash
adb devices
adb shell echo ok
```

Si hay varios dispositivos, define `ADB_SERIAL` antes de arrancar el dashboard.

### 3. Compilar e instalar PhoneLink

Ajusta la ruta `SDK` de `android/build.sh` si tu Android SDK no está en `/usr/lib/android-sdk`.

```bash
cd android
./build.sh
./install.sh
```

`install.sh` instala la APK y concede permisos por ADB. Si hay varios dispositivos:

```bash
ADB_SERIAL=TU_SERIAL ./install.sh
```

Abre **PhoneLink** en el teléfono, escribe la URL del dashboard y el token, acepta el consentimiento y pulsa **Activar monitoreo**.

Ejemplo de URL con dos servidores:

```
http://100.x.x.x:8787,https://tu-tunel.trycloudflare.com
```

### 4. Acceso remoto con Tailscale (recomendado)

Instala Tailscale en el PC y en el teléfono, inicia sesión con la misma cuenta y conéctalos:

```bash
tailscale up
tailscale ip -4
```

Reinicia el dashboard para que detecte la IP de Tailscale. Entra desde el teléfono o desde otro dispositivo de la tailnet:

```
http://100.x.x.x:8787/login?token=TU_TOKEN
```

El tráfico va cifrado por WireGuard y las peticiones externas siguen exigiendo token.

### 5. Cloudflare Tunnel (respaldo opcional)

Para un túnel rápido:

```bash
cloudflared tunnel --url http://127.0.0.1:8787 --protocol http2
```

Añade la URL `https://....trycloudflare.com` en PhoneLink, separada por coma si ya tienes Tailscale:

```
http://100.x.x.x:8787,https://....trycloudflare.com
```

Los túneles rápidos cambian de URL y expiran; para uso estable crea un túnel con nombre en Cloudflare.

## Seguridad y privacidad

- **No publiques `.phonedeck-token`** ni lo incluyas en capturas o repositorios.
- El token se genera automáticamente y se guarda con permisos `0600`.
- Si el token se filtra, borra el archivo, reinicia el dashboard y actualiza el token en PhoneLink.
- PhoneLink muestra una notificación permanente mientras está activo y permite detenerlo.
- El consentimiento se guarda en el teléfono; no se activa nada sin marcarlo.
- No expongas el puerto de ADB a Internet. Para remoto usa Tailscale o un túnel HTTPS.
- Revisa los permisos de la app: SMS, ubicación, fotos, notificaciones, acceso a archivos, administrador del dispositivo y exclusión de optimización de batería.
- `pm clear` revoca los permisos concedidos por ADB; vuelve a ejecutar `install.sh`.

## Estructura

```
PhoneDeck/
├── README.md
├── .gitignore
├── dashboard/
│   ├── package.json
│   ├── server.js
│   └── public/
│       └── index.html
└── android/
    ├── AndroidManifest.xml
    ├── build.sh
    ├── install.sh
    ├── res/
    ├── src/com/zynolist/phonelink/
    └── tools/
```

## API remota (resumen)

```bash
TOKEN=$(cat dashboard/.phonedeck-token)

curl -H "x-phonedeck-token: $TOKEN" \
  http://127.0.0.1:8787/api/remote/devices

curl -H "x-phonedeck-token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"...","type":"status","args":{}}' \
  http://127.0.0.1:8787/api/remote/command
```

Comandos disponibles: `status`, `sms`, `notifications`, `location`, `photos`, `thumb`, `photo`, `files`, `file`, `apps`, `launch`, `lock`, `vibrate`, `toast`, `permissions`, `ping`, `stop`.

## Solución de problemas

- **`adb: more than one device/emulator`**: define `ADB_SERIAL` o usa `ADB_SERIAL=... ./install.sh`.
- **No aparecen notificaciones**: vuelve a conceder el acceso al listener con `install.sh`.
- **El teléfono no conecta en remoto**: revisa que PhoneLink esté activo, que la URL sea correcta y que Tailscale o el túnel estén arriba.
- **La URL de Cloudflare dejó de funcionar**: los túneles rápidos expiran; genera uno nuevo y actualízalo en PhoneLink.
- **El servicio se detuvo tras una actualización**: abre PhoneLink; la app reanuda el monitoreo si estaba activado.

## Créditos

Hecho por **ZynoList**.

## Licencia

© 2026 ZynoList. Todos los derechos reservados.
