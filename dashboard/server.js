'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFile, execSync, spawn } = require('child_process');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const ADB = process.env.ADB || 'adb';
const CAPTURE_WIDTH = Number(process.env.CAPTURE_WIDTH || 540);
const CAPTURE_INTERVAL = Number(process.env.CAPTURE_INTERVAL || 150);
const PUBLIC_DIR = path.join(__dirname, 'public');
const TOKEN_FILE = process.env.PHONEDECK_TOKEN_FILE || path.join(__dirname, '.phonedeck-token');

let SERIAL = process.env.ADB_SERIAL || null;
let statusCache = { at: 0, data: null };

let AUTH_TOKEN = String(process.env.PHONEDECK_TOKEN || '').trim();
if (!AUTH_TOKEN) {
  try { AUTH_TOKEN = fs.readFileSync(TOKEN_FILE, 'utf8').trim(); } catch {}
}
if (!AUTH_TOKEN) {
  AUTH_TOKEN = crypto.randomBytes(18).toString('hex');
  try { fs.writeFileSync(TOKEN_FILE, AUTH_TOKEN + '\n', { mode: 0o600 }); } catch {}
}

const agents = new Map();

const ALLOWED_FILE_ROOTS = ['/sdcard', '/storage', '/mnt', '/data/local/tmp'];
const KEYCODES = {
  home: 3, back: 4, recents: 187, power: 26, sleep: 223, wakeup: 224,
  volume_up: 24, volume_down: 25, enter: 66, delete: 67, menu: 82,
  notifications: 83, camera: 27, media_play_pause: 85, tab: 61,
  dpad_up: 19, dpad_down: 20, dpad_left: 21, dpad_right: 22,
};

function httpError(status, message) {
  const err = new Error(message);
  err.status = status;
  return err;
}

function adbFullArgs(args) {
  return SERIAL ? ['-s', SERIAL, ...args] : args;
}

function adbRaw(args, opts = {}) {
  return adbExecRaw(args, opts);
}

function adbExecRaw(args, { timeout = 20000, maxBuffer = 64 * 1024 * 1024, encoding = 'utf8' } = {}) {
  return new Promise((resolve, reject) => {
    execFile(ADB, args, { timeout, maxBuffer, encoding }, (err, stdout, stderr) => {
      if (err) {
        const msg = (stderr && String(stderr).trim()) || err.message;
        return reject(httpError(502, msg));
      }
      resolve(stdout);
    });
  });
}

function adbExec(args, opts = {}) {
  return adbExecRaw(adbFullArgs(args), opts);
}

function shell(cmd, opts = {}) {
  return adbExec(['shell', cmd], opts);
}

function q(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

function validFile(pathname) {
  if (!pathname || !pathname.startsWith('/')) return false;
  if (pathname.includes('\0')) return false;
  return ALLOWED_FILE_ROOTS.some((root) => pathname === root || pathname.startsWith(root + '/'));
}

async function listDevices() {
  const out = await adbRaw(['devices', '-l'], { timeout: 8000 });
  return out
    .split(/\r?\n/)
    .slice(1)
    .filter((line) => line.trim())
    .map((line) => {
      const parts = line.trim().split(/\s+/);
      const serial = parts[0];
      const state = parts[1];
      const info = {};
      for (const kv of parts.slice(2)) {
        const i = kv.indexOf(':');
        if (i > 0) info[kv.slice(0, i)] = kv.slice(i + 1);
      }
      return {
        serial,
        state,
        model: info.model || '',
        product: info.product || '',
        device: info.device || '',
        transportId: info.transport_id || '',
        wireless: serial.includes(':'),
      };
    });
}

async function ensureDevice() {
  const devices = await listDevices();
  const ready = devices.filter((d) => d.state === 'device');
  if (!ready.length) {
    SERIAL = null;
    throw httpError(503, 'No hay un teléfono conectado o autorizado por ADB. Conéctalo por USB y acepta el aviso de depuración USB.');
  }
  if (!SERIAL || !ready.some((d) => d.serial === SERIAL)) {
    SERIAL = (ready.find((d) => !d.wireless) || ready[0]).serial;
    statusCache = { at: 0, data: null };
  }
  return SERIAL;
}

function ffmpegJpeg(input, { width = 540, quality = 7 } = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn('ffmpeg', [
      '-hide_banner', '-loglevel', 'error',
      '-i', 'pipe:0',
      '-vf', `scale=${width}:-2:flags=lanczos`,
      '-frames:v', '1',
      '-f', 'mjpeg', '-q:v', String(quality),
      'pipe:1',
    ]);
    const chunks = [];
    let stderr = '';
    proc.stdout.on('data', (c) => chunks.push(c));
    proc.stderr.on('data', (c) => { stderr += c; });
    proc.on('error', reject);
    proc.on('close', (code) => {
      if (code === 0 && chunks.length) resolve(Buffer.concat(chunks));
      else reject(httpError(500, 'No se pudo convertir la imagen: ' + stderr.trim()));
    });
    proc.stdin.on('error', () => {});
    proc.stdin.end(input);
  });
}

async function captureFrame() {
  const png = await adbExec(['exec-out', 'screencap -p'], {
    encoding: 'buffer',
    timeout: 15000,
    maxBuffer: 64 * 1024 * 1024,
  });
  try {
    return await ffmpegJpeg(png, { width: CAPTURE_WIDTH, quality: 7 });
  } catch {
    return png;
  }
}

const JPEG_SOI = Buffer.from([0xff, 0xd8]);
const JPEG_EOI = Buffer.from([0xff, 0xd9]);

const streamClients = new Set();
const videoState = {
  adbProc: null,
  ffProc: null,
  buf: Buffer.alloc(0),
  latest: null,
  lastAt: 0,
  running: false,
  streaming: false,
  failures: 0,
  startedAt: 0,
  restartTimer: null,
};
let screenshotRunning = false;
let idleTimer = null;

function writeFrame(res, jpeg) {
  try {
    res.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.length}\r\n\r\n`);
    res.write(jpeg);
    res.write('\r\n');
  } catch {
    streamClients.delete(res);
  }
}

function broadcastFrame(jpeg) {
  for (const res of streamClients) writeFrame(res, jpeg);
}

async function screenshotFallbackLoop() {
  if (screenshotRunning) return;
  screenshotRunning = true;
  while (streamClients.size > 0 && !videoState.streaming) {
    const started = Date.now();
    try {
      await ensureDevice();
      broadcastFrame(await captureFrame());
    } catch {
      await new Promise((r) => setTimeout(r, 700));
    }
    const wait = Math.max(0, 1200 - (Date.now() - started));
    if (wait) await new Promise((r) => setTimeout(r, wait));
  }
  screenshotRunning = false;
}

function startVideoPipeline() {
  if (videoState.running || streamClients.size === 0) return;
  videoState.running = true;
  videoState.startedAt = Date.now();
  videoState.buf = Buffer.alloc(0);
  const adbProc = spawn(
    ADB,
    adbFullArgs(['exec-out', 'screenrecord --output-format=h264 --size 540x1200 --bit-rate 2500000 --time-limit 170 -']),
    { stdio: ['ignore', 'pipe', 'pipe'] }
  );
  const ffProc = spawn('ffmpeg', [
    '-hide_banner', '-loglevel', 'error',
    '-f', 'h264', '-i', 'pipe:0',
    '-vf', 'scale=540:-2',
    '-r', '8',
    '-f', 'image2pipe', '-vcodec', 'mjpeg', '-q:v', '7',
    'pipe:1',
  ]);
  videoState.adbProc = adbProc;
  videoState.ffProc = ffProc;

  adbProc.stdout.pipe(ffProc.stdin);
  adbProc.stderr.on('data', () => {});
  ffProc.stdin.on('error', () => {});
  ffProc.stderr.on('data', () => {});

  ffProc.stdout.on('data', (chunk) => {
    videoState.buf = videoState.buf.length ? Buffer.concat([videoState.buf, chunk]) : chunk;
    let soi;
    while ((soi = videoState.buf.indexOf(JPEG_SOI)) !== -1) {
      const eoi = videoState.buf.indexOf(JPEG_EOI, soi + 2);
      if (eoi === -1) {
        if (soi > 0) videoState.buf = videoState.buf.subarray(soi);
        break;
      }
      const jpeg = videoState.buf.subarray(soi, eoi + 2);
      videoState.latest = Buffer.from(jpeg);
      videoState.lastAt = Date.now();
      videoState.streaming = true;
      broadcastFrame(videoState.latest);
      videoState.buf = videoState.buf.subarray(eoi + 2);
    }
    if (videoState.buf.length > 8 * 1024 * 1024) {
      videoState.buf = videoState.buf.subarray(-1024 * 1024);
    }
  });

  const cleanup = () => {
    if (videoState.adbProc !== adbProc) return;
    videoState.running = false;
    videoState.streaming = false;
    videoState.adbProc = null;
    videoState.ffProc = null;
    videoState.buf = Buffer.alloc(0);
    try { adbProc.kill('SIGKILL'); } catch {}
    try { ffProc.kill('SIGKILL'); } catch {}
    const lived = Date.now() - videoState.startedAt;
    if (lived < 5000) videoState.failures += 1;
    else videoState.failures = 0;
    if (streamClients.size > 0) {
      screenshotFallbackLoop();
      const delay = videoState.failures >= 3 ? 30000 : 900;
      clearTimeout(videoState.restartTimer);
      videoState.restartTimer = setTimeout(() => {
        if (videoState.failures >= 3) videoState.failures = 0;
        startVideoPipeline();
      }, delay);
    }
  };

  adbProc.on('close', cleanup);
  adbProc.on('error', cleanup);
  ffProc.on('close', cleanup);
  ffProc.on('error', cleanup);
}

function stopVideoPipeline() {
  clearTimeout(videoState.restartTimer);
  if (videoState.adbProc) {
    try { videoState.adbProc.kill('SIGKILL'); } catch {}
  }
  if (videoState.ffProc) {
    try { videoState.ffProc.kill('SIGKILL'); } catch {}
  }
  videoState.adbProc = null;
  videoState.ffProc = null;
  videoState.running = false;
  videoState.streaming = false;
  videoState.buf = Buffer.alloc(0);
}

function scheduleStopCapture() {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (!streamClients.size) stopVideoPipeline();
  }, 12000);
}

function startCaptureForClients() {
  clearTimeout(idleTimer);
  screenshotFallbackLoop();
  startVideoPipeline();
}

function parseStatusOutput(out) {
  const sections = {};
  let current = null;
  for (const rawLine of out.split(/\r?\n/)) {
    const line = rawLine.replace(/\r$/, '');
    if (/^[A-Z_]+$/.test(line) && line.length > 1) {
      current = line;
      sections[current] = [];
      continue;
    }
    if (current) sections[current].push(line);
  }

  const props = (sections.PROPS || []).map((s) => s.trim());
  const batteryLines = sections.BATTERY || [];
  const battery = { level: null, status: null, charging: false, temperature: null, acPowered: false, usbPowered: false };
  for (const line of batteryLines) {
    let m;
    if ((m = line.match(/^\s*level:\s*(\d+)/))) battery.level = Number(m[1]);
    if ((m = line.match(/^\s*status:\s*(\d+)/))) battery.status = Number(m[1]);
    if ((m = line.match(/^\s*temperature:\s*(\d+)/))) battery.temperature = Number(m[1]) / 10;
    if ((m = line.match(/^\s*AC powered:\s*(true|false)/))) battery.acPowered = m[1] === 'true';
    if ((m = line.match(/^\s*USB powered:\s*(true|false)/))) battery.usbPowered = m[1] === 'true';
  }
  battery.charging = battery.status === 2 || battery.status === 5 || battery.acPowered;

  const dfLine = (sections.DF || []).find((l) => /\d+%/.test(l)) || '';
  const df = dfLine.match(/^(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)%\s+(\S+)/);

  const ips = [];
  for (const line of sections.IP || []) {
    const m = line.match(/^\d+:\s+(\S+)\s+inet\s+([\d.]+)\/(\d+)/);
    if (m) ips.push({ iface: m[1], ip: m[2], cidr: Number(m[3]) });
  }
  ips.sort((a, b) => {
    const score = (i) => (i.iface.startsWith('wlan') ? 0 : i.iface.startsWith('rmnet') ? 1 : 2);
    return score(a) - score(b);
  });

  const wifiLine = (sections.WIFI || [])[0] || '';
  const wifiEnabled = /Wifi is enabled/i.test(wifiLine);
  let wifiSsid = null;
  for (const line of sections.WIFI || []) {
    const m = line.match(/SSID:\s*"?([^",]+)"?/i);
    if (m) { wifiSsid = m[1].trim(); break; }
  }

  const wakeLine = (sections.WAKEFUL || []).find((l) => l.includes('mWakefulness=')) || '';
  const wakeMatch = wakeLine.match(/mWakefulness=(\w+)/);
  const wakefulness = wakeMatch ? wakeMatch[1] : 'Unknown';

  let screenSize = { width: 1080, height: 2400 };
  for (const line of sections.SIZE || []) {
    const m = line.match(/(Physical|Override) size:\s*(\d+)x(\d+)/);
    if (m) screenSize = { width: Number(m[2]), height: Number(m[3]) };
  }

  const serialno = (sections.SERIAL || []).map((s) => s.trim()).find(Boolean) || '';
  const tcpPort = (sections.TCPPORT || []).map((s) => s.trim()).find(Boolean) || '';
  const uptimeSec = Number((sections.UP || []).map((s) => s.trim()).find(Boolean)) || null;
  const root = (sections.ROOT || []).some((l) => /^yes$/i.test(l.trim()));

  return {
    ok: true,
    serial: SERIAL,
    serialno,
    wireless: Boolean(SERIAL && SERIAL.includes(':')),
    manufacturer: props[0] || '',
    model: props[1] || '',
    android: props[2] || '',
    sdk: props[3] || '',
    device: props[4] || '',
    build: props[5] || '',
    battery,
    storage: df
      ? { filesystem: df[1], size: df[2], used: df[3], avail: df[4], usePct: Number(df[5]), mount: df[6] }
      : null,
    ips,
    primaryIp: ips.length ? ips[0].ip : null,
    wifi: { enabled: wifiEnabled, ssid: wifiSsid },
    wakefulness,
    screenAwake: wakefulness === 'Awake',
    screenSize,
    adbTcpPort: tcpPort || null,
    uptimeSec,
    root,
    at: new Date().toISOString(),
  };
}

async function getStatus(force = false) {
  await ensureDevice();
  if (!force && Date.now() - statusCache.at < 1200 && statusCache.data) return statusCache.data;
  const script = `
echo PROPS
getprop ro.product.manufacturer
getprop ro.product.model
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.product.device
getprop ro.build.display.id
echo BATTERY
dumpsys battery
echo DF
df -h /data | tail -n 1
echo IP
ip -4 -o addr show scope global
echo WIFI
cmd wifi status
echo WAKEFUL
dumpsys power | grep mWakefulness=
echo SIZE
wm size
echo SERIAL
getprop ro.serialno
echo TCPPORT
getprop service.adb.tcp.port
echo UP
cut -d. -f1 /proc/uptime
echo ROOT
command -v su >/dev/null 2>&1 && echo yes || echo no
echo END
`;
  const out = await shell(script, { timeout: 20000 });
  const data = parseStatusOutput(out);
  statusCache = { at: Date.now(), data };
  return data;
}

function readJson(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > limit) {
        reject(httpError(413, 'Cuerpo demasiado grande'));
        req.destroy();
      }
    });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch {
        reject(httpError(400, 'JSON inválido'));
      }
    });
    req.on('error', reject);
  });
}

function readRaw(req, limit = 2 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(httpError(413, 'Cuerpo demasiado grande'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = Buffer.from(JSON.stringify(obj));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': body.length,
  });
  res.end(body);
}

function serveIndex(res) {
  fs.readFile(path.join(PUBLIC_DIR, 'index.html'), (err, data) => {
    if (err) return sendJson(res, 500, { ok: false, error: 'Falta public/index.html' });
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(data);
  });
}

function mimeFor(name) {
  const ext = path.extname(name).toLowerCase();
  const map = {
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp',
    '.gif': 'image/gif', '.heic': 'image/heic', '.heif': 'image/heif', '.avif': 'image/avif',
    '.mp4': 'video/mp4', '.webm': 'video/webm', '.mp3': 'audio/mpeg', '.pdf': 'application/pdf',
    '.txt': 'text/plain; charset=utf-8', '.json': 'application/json',
  };
  return map[ext] || 'application/octet-stream';
}

async function listDir(dir) {
  if (!validFile(dir)) throw httpError(400, 'Ruta no permitida');
  const clean = dir.replace(/\/+$/, '') || '/';
  const cmd = `stat -c '%n|%s|%Y|%F' ${q(clean)}/* ${q(clean)}/.[!.]* 2>/dev/null; true`;
  const out = await shell(cmd, { timeout: 20000 });
  const entries = [];
  for (const line of out.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const m = line.match(/^(.*)\|(\d+)\|(\d+)\|([^|]+)$/);
    if (!m) continue;
    const name = path.posix.basename(m[1]);
    if (!name || name === '.' || name === '..') continue;
    entries.push({
      name,
      path: m[1],
      size: Number(m[2]),
      mtime: Number(m[3]) * 1000,
      isDir: /directory/i.test(m[4]),
      type: m[4],
    });
  }
  entries.sort((a, b) => (a.isDir === b.isDir ? a.name.localeCompare(b.name, 'es', { numeric: true }) : a.isDir ? -1 : 1));
  const parent = clean === '/' ? null : path.posix.dirname(clean) === clean ? null : path.posix.dirname(clean);
  return { path: clean, parent, entries };
}

async function listPhotos() {
  const roots = ['/sdcard/DCIM', '/sdcard/Pictures', '/sdcard/Download', '/sdcard/Screenshots', '/sdcard/Movies'];
  const cmd = `find ${roots.map(q).join(' ')} -type f \\( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' -o -iname '*.gif' -o -iname '*.heic' -o -iname '*.avif' \\) 2>/dev/null | sort -r | head -n 600`;
  const out = await shell(cmd, { timeout: 30000 });
  return out
    .split(/\r?\n/)
    .filter(Boolean)
    .map((p) => ({ path: p, name: path.posix.basename(p) }));
}

const thumbCache = new Map();
const THUMB_TTL = 10 * 60 * 1000;

async function getThumb(file) {
  if (!validFile(file)) throw httpError(400, 'Ruta no permitida');
  const hit = thumbCache.get(file);
  if (hit && Date.now() - hit.at < THUMB_TTL) return hit.buf;
  const raw = await adbExec(['exec-out', `cat ${q(file)}`], {
    encoding: 'buffer',
    timeout: 25000,
    maxBuffer: 128 * 1024 * 1024,
  });
  const jpeg = await ffmpegJpeg(raw, { width: 320, quality: 7 });
  thumbCache.set(file, { buf: jpeg, at: Date.now() });
  if (thumbCache.size > 400) {
    thumbCache.delete(thumbCache.keys().next().value);
  }
  return jpeg;
}

function streamPhoneFile(req, res, file, inline) {
  if (!validFile(file)) return sendJson(res, 400, { ok: false, error: 'Ruta no permitida' });
  return ensureDevice().then(() => {
    const proc = spawn(ADB, adbFullArgs(['exec-out', `cat ${q(file)}`]), {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stderr = '';
    let headersSent = false;
    const fail = (message) => {
      if (!headersSent && !res.headersSent) {
        sendJson(res, 500, { ok: false, error: message || 'No se pudo leer el archivo' });
      } else {
        res.destroy();
      }
    };
    proc.on('error', (e) => fail(e.message));
    proc.stderr.on('data', (c) => { stderr += c; });
    proc.on('close', (code) => {
      if (code !== 0 && !headersSent) fail(stderr.trim());
      else if (code !== 0) res.destroy();
    });
    proc.stdout.once('data', () => {
      headersSent = true;
      const name = path.posix.basename(file);
      res.writeHead(200, {
        'Content-Type': mimeFor(name),
        'Content-Disposition': `${inline ? 'inline' : 'attachment'}; filename*=UTF-8''${encodeURIComponent(name)}`,
        'Cache-Control': 'private, max-age=60',
        'X-Content-Type-Options': 'nosniff',
      });
    });
    proc.stdout.pipe(res);
    req.on('close', () => {
      if (!res.writableEnded) proc.kill('SIGKILL');
    });
  }).catch((e) => {
    if (!res.headersSent) sendJson(res, e.status || 500, { ok: false, error: e.message });
  });
}

async function readNotifications() {
  const out = await shell('dumpsys notification --noredact 2>/dev/null', {
    timeout: 25000,
    maxBuffer: 64 * 1024 * 1024,
  });
  const lines = out.split(/\r?\n/);
  const items = [];
  let block = null;

  const flush = () => {
    if (!block) return;
    const pkgMatch = block.header.match(/pkg=([\w.]+)/);
    if (pkgMatch) {
      const title = block.title || '';
      const text = block.text || '';
      if (title || text) {
        items.push({ package: pkgMatch[1], title, text, key: pkgMatch[1] + '|' + title + '|' + text });
      }
    }
    block = null;
  };

  for (const line of lines) {
    if (/^\s+NotificationRecord\(/.test(line)) {
      flush();
      block = { header: line, title: '', text: '' };
      continue;
    }
    if (!block) continue;
    if (/^\s*$/.test(line) && block.title && block.text) {
      flush();
      continue;
    }
    let m;
    if ((m = line.match(/android\.title=(?:String|SpannableString) \((.*)\)\s*$/))) {
      if (!block.title) block.title = m[1].trim();
    } else if ((m = line.match(/android\.text=(?:String|SpannableString) \((.*)\)\s*$/))) {
      if (!block.text) block.text = m[1].trim();
    }
  }
  flush();

  const seen = new Set();
  return items.filter((it) => {
    if (seen.has(it.key)) return false;
    seen.add(it.key);
    it.key = undefined;
    return true;
  }).slice(0, 80);
}

async function readSms(box, limit) {
  const boxes = { inbox: 'content://sms/inbox', sent: 'content://sms/sent', all: 'content://sms' };
  const uri = boxes[box] || boxes.inbox;
  const max = Math.min(Math.max(Number(limit) || 50, 1), 300);
  const out = await shell(
    `content query --uri ${uri} --projection address:body:date:type --sort 'date DESC' 2>/dev/null | head -n ${max * 2}`,
    { timeout: 25000, maxBuffer: 16 * 1024 * 1024 }
  );
  const items = [];
  for (const line of out.split(/\r?\n/)) {
    const m = line.match(/^Row: \d+ address=(.*?) body=(.*) date=(\d+),?\s*(?:type=(\d+))?$/);
    if (!m) continue;
    items.push({
      address: m[1].replace(/,\s*$/, ''),
      body: m[2].replace(/,\s*$/, ''),
      date: Number(m[3]),
      type: m[4] ? Number(m[4]) : null,
    });
    if (items.length >= max) break;
  }
  return items;
}

async function readLocation() {
  const out = await shell("dumpsys location 2>/dev/null | grep 'last location=' | head -n 20", {
    timeout: 25000,
    maxBuffer: 16 * 1024 * 1024,
  });
  const matches = [...out.matchAll(/last location=Location\[(\w+)\s+(-?[\d.]+),(-?[\d.]+)\s+hAcc=([\d.]+)/g)];
  if (!matches.length) {
    return { ok: true, location: null, message: 'Sin ubicación reciente. Activa la ubicación/GPS en el teléfono y vuelve a intentar.' };
  }
  const score = { fused: 0, gps: 1, network: 2, passive: 3 };
  matches.sort((a, b) => (score[a[1]] ?? 9) - (score[b[1]] ?? 9));
  const m = matches[0];
  return {
    ok: true,
    location: {
      provider: m[1],
      lat: Number(m[2]),
      lon: Number(m[3]),
      accuracy: Number(m[4]),
    },
  };
}

async function readApps() {
  const out = await shell('pm list packages -3 2>/dev/null', { timeout: 20000 });
  return out
    .split(/\r?\n/)
    .map((l) => l.replace(/^package:/, '').trim())
    .filter(Boolean)
    .sort();
}

async function launchApp(pkg) {
  if (!/^[a-zA-Z0-9_.]+$/.test(pkg)) throw httpError(400, 'Paquete inválido');
  const out = await shell(`monkey -p ${q(pkg)} -c android.intent.category.LAUNCHER 1 2>&1`, { timeout: 20000 });
  if (/No activities found|monkey aborted|ERROR/i.test(out)) {
    throw httpError(404, 'La app no tiene actividad de inicio o no está instalada');
  }
  return { ok: true, output: out.trim() };
}

function sanitizeName(name) {
  const base = path.basename(String(name || '')).replace(/[\u0000-\u001f]/g, '').trim();
  if (!base || base === '.' || base === '..') return null;
  return base;
}

function parseCookies(header) {
  const out = {};
  for (const part of String(header || '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) {
      try { out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim()); } catch {}
    }
  }
  return out;
}

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

function requestAuthorized(req, url) {
  const header = req.headers['x-phonedeck-token'];
  if (header && safeEqual(header, AUTH_TOKEN)) return true;
  const auth = String(req.headers.authorization || '');
  if (auth.toLowerCase().startsWith('bearer ') && safeEqual(auth.slice(7).trim(), AUTH_TOKEN)) return true;
  if (url) {
    const t = url.searchParams.get('token');
    if (t && safeEqual(t, AUTH_TOKEN)) return true;
  }
  const cookies = parseCookies(req.headers.cookie);
  return cookies.pd_auth ? safeEqual(cookies.pd_auth, AUTH_TOKEN) : false;
}

function isLoopbackAddress(addr) {
  const value = String(addr || '');
  return value === '::1' || value === '::ffff:127.0.0.1' || value.startsWith('127.');
}

function isExternalRequest(req) {
  if (req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for'] || req.headers['x-real-ip']) return true;
  return !isLoopbackAddress(req.socket && req.socket.remoteAddress);
}

function tailscaleHost() {
  if (process.env.EXTRA_HOST) return process.env.EXTRA_HOST.trim();
  try {
    const out = execSync('tailscale ip -4', { encoding: 'utf8', timeout: 3000 }).trim();
    const ip = out.split('\n').map((line) => line.trim()).find(Boolean);
    if (ip && /^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return ip;
  } catch {}
  return null;
}

function loginHtml(error) {
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhoneDeck by ZynoList · Acceso</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0a0f1c;color:#e8eefb;font:15px/1.5 system-ui,sans-serif}
form{background:#111a2e;border:1px solid #223052;border-radius:16px;padding:28px;width:min(420px,90vw)}
h1{margin:0 0 4px;font-size:22px}p{margin:0 0 18px;color:#8ea3c7;font-size:13px}
input{width:100%;box-sizing:border-box;background:#16213a;border:1px solid #223052;color:#e8eefb;border-radius:10px;padding:11px 12px;font:inherit;margin-bottom:12px}
button{width:100%;border:0;border-radius:10px;padding:11px;background:linear-gradient(135deg,#0ea5e9,#6366f1);color:#fff;font-weight:600;font-size:15px;cursor:pointer}
.err{color:#fca5a5;font-size:13px;margin-bottom:10px}
.by{margin:16px 0 0;text-align:center;font-size:12px;color:#8ea3c7}
.by b{color:#a78bfa}
</style></head><body>
<form method="POST" action="/login">
<h1>PhoneDeck</h1>
<p>Introduce el token de acceso de tu PC para entrar.</p>
${error ? '<div class="err">Token incorrecto.</div>' : ''}
<input name="token" type="password" placeholder="Token de acceso" autofocus autocomplete="off">
<button type="submit">Entrar</button>
<p class="by">by <b>ZynoList</b></p>
</form></body></html>`;
}

function handleLogin(req, res, url) {
  const token = url.searchParams.get('token');
  if (req.method === 'GET' && token && safeEqual(token, AUTH_TOKEN)) {
    res.writeHead(302, {
      'Set-Cookie': `pd_auth=${encodeURIComponent(AUTH_TOKEN)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000`,
      Location: '/',
    });
    return res.end();
  }
  if (req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 8192) req.destroy(); });
    req.on('end', () => {
      const params = new URLSearchParams(body);
      if (safeEqual(params.get('token') || '', AUTH_TOKEN)) {
        res.writeHead(302, {
          'Set-Cookie': `pd_auth=${encodeURIComponent(AUTH_TOKEN)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000`,
          Location: '/',
        });
        return res.end();
      }
      res.writeHead(401, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(loginHtml(true));
    });
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(loginHtml(false));
}

function getAgent(deviceId) {
  return agents.get(String(deviceId)) || null;
}

function touchAgent(deviceId, info) {
  let agent = agents.get(deviceId);
  if (!agent) {
    agent = {
      deviceId,
      info: {},
      status: null,
      lastSeen: 0,
      commands: [],
      nextCommand: 1,
      results: new Map(),
      waiters: new Set(),
      camera: null,
      audio: { chunks: [], nextSeq: 1, lastAt: 0 },
    };
    agents.set(deviceId, agent);
  }
  if (info && typeof info === 'object') Object.assign(agent.info, info);
  agent.lastSeen = Date.now();
  return agent;
}

function drainCommands(agent) {
  return agent.commands.splice(0, agent.commands.length);
}

function wakeAgent(agent) {
  for (const fn of Array.from(agent.waiters || [])) {
    try { fn(); } catch {}
  }
  if (agent.waiters) agent.waiters.clear();
}

async function handleApi(req, res, url) {
  const p = url.pathname;
  const method = req.method;

  if (p === '/api/status' && method === 'GET') {
    return sendJson(res, 200, await getStatus(true));
  }

  if (p === '/api/devices' && method === 'GET') {
    return sendJson(res, 200, { ok: true, devices: await listDevices(), selected: SERIAL });
  }

  if (p === '/api/device/select' && method === 'POST') {
    const body = await readJson(req);
    const devices = await listDevices();
    const found = devices.find((d) => d.serial === body.serial && d.state === 'device');
    if (!found) throw httpError(404, 'Ese dispositivo no está disponible');
    SERIAL = found.serial;
    statusCache = { at: 0, data: null };
    return sendJson(res, 200, { ok: true, serial: SERIAL });
  }

  if (p === '/api/screen/frame' && method === 'GET') {
    await ensureDevice();
    if (videoState.latest && Date.now() - videoState.lastAt < 2500) {
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' });
      return res.end(videoState.latest);
    }
    const jpeg = await captureFrame();
    res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' });
    return res.end(jpeg);
  }

  if (p === '/api/screen/stream' && method === 'GET') {
    await ensureDevice();
    res.writeHead(200, {
      'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      Pragma: 'no-cache',
      Connection: 'keep-alive',
    });
    res.write('\r\n');
    streamClients.add(res);
    if (videoState.latest && Date.now() - videoState.lastAt < 3000) {
      writeFrame(res, videoState.latest);
    }
    req.on('close', () => {
      streamClients.delete(res);
      if (!streamClients.size) scheduleStopCapture();
    });
    startCaptureForClients();
    return;
  }

  if (p === '/api/photos' && method === 'GET') {
    return sendJson(res, 200, { ok: true, photos: await listPhotos() });
  }

  if (p === '/api/thumb' && method === 'GET') {
    const file = url.searchParams.get('path') || '';
    const jpeg = await getThumb(file);
    res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'private, max-age=600' });
    return res.end(jpeg);
  }

  if (p === '/api/files' && method === 'GET') {
    const dir = url.searchParams.get('path') || '/sdcard';
    return sendJson(res, 200, { ok: true, ...(await listDir(dir)) });
  }

  if (p === '/api/file' && method === 'GET') {
    const file = url.searchParams.get('path') || '';
    return streamPhoneFile(req, res, file, url.searchParams.get('inline') === '1');
  }

  if (p === '/api/upload' && method === 'POST') {
    await ensureDevice();
    const dir = url.searchParams.get('path') || '/sdcard';
    if (!validFile(dir)) throw httpError(400, 'Ruta no permitida');
    let decodedName = req.headers['x-filename'] || '';
    try { decodedName = decodeURIComponent(decodedName); } catch {}
    const name = sanitizeName(decodedName);
    if (!name) throw httpError(400, 'Nombre de archivo inválido');
    const tmp = path.join('/tmp', `phone-web-upload-${Date.now()}-${Math.random().toString(36).slice(2)}`);
    await new Promise((resolve, reject) => {
      const ws = fs.createWriteStream(tmp);
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > 512 * 1024 * 1024) {
          ws.destroy();
          reject(httpError(413, 'Archivo demasiado grande (máx 512 MB)'));
          req.destroy();
        }
      });
      req.pipe(ws);
      ws.on('finish', resolve);
      ws.on('error', reject);
    }).catch((e) => {
      try { fs.unlinkSync(tmp); } catch {}
      throw e;
    });
    try {
      await adbExec(['push', tmp, `${dir.replace(/\/+$/, '')}/${name}`], { timeout: 120000, maxBuffer: 4 * 1024 * 1024 });
    } finally {
      try { fs.unlinkSync(tmp); } catch {}
    }
    return sendJson(res, 200, { ok: true, name, path: `${dir.replace(/\/+$/, '')}/${name}` });
  }

  if (p === '/api/notifications' && method === 'GET') {
    return sendJson(res, 200, { ok: true, notifications: await readNotifications() });
  }

  if (p === '/api/sms' && method === 'GET') {
    const box = url.searchParams.get('box') || 'inbox';
    const limit = url.searchParams.get('limit') || 50;
    return sendJson(res, 200, { ok: true, box, messages: await readSms(box, limit) });
  }

  if (p === '/api/location' && method === 'GET') {
    return sendJson(res, 200, await readLocation());
  }

  if (p === '/api/apps' && method === 'GET') {
    return sendJson(res, 200, { ok: true, apps: await readApps() });
  }

  if (p === '/api/app/launch' && method === 'POST') {
    const body = await readJson(req);
    return sendJson(res, 200, await launchApp(body.package || ''));
  }

  if (p.startsWith('/api/input/') && method === 'POST') {
    await ensureDevice();
    const body = await readJson(req);
    const action = p.slice('/api/input/'.length);
    if (action === 'tap') {
      const x = Math.round(Number(body.x));
      const y = Math.round(Number(body.y));
      if (!Number.isFinite(x) || !Number.isFinite(y)) throw httpError(400, 'Coordenadas inválidas');
      await shell(`input tap ${x} ${y}`);
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'swipe') {
      const nums = ['x1', 'y1', 'x2', 'y2', 'duration'].map((k) => Math.round(Number(body[k])));
      if (nums.some((n) => !Number.isFinite(n))) throw httpError(400, 'Parámetros inválidos');
      await shell(`input swipe ${nums[0]} ${nums[1]} ${nums[2]} ${nums[3]} ${Math.max(1, nums[4])}`);
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'text') {
      const text = String(body.text || '');
      if (!text) throw httpError(400, 'Texto vacío');
      if (text.length > 2000) throw httpError(400, 'Texto demasiado largo');
      const escaped = text.replace(/ /g, '%s');
      await shell(`input text ${q(escaped)}`);
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'key') {
      const key = String(body.key || '');
      const code = KEYCODES[key] ?? (Number.isFinite(Number(body.code)) ? Number(body.code) : null);
      if (!code || code < 0 || code > 400) throw httpError(400, 'Tecla inválida');
      await shell(`input keyevent ${code}`);
      return sendJson(res, 200, { ok: true, code });
    }
    throw httpError(404, 'Acción desconocida');
  }

  if (p.startsWith('/api/power/') && method === 'POST') {
    await ensureDevice();
    const action = p.slice('/api/power/'.length);
    statusCache = { at: 0, data: null };
    if (action === 'wake') {
      await shell('input keyevent 224');
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'sleep' || action === 'lock') {
      await shell('input keyevent 223');
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'unlock') {
      await shell('input keyevent 224');
      await new Promise((r) => setTimeout(r, 350));
      await shell('input swipe 540 2100 540 500 220');
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'reboot') {
      sendJson(res, 200, { ok: true, message: 'Reiniciando…' });
      setTimeout(() => { adbExec(['reboot']).catch(() => {}); }, 300);
      return;
    }
    if (action === 'shutdown') {
      sendJson(res, 200, { ok: true, message: 'Apagando…' });
      setTimeout(() => { shell('reboot -p').catch(() => {}); }, 300);
      return;
    }
    throw httpError(404, 'Acción desconocida');
  }

  if (p === '/api/wifi/enable' && method === 'POST') {
    await ensureDevice();
    let out = '';
    try {
      out = await adbExec(['tcpip', '5555'], { timeout: 20000 });
    } catch (err) {
      out = err.message;
    }
    await new Promise((r) => setTimeout(r, 1500));
    statusCache = { at: 0, data: null };
    let status = null;
    let lastErr = null;
    for (let attempt = 0; attempt < 4; attempt += 1) {
      try {
        status = await getStatus(true);
        break;
      } catch (err) {
        lastErr = err;
        await new Promise((r) => setTimeout(r, 1200));
      }
    }
    if (!status) {
      throw httpError(502, 'El modo TCP se envió, pero el teléfono no responde tras reiniciar ADB. Reconéctalo por USB. ' + (lastErr ? lastErr.message : ''));
    }
    if (!status.adbTcpPort && !/5555/.test(out)) {
      throw httpError(502, out.trim() || 'No se pudo activar ADB por WiFi');
    }
    return sendJson(res, 200, {
      ok: true,
      message: 'ADB por WiFi activado en el puerto 5555. Ahora pulsa «Conectar» con la IP del teléfono.',
      ips: status.ips,
      adbTcpPort: status.adbTcpPort || '5555',
    });
  }

  if (p === '/api/wifi/connect' && method === 'POST') {
    const body = await readJson(req);
    let ip = String(body.ip || '').trim();
    if (!ip) throw httpError(400, 'Falta la IP');
    if (!ip.includes(':')) ip += ':5555';
    if (!/^[\w.:\[\]-]+$/.test(ip)) throw httpError(400, 'IP inválida');
    const out = await adbRaw(['connect', ip], { timeout: 20000 });
    const devices = await listDevices();
    return sendJson(res, 200, { ok: true, message: out.trim(), devices });
  }

  if (p === '/api/wifi/disable' && method === 'POST') {
    const out = await adbRaw(['usb'], { timeout: 20000 });
    SERIAL = null;
    statusCache = { at: 0, data: null };
    return sendJson(res, 200, { ok: true, message: out.trim() || 'ADB vuelve a modo USB' });
  }

  if (p === '/api/shell' && method === 'POST') {
    await ensureDevice();
    const body = await readJson(req);
    const cmd = String(body.cmd || '').trim();
    if (!cmd) throw httpError(400, 'Comando vacío');
    const started = Date.now();
    const out = await shell(cmd, { timeout: 30000, maxBuffer: 8 * 1024 * 1024 });
    return sendJson(res, 200, { ok: true, output: out, ms: Date.now() - started });
  }

  /* ---------- API del agente Android (APK) ---------- */

  if (p === '/api/agent/register' && method === 'POST') {
    const body = await readJson(req, 4 * 1024 * 1024);
    const deviceId = String(body.deviceId || '').trim();
    if (!deviceId) throw httpError(400, 'deviceId requerido');
    const agent = touchAgent(deviceId, body.info || {});
    if (body.appVersion) agent.info.appVersion = String(body.appVersion);
    return sendJson(res, 200, { ok: true, deviceId, interval: 20, serverTime: Date.now() });
  }

  if (p === '/api/agent/heartbeat' && method === 'POST') {
    const body = await readJson(req, 8 * 1024 * 1024);
    const deviceId = String(body.deviceId || '').trim();
    if (!deviceId) throw httpError(400, 'deviceId requerido');
    const agent = touchAgent(deviceId, body.info || {});
    if (body.status) agent.status = body.status;
    return sendJson(res, 200, {
      ok: true,
      interval: 20,
      commands: drainCommands(agent),
    });
  }

  if (p === '/api/agent/poll' && method === 'GET') {
    const deviceId = String(url.searchParams.get('deviceId') || '').trim();
    if (!deviceId) throw httpError(400, 'deviceId requerido');
    const agent = touchAgent(deviceId);
    let commands = drainCommands(agent);
    if (commands.length) return sendJson(res, 200, { ok: true, commands });
    const wait = Math.min(Math.max(Number(url.searchParams.get('wait') || 20), 0), 25) * 1000;
    return await new Promise((resolve) => {
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        agent.waiters.delete(finish);
        if (!res.writableEnded && !res.destroyed) {
          try { sendJson(res, 200, { ok: true, commands: drainCommands(agent) }); } catch {}
        }
        resolve();
      };
      const timer = setTimeout(finish, wait);
      agent.waiters.add(finish);
      req.on('close', () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        agent.waiters.delete(finish);
        resolve();
      });
    });
  }

  if (p === '/api/agent/result' && method === 'POST') {
    const body = await readJson(req, 90 * 1024 * 1024);
    const deviceId = String(body.deviceId || '').trim();
    if (!deviceId) throw httpError(400, 'deviceId requerido');
    const agent = touchAgent(deviceId);
    const commandId = body.commandId != null ? String(body.commandId) : '';
    if (commandId) {
      agent.results.set(commandId, {
        ok: body.ok !== false,
        data: body.data !== undefined ? body.data : null,
        error: body.error ? String(body.error) : null,
        at: Date.now(),
      });
      while (agent.results.size > 50) {
        agent.results.delete(agent.results.keys().next().value);
      }
    }
    return sendJson(res, 200, { ok: true });
  }

  if (p === '/api/agent/camera' && method === 'POST') {
    const deviceId = String(url.searchParams.get('deviceId') || '').trim();
    if (!deviceId) throw httpError(400, 'deviceId requerido');
    const agent = touchAgent(deviceId);
    const jpeg = await readRaw(req, 5 * 1024 * 1024);
    if (!jpeg.length) throw httpError(400, 'Frame vacío');
    agent.camera = { at: Date.now(), jpeg };
    return sendJson(res, 200, { ok: true, at: agent.camera.at });
  }

  if (p === '/api/agent/audio' && method === 'POST') {
    const deviceId = String(url.searchParams.get('deviceId') || '').trim();
    if (!deviceId) throw httpError(400, 'deviceId requerido');
    const agent = touchAgent(deviceId);
    if (!agent.audio) agent.audio = { chunks: [], nextSeq: 1, lastAt: 0 };
    const pcm = await readRaw(req, 512 * 1024);
    if (!pcm.length) throw httpError(400, 'Audio vacío');
    const seq = agent.audio.nextSeq++;
    agent.audio.chunks.push({ seq, at: Date.now(), pcm });
    while (agent.audio.chunks.length > 100) agent.audio.chunks.shift();
    agent.audio.lastAt = Date.now();
    return sendJson(res, 200, { ok: true, seq });
  }

  /* ---------- API del panel remoto ---------- */

  if (p === '/api/remote/devices' && method === 'GET') {
    const list = [];
    for (const agent of agents.values()) {
      list.push({
        deviceId: agent.deviceId,
        info: agent.info,
        status: agent.status,
        lastSeen: agent.lastSeen,
        online: Date.now() - agent.lastSeen < 70000,
        commandsPending: agent.commands.length,
      });
    }
    list.sort((a, b) => b.lastSeen - a.lastSeen);
    return sendJson(res, 200, { ok: true, devices: list });
  }

  if (p === '/api/remote/command' && method === 'POST') {
    const body = await readJson(req, 2 * 1024 * 1024);
    const deviceId = String(body.deviceId || '');
    const type = String(body.type || '');
    const agent = getAgent(deviceId);
    if (!agent) throw httpError(404, 'Ese teléfono no está registrado. Instala y activa la APK PhoneLink.');
    if (Date.now() - agent.lastSeen > 120000) {
      throw httpError(409, 'La app de ese teléfono no está conectada. Abre PhoneLink y pulsa «Activar».');
    }
    if (!/^[a-z_]+$/.test(type)) throw httpError(400, 'Comando inválido');
    if (type === 'camera_start') agent.camera = null;
    if (type === 'mic_start') agent.audio = { chunks: [], nextSeq: 1, lastAt: 0 };
    const command = {
      id: String(agent.nextCommand++),
      type,
      args: body.args && typeof body.args === 'object' ? body.args : {},
      at: Date.now(),
    };
    agent.commands.push(command);
    wakeAgent(agent);
    return sendJson(res, 200, { ok: true, id: command.id });
  }

  if (p === '/api/remote/result' && method === 'GET') {
    const deviceId = String(url.searchParams.get('deviceId') || '');
    const id = String(url.searchParams.get('id') || '');
    const agent = getAgent(deviceId);
    if (!agent) throw httpError(404, 'Teléfono no registrado');
    return sendJson(res, 200, { ok: true, result: agent.results.get(id) || null });
  }

  if (p === '/api/remote/camera' && method === 'GET') {
    const deviceId = String(url.searchParams.get('deviceId') || '');
    const agent = getAgent(deviceId);
    if (!agent || !agent.camera || !agent.camera.jpeg) {
      throw httpError(404, 'No hay imagen de cámara. Activa la cámara desde el panel.');
    }
    const jpeg = agent.camera.jpeg;
    res.writeHead(200, {
      'Content-Type': 'image/jpeg',
      'Cache-Control': 'no-store',
      'Content-Length': jpeg.length,
    });
    return res.end(jpeg);
  }

  if (p === '/api/remote/audio' && method === 'GET') {
    const deviceId = String(url.searchParams.get('deviceId') || '');
    const after = Number(url.searchParams.get('after') || 0);
    const agent = getAgent(deviceId);
    if (!agent) throw httpError(404, 'Teléfono no registrado');
    if (!agent.audio) agent.audio = { chunks: [], nextSeq: 1, lastAt: 0 };
    const now = Date.now();
    const active = Boolean(agent.audio.lastAt && now - agent.audio.lastAt < 3000);
    let chunks = agent.audio.chunks.filter((chunk) => chunk.seq > after);
    if (!(after > 0)) {
      const cutoff = now - 1000;
      chunks = agent.audio.chunks.filter((chunk) => chunk.at >= cutoff);
    }
    chunks = chunks.slice(-20);
    return sendJson(res, 200, {
      ok: true,
      active,
      chunks: chunks.map((chunk) => ({
        seq: chunk.seq,
        at: chunk.at,
        pcm: chunk.pcm.toString('base64'),
      })),
    });
  }

  if (p === '/api/remote/forget' && method === 'POST') {
    const body = await readJson(req);
    agents.delete(String(body.deviceId || ''));
    return sendJson(res, 200, { ok: true });
  }

  throw httpError(404, 'Endpoint no encontrado');
}

async function handleRequest(req, res) {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (url.pathname === '/login') {
      return handleLogin(req, res, url);
    }

    if (url.pathname.startsWith('/api/agent/')) {
      if (!requestAuthorized(req, url)) {
        return sendJson(res, 401, { ok: false, error: 'Token de PhoneDeck inválido' });
      }
    } else if (isExternalRequest(req) && !requestAuthorized(req, url)) {
      if (req.method === 'GET' && !url.pathname.startsWith('/api/')) {
        res.writeHead(302, { Location: '/login?next=' + encodeURIComponent(url.pathname + url.search) });
        return res.end();
      }
      return sendJson(res, 401, { ok: false, error: 'No autorizado. Inicia sesión con el token.' });
    }

    if (url.pathname === '/' || url.pathname === '/index.html') {
      return serveIndex(res);
    }
    if (url.pathname.startsWith('/api/')) {
      return await handleApi(req, res, url);
    }
    sendJson(res, 404, { ok: false, error: 'No encontrado' });
  } catch (err) {
    if (res.headersSent) {
      res.destroy();
      return;
    }
    sendJson(res, err.status || 500, { ok: false, error: err.message || 'Error interno' });
  }
}

const server = http.createServer(handleRequest);

function logListening(host, suffix) {
  console.log(`PhoneDeck escuchando en http://${host}:${PORT}${suffix || ''}`);
  console.log(`Token de acceso: ${AUTH_TOKEN}`);
  console.log(`Entrar: http://${host}:${PORT}/login?token=${AUTH_TOKEN}`);
}

server.listen(PORT, HOST, () => {
  logListening(HOST, '');
  console.log('Conecta el teléfono por USB con depuración USB activada.');
});

const EXTRA_HOST = tailscaleHost();
if (EXTRA_HOST && EXTRA_HOST !== HOST) {
  const extraServer = http.createServer(handleRequest);
  extraServer.on('error', (err) => console.error(`No se pudo abrir ${EXTRA_HOST}:${PORT}: ${err.message}`));
  extraServer.listen(PORT, EXTRA_HOST, () => logListening(EXTRA_HOST, ' (Tailscale)'));
}

process.on('SIGINT', () => {
  stopVideoPipeline();
  for (const client of streamClients) {
    try { client.end(); } catch {}
  }
  process.exit(0);
});
