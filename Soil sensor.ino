#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WebServer.h>
#include <Preferences.h>
#include <SPIFFS.h>
#include <ESPmDNS.h>
#include "time.h"
#include "include/wifi_secrets.h"

// ---------------- Wi-Fi settings ----------------
const char* ssid     = WIFI_SSID;
const char* password = WIFI_PASSWORD;
const char* hostName = "soilmonitor";

const char* ALERT_WEBHOOK_HOST = "soil-sensor.netlify.app";
const char* ALERT_WEBHOOK_PATH = "/.netlify/functions/alert";
const unsigned long WIFI_RETRY_INTERVAL_MS = 30000;
const unsigned long ALERT_COOLDOWN_MS = 20000;
const int ALERT_LOW_DEFAULT = 30;
const int ALERT_HIGH_DEFAULT = 80;

// Defaults for alerts
const uint32_t NOTIF_COOLDOWN_DEFAULT_MS = 5 * 60 * 1000;

// ---------------- NTP / time ----------------
const char* ntpServer      = "pool.ntp.org";
const long  gmtOffset_sec  = 0;   // adjust if you want a fixed offset
const int   daylightOffset = 0;

// ---------------- Sensor / calibration ----------------
const int SENSOR_PIN          = 36;    // VP on ESP32 DevKit

// Default calibration (used on first boot, or if no stored values yet)
const int DRY_DEFAULT         = 3400;  // measured dry reading
const int WET_DEFAULT         = 1200;  // measured wet reading

// Actual calibration values (can be changed from UI and stored in flash)
int dryValue = DRY_DEFAULT;
int wetValue = WET_DEFAULT;

const unsigned long READ_INTERVAL_MS = 200;   // sample every 0.2 s
const float SMOOTHING = 0.8f;                  // for smoothed moisture

// ---------------- History buffer ----------------
// One averaged point per minute, keep last 10 days (~14,400 points)
const size_t HISTORY_POINTS = 10UL * 24UL * 60UL;
const char* HISTORY_FILE = "/history.bin";

uint32_t historyTime[HISTORY_POINTS];      // epoch seconds
uint8_t  historyMoisture[HISTORY_POINTS];  // 0–100 %
size_t   historyCount = 0;                 // how many valid entries
size_t   historyIndex = 0;                 // next write index
bool     spiffsReady   = false;
uint32_t notifCooldownMs = NOTIF_COOLDOWN_DEFAULT_MS;
int      alertLowThreshold  = ALERT_LOW_DEFAULT;
int      alertHighThreshold = ALERT_HIGH_DEFAULT;
bool     alertsEnabled      = true;
String   sensorName         = "Soil Sensor";

String jsonEscape(const String& input) {
  String output;
  output.reserve(input.length());
  for (size_t i = 0; i < input.length(); ++i) {
    char c = input[i];
    switch (c) {
      case '\"': output += "\\\""; break;
      case '\\': output += "\\\\"; break;
      case '\b': output += "\\b"; break;
      case '\f': output += "\\f"; break;
      case '\n': output += "\\n"; break;
      case '\r': output += "\\r"; break;
      case '\t': output += "\\t"; break;
      default: output += c; break;
    }
  }
  return output;
}

// Minute aggregation (downsample to 1 sample/minute)
bool     minuteBucketValid = false;
uint32_t minuteBucket      = 0;
uint32_t minuteAccum       = 0;
uint16_t minuteSamples     = 0;
unsigned long lastWiFiAttempt = 0;
bool wifiConnected = false;
bool mdnsStarted   = false;

enum AlertState {
  ALERT_NORMAL,
  ALERT_LOW,
  ALERT_HIGH
};

AlertState lastRemoteAlertState = ALERT_NORMAL;
unsigned long lastRemoteAlertMillis = 0;

void clearHistoryStorage() {
  historyCount = 0;
  historyIndex = 0;
  minuteBucketValid = false;
  minuteSamples = 0;
  minuteAccum = 0;
  minuteBucket = 0;
  if (spiffsReady && SPIFFS.exists(HISTORY_FILE)) {
    SPIFFS.remove(HISTORY_FILE);
  }
}

// ---------------- Live data globals ----------------
int   lastRaw           = 0;
int   lastMoisture      = 0;     // 0–100 %
bool  sensorInitialised = false;
float smoothMoisture    = 0.0f;
unsigned long lastReadMillis = 0;

// ---------------- System helpers ----------------
WebServer server(80);
Preferences prefs;

// ---------------- HTML + JS (served at "/") ----------------
const char MAIN_page[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>ESP32 Soil Monitor</title>
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>
    :root { color-scheme: dark; }
    body {
      margin: 0;
      padding: 1.5rem;
      font-family: system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",
                   Roboto,Helvetica,Arial,sans-serif;
      background: #020617;
      color: #e5e7eb;
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
    }
    .card {
      width: 100%;
      max-width: 520px;
      background: radial-gradient(circle at top left,#0f172a,#020617);
      border-radius: 20px;
      padding: 1.75rem 1.5rem 1.5rem;
      box-shadow:
        0 18px 45px rgba(0,0,0,0.5),
        0 0 0 1px rgba(148,163,184,0.1);
    }
    h1 {
      margin: 0 0 0.5rem;
      font-size: 1.5rem;
      letter-spacing: 0.03em;
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    h1 span.icon {
      display: inline-flex;
      width: 26px;
      height: 26px;
      border-radius: 999px;
      justify-content: center;
      align-items: center;
      background: radial-gradient(circle at 30% 0,#22c55e,#15803d);
      box-shadow: 0 0 0 1px rgba(34,197,94,0.5);
      font-size: 1.2rem;
    }
    .subtitle {
      margin: 0 0 1rem;
      color: #9ca3af;
      font-size: 0.85rem;
    }
    .value-row {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: 0.75rem;
      margin-top: 0.3rem;
    }
    .value-main {
      font-size: 2.7rem;
      font-weight: 600;
      letter-spacing: 0.02em;
    }
    .value-unit {
      font-size: 1.1rem;
      color: #9ca3af;
      margin-left: 0.15rem;
    }
    .status-pill {
      font-size: 0.8rem;
      padding: 0.25rem 0.6rem;
      border-radius: 999px;
      border: 1px solid rgba(148,163,184,0.5);
      color: #e5e7eb;
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      white-space: nowrap;
    }
    .status-dot {
      width: 7px;
      height: 7px;
      border-radius: 999px;
      background: #22c55e;
      box-shadow: 0 0 0 4px rgba(34,197,94,0.25);
    }
    .bar {
      width: 100%;
      height: 12px;
      border-radius: 999px;
      background: #020617;
      border: 1px solid rgba(30,64,175,0.7);
      margin-top: 1rem;
      overflow: hidden;
      position: relative;
    }
    .bar-inner {
      position: absolute;
      top: 0; left: 0;
      height: 100%;
      width: 0%;
      background: linear-gradient(90deg,#22c55e,#3b82f6);
      box-shadow: 0 0 20px rgba(56,189,248,0.7);
      transition: width 0.4s ease-out;
    }
    .bar-marker {
      position: absolute;
      top: -6px;
      width: 1px;
      height: 24px;
      background: rgba(148,163,184,0.5);
    }
    .bar-labels {
      display: flex;
      justify-content: space-between;
      margin-top: 0.3rem;
      font-size: 0.75rem;
      color: #9ca3af;
    }
    .notif-row {
      margin-top: 0.6rem;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    .notif-button {
      width: fit-content;
      padding: 0.3rem 0.9rem;
      border-radius: 999px;
      border: 1px solid rgba(56,189,248,0.8);
      background: rgba(30,64,175,0.85);
      color: #e0f2fe;
      font-size: 0.78rem;
      cursor: pointer;
      transition: opacity 0.2s ease;
    }
    .notif-button[disabled] {
      opacity: 0.4;
      cursor: not-allowed;
    }
    .notif-select {
      width: 100%;
      max-width: 220px;
      padding: 0.25rem 0.6rem;
      border-radius: 10px;
      border: 1px solid rgba(56,189,248,0.4);
      background: rgba(15,23,42,0.9);
      color: #e0f2fe;
      font-size: 0.8rem;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(2,minmax(0,1fr));
      gap: 0.9rem;
      margin-top: 1.2rem;
      font-size: 0.85rem;
    }
    .tile {
      padding: 0.6rem 0.7rem;
      border-radius: 12px;
      background: rgba(15,23,42,0.9);
      border: 1px solid rgba(30,64,175,0.8);
    }
    .tile-label {
      font-size: 0.75rem;
      color: #9ca3af;
      margin-bottom: 0.25rem;
    }
    .tile-value {
      font-size: 0.95rem;
    }
    .chart-container {
      margin-top: 1.4rem;
      height: 220px;
    }
    .chart-container canvas {
      width: 100%;
      height: 100%;
    }
    .cal-section {
      margin-top: 1.4rem;
      padding-top: 1rem;
      border-top: 1px solid rgba(30,64,175,0.7);
    }
    .cal-title {
      font-size: 0.95rem;
      margin-bottom: 0.5rem;
      color: #e5e7eb;
    }
    .cal-grid {
      display: grid;
      grid-template-columns: repeat(2,minmax(0,1fr));
      gap: 0.75rem;
      font-size: 0.8rem;
    }
    .cal-field label {
      display: block;
      color: #9ca3af;
      margin-bottom: 0.2rem;
    }
    .cal-field input {
      width: 100%;
      padding: 0.25rem 0.35rem;
      border-radius: 8px;
      border: 1px solid rgba(148,163,184,0.7);
      background: rgba(15,23,42,0.9);
      color: #e5e7eb;
      font-size: 0.85rem;
      box-sizing: border-box;
    }
    .cal-field button {
      margin-top: 0.25rem;
      padding: 0.2rem 0.35rem;
      font-size: 0.75rem;
      border-radius: 999px;
      border: 1px solid rgba(59,130,246,0.8);
      background: rgba(30,64,175,0.9);
      color: #e5e7eb;
      cursor: pointer;
    }
    .cal-save-row {
      margin-top: 0.7rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 0.6rem;
    }
    .cal-save-row button {
      padding: 0.35rem 0.9rem;
      border-radius: 999px;
      border: 1px solid rgba(34,197,94,0.9);
      background: linear-gradient(90deg,#22c55e,#16a34a);
      color: #022c22;
      font-size: 0.8rem;
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
    }
    .small {
      font-size: 0.75rem;
      color: #6b7280;
      margin-top: 0.8rem;
    }
  </style>
</head>
<body>
  <div class="card">
    <h1><span class="icon">🌱</span>Soil moisture</h1>
    <p class="subtitle">Live data from ESP32 + capacitive sensor.</p>

    <div class="value-row">
      <div>
        <span class="value-main" id="moistureValue">--</span>
        <span class="value-unit">%</span>
      </div>
      <div class="status-pill">
        <span class="status-dot" id="statusDot"></span>
        <span id="statusText">Connecting…</span>
      </div>
    </div>
    <div class="notif-row">
      <button id="notifButton" class="notif-button">Enable phone alerts</button>
      <label class="small" style="color:#9ca3af;">Alert cooldown</label>
      <select id="notifCooldown" class="notif-select"></select>
      <span id="notifHint" class="small">Receive dryness alerts when this page stays open.</span>
    </div>

    <div class="bar">
      <div class="bar-inner" id="moistureBar"></div>
      <div class="bar-marker" style="left:25%;"></div>
      <div class="bar-marker" style="left:50%;"></div>
      <div class="bar-marker" style="left:75%;"></div>
    </div>
    <div class="bar-labels">
      <span>Dry</span>
      <span>Medium</span>
      <span>Wet</span>
    </div>

    <div class="grid">
      <div class="tile">
        <div class="tile-label">Raw ADC</div>
        <div class="tile-value" id="rawValue">--</div>
      </div>
      <div class="tile">
        <div class="tile-label">Last update</div>
        <div class="tile-value" id="timeValue">--</div>
      </div>
      <div class="tile">
        <div class="tile-label">Wi-Fi IP</div>
        <div class="tile-value" id="ipValue">--</div>
      </div>
      <div class="tile">
        <div class="tile-label">Calibration / interval</div>
        <div class="tile-value">
          <span id="calValue">--</span><br>
          <span id="intervalValue"
                style="font-size:0.75rem;color:#9ca3af;">--</span>
        </div>
      </div>
    </div>

    <div class="chart-container">
      <canvas id="moistureChart"></canvas>
    </div>

    <div class="cal-section">
      <div class="cal-title">Calibration (stored on ESP32)</div>
      <div class="cal-grid">
        <div class="cal-field">
          <label for="wetInput">Wet raw value</label>
          <input type="number" id="wetInput" inputmode="numeric">
          <button id="setWetCurrent">Use current</button>
        </div>
        <div class="cal-field">
          <label for="dryInput">Dry raw value</label>
          <input type="number" id="dryInput" inputmode="numeric">
          <button id="setDryCurrent">Use current</button>
        </div>
      </div>
      <div class="cal-save-row">
        <button id="saveCal">Save calibration</button>
        <span id="calStatus" class="small"></span>
      </div>
    </div>

    <p class="small">
      History is stored in ESP32 RAM while powered. Chart updates every few seconds.
    </p>
  </div>

  <script>
    let historyMaxPoints = 200;
    let lastRaw = null;
    let calInitialised = false;
    let chartCanvas = null;
    let chartCtx = null;
    let chartLabels = [];
    let chartValues = [];
    let liveLabels = [];
    let liveValues = [];
    let liveMinuteLabel = null;
    const liveMaxPoints = 360; // roughly 72 seconds @ 0.2 s updates
    const MOISTURE_LOW = 30;
    const MOISTURE_HIGH = 80;
    let notifCooldownMs = 5 * 60 * 1000;
    const NOTIF_OPTIONS = [1, 5, 10, 30, 60]; // minutes
    let pendingCooldownMs = null;
    let lastNotifState = 'normal';
    let lastNotificationTime = 0;
    const NOTIF_ICON =
      'data:image/svg+xml,%3Csvg xmlns%3D%22http://www.w3.org/2000/svg%22 viewBox%3D%220 0 64 64%22%3E%3Ccircle cx%3D%2232%22 cy%3D%2232%22 r%3D%2232%22 fill%3D%22%231a2a4a%22/%3E%3Cpath d%3D%22M32 10c-8 13-14 19-14 27a14 14 0 1 0 28 0c0-8-6-14-14-27z%22 fill%3D%22%23a7f3d0%22/%3E%3C/svg%3E';

    function formatLabelFromEpoch(sec) {
      const d = new Date(sec * 1000);
      const h = String(d.getHours()).padStart(2,"0");
      const m = String(d.getMinutes()).padStart(2,"0");
      return h + ":" + m;
    }

    function setupChartCanvas() {
      chartCanvas = document.getElementById('moistureChart');
      if (!chartCanvas) return;
      chartCtx = chartCanvas.getContext('2d');
      window.addEventListener('resize', renderChart);
      renderChart();
    }

    function ensureCanvasReady() {
      if (!chartCanvas || !chartCtx) return null;
      const width = chartCanvas.clientWidth || chartCanvas.offsetWidth;
      const height = chartCanvas.clientHeight || chartCanvas.offsetHeight;
      if (!width || !height) return null;
      const dpr = window.devicePixelRatio || 1;
      const w = Math.round(width * dpr);
      const h = Math.round(height * dpr);
      if (chartCanvas.width !== w || chartCanvas.height !== h) {
        chartCanvas.width = w;
        chartCanvas.height = h;
      }
      chartCtx.setTransform(1, 0, 0, 1, 0, 0);
      chartCtx.scale(dpr, dpr);
      chartCtx.clearRect(0, 0, width, height);
      return { width, height };
    }

    function renderChart() {
      const dims = ensureCanvasReady();
      if (!dims || !chartCtx) return;
      const { width, height } = dims;
      const padding = 24;
      const plotWidth = Math.max(1, width - padding * 2);
      const plotHeight = Math.max(1, height - padding * 2);

      const bg = chartCtx.createLinearGradient(0, 0, 0, height);
      bg.addColorStop(0, 'rgba(15,23,42,0.9)');
      bg.addColorStop(1, 'rgba(2,6,23,0.95)');
      chartCtx.fillStyle = bg;
      chartCtx.fillRect(0, 0, width, height);

      chartCtx.strokeStyle = 'rgba(148,163,184,0.15)';
      chartCtx.lineWidth = 1;
      chartCtx.font = '10px "Segoe UI", sans-serif';
      chartCtx.fillStyle = '#9ca3af';
      chartCtx.textBaseline = 'middle';
      const gridSteps = 5;
      for (let i = 0; i <= gridSteps; i++) {
        const value = (i / gridSteps) * 100;
        const y = padding + plotHeight - (value / 100) * plotHeight;
        chartCtx.beginPath();
        chartCtx.moveTo(padding, y);
        chartCtx.lineTo(width - padding, y);
        chartCtx.stroke();
        chartCtx.fillText(value.toFixed(0) + '%', 4, y);
      }

      const coords = chartValues.map((val, idx) => {
        const clamped = Math.max(0, Math.min(100, val));
        const x = padding + (chartValues.length === 1
          ? plotWidth / 2
          : (idx / (chartValues.length - 1)) * plotWidth);
        const y = padding + plotHeight - (clamped / 100) * plotHeight;
        return { x, y };
      });

      if (coords.length) {
      chartCtx.beginPath();
      coords.forEach((pt, idx) => {
        if (idx === 0) chartCtx.moveTo(pt.x, pt.y);
        else chartCtx.lineTo(pt.x, pt.y);
      });
      chartCtx.lineTo(coords[coords.length - 1].x, padding + plotHeight);
      chartCtx.lineTo(coords[0].x, padding + plotHeight);
      chartCtx.closePath();
      chartCtx.fillStyle = 'rgba(34,197,94,0.15)';
      chartCtx.fill();

      chartCtx.beginPath();
      coords.forEach((pt, idx) => {
        if (idx === 0) chartCtx.moveTo(pt.x, pt.y);
        else chartCtx.lineTo(pt.x, pt.y);
      });
      chartCtx.strokeStyle = 'rgba(56,189,248,1)';
      chartCtx.lineWidth = 2;
      chartCtx.stroke();
      }

      const lastBase = coords.length ? coords[coords.length - 1] : null;

      if (liveValues.length) {
        const startX = lastBase ? lastBase.x : padding;
        const endX = width - padding;
        const span = Math.max(1, endX - startX);
        const liveCoords = liveValues.map((val, idx) => {
          const clamped = Math.max(0, Math.min(100, val));
          const ratio = liveValues.length === 1
            ? 0
            : idx / (liveValues.length - 1);
          const x = startX + ratio * span;
          const y = padding + plotHeight - (clamped / 100) * plotHeight;
          return { x, y };
        });

        chartCtx.beginPath();
        if (lastBase) chartCtx.moveTo(lastBase.x, lastBase.y);
        liveCoords.forEach((pt, idx) => {
          if (!lastBase && idx === 0) chartCtx.moveTo(pt.x, pt.y);
          chartCtx.lineTo(pt.x, pt.y);
        });
        chartCtx.strokeStyle = 'rgba(34,197,94,0.9)';
        chartCtx.lineWidth = 2;
        chartCtx.stroke();

        const lastLive = liveCoords[liveCoords.length - 1];
        chartCtx.fillStyle = '#fbbf24';
        chartCtx.beginPath();
        chartCtx.arc(lastLive.x, lastLive.y, 3, 0, Math.PI * 2);
        chartCtx.fill();
      } else if (lastBase) {
      chartCtx.fillStyle = '#22c55e';
      chartCtx.beginPath();
        chartCtx.arc(lastBase.x, lastBase.y, 3, 0, Math.PI * 2);
      chartCtx.fill();
      }

      chartCtx.textBaseline = 'top';
      chartCtx.fillStyle = '#9ca3af';
      chartCtx.textAlign = 'center';
      const labelSlots = Math.min(4, chartLabels.length);
      for (let i = 0; i < labelSlots; i++) {
        const idx = labelSlots === 1
          ? chartLabels.length - 1
          : Math.round((chartLabels.length - 1) * (i / (labelSlots - 1)));
        const label = chartLabels[idx];
        const pt = coords[idx];
        chartCtx.fillText(label, pt.x, padding + plotHeight + 6);
      }
      chartCtx.textAlign = 'start';
    }

    function appendChartPoint(label, value) {
      if (!label) return;
      const lastIdx = chartLabels.length - 1;
      if (lastIdx >= 0 && chartLabels[lastIdx] === label) {
        chartValues[lastIdx] = value;
        return;
      }
      chartLabels.push(label);
      chartValues.push(value);
      while (chartLabels.length > historyMaxPoints) chartLabels.shift();
      while (chartValues.length > historyMaxPoints) chartValues.shift();
    }

    function appendLivePoint(fullLabel, minuteLabel, value) {
      if (!fullLabel) return;
      if (liveMinuteLabel && minuteLabel !== liveMinuteLabel) {
        liveLabels = [];
        liveValues = [];
      }
      liveMinuteLabel = minuteLabel;
      liveLabels.push(fullLabel);
      liveValues.push(value);
      while (liveLabels.length > liveMaxPoints) liveLabels.shift();
      while (liveValues.length > liveMaxPoints) liveValues.shift();
    }

    function notificationsSupported() {
      return 'Notification' in window;
    }

    function updateNotifUI() {
      const btn = document.getElementById('notifButton');
      const hint = document.getElementById('notifHint');
      if (!btn || !hint) return;
      if (!notificationsSupported()) {
        btn.disabled = true;
        hint.textContent = 'Notifications are not available in this browser.';
        return;
      }
      if (Notification.permission === 'granted') {
        btn.disabled = true;
        hint.textContent = 'Alerts enabled. Keep this page open for phone notifications.';
      } else if (Notification.permission === 'denied') {
        btn.disabled = true;
        hint.textContent = 'Notifications blocked in browser settings.';
      } else {
        btn.disabled = false;
        hint.textContent = 'Tap to allow dryness alerts on this device.';
      }
    }

    function loadNotifCooldownFromStorage() {
      try {
        const stored = localStorage.getItem('notifCooldownMs');
        if (!stored) return null;
        const parsed = parseInt(stored, 10);
        if (!isNaN(parsed) && parsed > 0) {
          return parsed;
        }
      } catch (_) {}
      return null;
    }

    function saveNotifCooldown(ms) {
      try {
        localStorage.setItem('notifCooldownMs', String(ms));
      } catch (_) {}
    }

    async function pushCooldownToDevice(ms) {
      pendingCooldownMs = ms;
      try {
        const res = await fetch('/config?cooldown=' + ms);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const json = await res.json();
        if (json && typeof json.cooldown === 'number') {
          notifCooldownMs = json.cooldown;
          saveNotifCooldown(json.cooldown);
          pendingCooldownMs = null;
          const select = document.getElementById('notifCooldown');
          if (select) {
            select.value = String(json.cooldown);
          }
        }
      } catch (err) {
        console.error('Failed to save cooldown', err);
        pendingCooldownMs = null;
      }
    }

    function setupNotifications() {
      const btn = document.getElementById('notifButton');
      const select = document.getElementById('notifCooldown');
      if (!btn || !select) return;

      const stored = loadNotifCooldownFromStorage();
      if (stored) {
        notifCooldownMs = stored;
      }

      select.innerHTML = NOTIF_OPTIONS.map(min => {
        const ms = min * 60 * 1000;
        const selected = ms === notifCooldownMs ? ' selected' : '';
        return `<option value="${ms}"${selected}>${min} min</option>`;
      }).join('');

      select.onchange = function () {
        const value = parseInt(select.value, 10);
        if (!isNaN(value) && value > 0) {
          notifCooldownMs = value;
          saveNotifCooldown(value);
          pushCooldownToDevice(value);
        }
      };

      updateNotifUI();
      btn.onclick = async function () {
        if (!notificationsSupported()) return;
        if (Notification.permission === 'granted') {
          updateNotifUI();
          return;
        }
        try {
          const result = await Notification.requestPermission();
          if (result !== 'granted') {
            alert('Notification permission denied by the browser.');
          }
        } catch (err) {
          console.error('Notification permission error', err);
        }
        updateNotifUI();
      };
      window.addEventListener('focus', updateNotifUI);
    }

    function notifyUser(title, body) {
      if (!notificationsSupported()) return;
      if (Notification.permission !== 'granted') return;
      const now = Date.now();
      if (now - lastNotificationTime < notifCooldownMs) return;
      lastNotificationTime = now;
      new Notification(title, {
        body,
        icon: NOTIF_ICON,
        tag: 'soil-monitor'
      });
    }

    function maybeSendNotification(moisture) {
      if (!notificationsSupported()) return;
      if (Notification.permission !== 'granted') return;
      if (moisture <= MOISTURE_LOW && lastNotifState !== 'low') {
        notifyUser('Soil is getting dry', 'Moisture is at ' + moisture + '%. Consider watering.');
        lastNotifState = 'low';
      } else if (moisture >= MOISTURE_HIGH && lastNotifState !== 'high') {
        notifyUser('Soil is saturated', 'Moisture climbed to ' + moisture + '%.');
        lastNotifState = 'high';
      } else if (moisture > MOISTURE_LOW && moisture < MOISTURE_HIGH) {
        lastNotifState = 'normal';
      }
    }

    async function initChart() {
      setupChartCanvas();
      if (!chartCanvas) return;
      try {
        const res = await fetch('/history');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const hist = await res.json();
        historyMaxPoints = hist.maxPoints || historyMaxPoints;
        chartLabels = hist.points.map(p => formatLabelFromEpoch(p.t));
        chartValues = hist.points.map(p => p.m);
        liveLabels = [];
        liveValues = [];
        liveMinuteLabel = null;
        renderChart();
      } catch (e) {
        console.error('Chart init failed:', e);
      }
    }

    async function fetchData() {
      try {
        const res = await fetch('/data');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const json = await res.json();
        if (json.maxPoints) {
          historyMaxPoints = json.maxPoints;
        }

        lastRaw = json.raw;

        document.getElementById('moistureValue').textContent = json.moisture;
        document.getElementById('rawValue').textContent      = json.raw;
        document.getElementById('timeValue').textContent     = json.time;
        document.getElementById('ipValue').textContent       = json.ip;
        document.getElementById('calValue').textContent      =
          'Wet: ' + json.wet + ' • Dry: ' + json.dry;
        document.getElementById('intervalValue').textContent =
          'Read every ' + (json.interval / 1000).toFixed(1) + ' s';
        if (typeof json.notifCooldown === 'number' && json.notifCooldown > 0) {
          const serverCooldown = json.notifCooldown;
          const select = document.getElementById('notifCooldown');
          if (pendingCooldownMs && serverCooldown === pendingCooldownMs) {
            pendingCooldownMs = null;
          }
          const inSync = !pendingCooldownMs || serverCooldown === pendingCooldownMs;
          if (inSync) {
            notifCooldownMs = serverCooldown;
            if (select && select.value !== String(serverCooldown)) {
              const match = Array.from(select.options).some(
                opt => opt.value === String(serverCooldown)
              );
              if (!match) {
                const min = Math.round(serverCooldown / 60000);
                const opt = document.createElement('option');
                opt.value = String(serverCooldown);
                opt.textContent = min + ' min';
                select.appendChild(opt);
              }
              select.value = String(serverCooldown);
            }
            saveNotifCooldown(serverCooldown);
          }
        }

        // initialise calibration inputs from device values (once)
        if (!calInitialised) {
          const wetInput = document.getElementById('wetInput');
          const dryInput = document.getElementById('dryInput');
          wetInput.value = json.wet;
          dryInput.value = json.dry;
          calInitialised = true;
        }

        const bar = document.getElementById('moistureBar');
        bar.style.width = json.moisture + '%';

        const statusText = document.getElementById('statusText');
        const statusDot  = document.getElementById('statusDot');
        statusText.textContent = 'Online';
        statusDot.style.background = '#22c55e';
        statusDot.style.boxShadow  = '0 0 0 4px rgba(34,197,94,0.25)';

        // Update chart with newest point
        const minuteLabel = json.time.substring(11,16);
        const fullLabel   = json.time.substring(11,19);
        appendChartPoint(minuteLabel, json.moisture);
        appendLivePoint(fullLabel, minuteLabel, json.moisture);
        renderChart();
        maybeSendNotification(json.moisture);
      } catch (e) {
        console.error(e);
        const statusText = document.getElementById('statusText');
        const statusDot  = document.getElementById('statusDot');
        statusText.textContent = 'Offline / error';
        statusDot.style.background = '#ef4444';
        statusDot.style.boxShadow  = '0 0 0 4px rgba(239,68,68,0.3)';
      }
    }

    // Calibration UI handlers
    function setupCalibrationUI() {
      const wetInput  = document.getElementById('wetInput');
      const dryInput  = document.getElementById('dryInput');
      const calStatus = document.getElementById('calStatus');

      document.getElementById('setWetCurrent').onclick = function () {
        if (lastRaw != null) wetInput.value = lastRaw;
      };
      document.getElementById('setDryCurrent').onclick = function () {
        if (lastRaw != null) dryInput.value = lastRaw;
      };

      document.getElementById('saveCal').onclick = async function () {
        const wet = parseInt(wetInput.value, 10);
        const dry = parseInt(dryInput.value, 10);

        if (isNaN(wet) || isNaN(dry)) {
          calStatus.textContent = 'Enter both wet and dry values.';
          return;
        }
        if (wet <= 0 || wet >= 4096 || dry <= 0 || dry >= 4096) {
          calStatus.textContent = 'Values must be between 1 and 4095.';
          return;
        }
        if (wet === dry) {
          calStatus.textContent = 'Wet and dry must be different.';
          return;
        }

        try {
          const res = await fetch('/config?wet=' + wet + '&dry=' + dry);
          const j   = await res.json();
          if (j.ok) {
            calStatus.textContent = 'Saved to ESP32 flash.';
          } else {
            calStatus.textContent = 'Failed to save calibration.';
          }
        } catch (e) {
          console.error(e);
          calStatus.textContent = 'Error talking to ESP32.';
        }
      };
    }

    initChart();
    setupCalibrationUI();
    setupNotifications();
    fetchData();
    setInterval(fetchData, 200);
  </script>
</body>
</html>
)rawliteral";

// ------------- Helper: log a reading into history -------------
void logHistory(uint32_t t, uint8_t moisture) {
  historyTime[historyIndex]     = t;
  historyMoisture[historyIndex] = moisture;
  historyIndex = (historyIndex + 1) % HISTORY_POINTS;
  if (historyCount < HISTORY_POINTS) {
    historyCount++;
  }
}

void persistHistoryToFlash() {
  if (!spiffsReady) return;

  SPIFFS.remove(HISTORY_FILE);
  File file = SPIFFS.open(HISTORY_FILE, FILE_WRITE);
  if (!file) {
    Serial.println("Failed to open history file for write");
    return;
  }

  const size_t recordBytes = sizeof(uint32_t) + sizeof(uint8_t);
  for (size_t i = 0; i < historyCount; ++i) {
    size_t idx = (historyIndex + HISTORY_POINTS - historyCount + i) % HISTORY_POINTS;
    uint32_t t = historyTime[idx];
    uint8_t  m = historyMoisture[idx];
    file.write(reinterpret_cast<uint8_t*>(&t), sizeof(uint32_t));
    file.write(&m, sizeof(uint8_t));
  }

  file.close();
}

void loadHistoryFromFlash() {
  if (!spiffsReady) return;

  File file = SPIFFS.open(HISTORY_FILE, FILE_READ);
  if (!file) return;

  const size_t recordBytes = sizeof(uint32_t) + sizeof(uint8_t);
  size_t totalRecords = file.size() / recordBytes;
  if (totalRecords == 0) {
    file.close();
    return;
  }

  if (totalRecords > HISTORY_POINTS) {
    size_t dropRecords = totalRecords - HISTORY_POINTS;
    file.seek(dropRecords * recordBytes, SeekSet);
    totalRecords = HISTORY_POINTS;
  }

  historyCount = 0;
  historyIndex = 0;

  for (size_t i = 0; i < totalRecords; ++i) {
    uint32_t t;
    uint8_t  m;
    if (file.read(reinterpret_cast<uint8_t*>(&t), sizeof(uint32_t)) != sizeof(uint32_t)) break;
    if (file.read(&m, sizeof(uint8_t)) != sizeof(uint8_t)) break;
    logHistory(t, m);
  }

  file.close();
}

void finalizeMinuteBucket() {
  if (!minuteBucketValid || minuteSamples == 0) return;
  uint8_t avg = (minuteAccum + minuteSamples / 2) / minuteSamples;
  uint32_t bucketEpoch = minuteBucket * 60;
  logHistory(bucketEpoch, avg);
  persistHistoryToFlash();
  minuteAccum = 0;
  minuteSamples = 0;
}

void handleMinuteAggregation(uint32_t epoch, uint8_t moisture) {
  if (epoch == 0) return;
  uint32_t bucket = epoch / 60;

  if (!minuteBucketValid) {
    minuteBucketValid = true;
    minuteBucket = bucket;
  }

  if (bucket != minuteBucket) {
    finalizeMinuteBucket();
    minuteBucket = bucket;
  }

  minuteAccum += moisture;
  minuteSamples++;
}

void sendAlertWebhook(const char* state, int moisture) {
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  WiFiClientSecure client;
  client.setInsecure();
  client.setTimeout(5000);

  if (!client.connect(ALERT_WEBHOOK_HOST, 443)) {
    Serial.println("Alert webhook: connection failed");
    return;
  }

  String payload = "{\"secret\":\"";
  payload += ALERT_SHARED_KEY;
  payload += "\",\"state\":\"";
  payload += state;
  payload += "\",\"moisture\":";
  payload += moisture;
  payload += "}";

  client.println(String("POST ") + ALERT_WEBHOOK_PATH + " HTTP/1.1");
  client.println(String("Host: ") + ALERT_WEBHOOK_HOST);
  client.println("Content-Type: application/json");
  client.println("Connection: close");
  client.println("Content-Length: " + String(payload.length()));
  client.println();
  client.print(payload);

  unsigned long start = millis();
  while (client.connected() && !client.available() && millis() - start < 5000) {
    delay(10);
  }
  while (client.available()) {
    client.read();
  }
}

void updateRemoteAlertState(int moisture) {
  if (!alertsEnabled) {
    lastRemoteAlertState = ALERT_NORMAL;
    return;
  }

  AlertState nextState = ALERT_NORMAL;
  if (moisture <= alertLowThreshold) {
    nextState = ALERT_LOW;
  } else if (moisture >= alertHighThreshold) {
    nextState = ALERT_HIGH;
  }

  unsigned long nowMs = millis();

  if (nextState == ALERT_NORMAL) {
    lastRemoteAlertState = ALERT_NORMAL;
    return;
  }

  bool stateChanged = nextState != lastRemoteAlertState;
  bool cooledDown = (nowMs - lastRemoteAlertMillis) >= ALERT_COOLDOWN_MS;

  if (stateChanged || cooledDown) {
    const char* stateLabel = (nextState == ALERT_LOW) ? "low" : "high";
    sendAlertWebhook(stateLabel, moisture);
    lastRemoteAlertState = nextState;
    lastRemoteAlertMillis = nowMs;
  }
}

void onWiFiConnected() {
  wifiConnected = true;
  Serial.println("Wi-Fi connected!");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());
  if (!mdnsStarted) {
    if (MDNS.begin(hostName)) {
      MDNS.addService("http", "tcp", 80);
      mdnsStarted = true;
      Serial.print("mDNS responder started: http://");
      Serial.print(hostName);
      Serial.println(".local");
    } else {
      Serial.println("mDNS responder failed to start");
    }
  }
}

void onWiFiDisconnected() {
  if (mdnsStarted) {
    MDNS.end();
    mdnsStarted = false;
  }
  wifiConnected = false;
}

void beginWiFiConnection() {
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(hostName);
  WiFi.begin(ssid, password);
  lastWiFiAttempt = millis();
  Serial.print("Connecting to Wi-Fi ");
  Serial.print(ssid);
}

void connectWiFiBlocking() {
  beginWiFiConnection();
  int retries = 0;
  while (WiFi.status() != WL_CONNECTED && retries < 60) {
    delay(500);
    Serial.print(".");
    retries++;
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    onWiFiConnected();
  } else {
    Serial.println("Wi-Fi connection FAILED");
  }
}

void ensureWiFi() {
  if (WiFi.status() == WL_CONNECTED) {
    if (!wifiConnected) {
      onWiFiConnected();
    }
    return;
  }
  if (wifiConnected) {
    onWiFiDisconnected();
    Serial.println("Wi-Fi lost. Will retry...");
  }
  unsigned long now = millis();
  if (now - lastWiFiAttempt >= WIFI_RETRY_INTERVAL_MS) {
    Serial.println("Retrying Wi-Fi connection...");
    beginWiFiConnection();
  }
}

// ------------- Helper: formatted time string -------------
String getTimeString() {
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo)) {
    return String("No time");
  }
  char buffer[25];
  strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", &timeinfo);
  return String(buffer);
}

// ------------- Sensor reading + smoothing + logging -------------
void updateSensorIfNeeded() {
  unsigned long nowMs = millis();
  if (nowMs - lastReadMillis < READ_INTERVAL_MS) return;
  lastReadMillis = nowMs;

  int raw = analogRead(SENSOR_PIN);

  // Use current calibration values (dryValue, wetValue)
  int moisture = map(raw, dryValue, wetValue, 0, 100);
  moisture = constrain(moisture, 0, 100);

  if (!sensorInitialised) {
    smoothMoisture    = moisture;
    sensorInitialised = true;
  } else {
    smoothMoisture = SMOOTHING * smoothMoisture +
                     (1.0f - SMOOTHING) * moisture;
  }

  lastRaw      = raw;
  lastMoisture = (int)(smoothMoisture + 0.5f);

  // Feed minute-level aggregation for persistent history
  time_t nowEpoch;
  time(&nowEpoch);
  uint32_t t32 = (nowEpoch > 0) ? (uint32_t)nowEpoch : 0;
  handleMinuteAggregation(t32, (uint8_t)lastMoisture);
  updateRemoteAlertState(lastMoisture);
}

// ------------- HTTP handlers -------------
void handleRoot() {
  server.send_P(200, "text/html", MAIN_page);
}

void handleData() {
  updateSensorIfNeeded();

  String json = "{";
  json += "\"name\":\""   + jsonEscape(sensorName) + "\",";
  json += "\"raw\":"      + String(lastRaw)      + ",";
  json += "\"moisture\":" + String(lastMoisture) + ",";
  json += "\"time\":\""   + getTimeString()      + "\",";
  json += "\"ip\":\""     + WiFi.localIP().toString() + "\",";
  json += "\"wet\":"      + String(wetValue)     + ",";
  json += "\"dry\":"      + String(dryValue)     + ",";
  json += "\"interval\":" + String(READ_INTERVAL_MS) + ",";
  json += "\"maxPoints\":" + String(HISTORY_POINTS) + ",";
  json += "\"notifCooldown\":" + String(notifCooldownMs) + ",";
  json += "\"alertLow\":" + String(alertLowThreshold) + ",";
  json += "\"alertHigh\":" + String(alertHighThreshold) + ",";
  json += "\"alertsEnabled\":";
  json += (alertsEnabled ? "true" : "false");
  json += "}";
  server.send(200, "application/json", json);
}

void handleHistory() {
  String json = "{\"maxPoints\":" + String(HISTORY_POINTS) + ",\"points\":[";
  for (size_t i = 0; i < historyCount; ++i) {
    size_t idx = (historyIndex + HISTORY_POINTS - historyCount + i) % HISTORY_POINTS;
    json += "{\"t\":";
    json += String(historyTime[idx]);
    json += ",\"m\":";
    json += String(historyMoisture[idx]);
    json += "}";
    if (i + 1 < historyCount) json += ",";
  }
  json += "]}";
  server.send(200, "application/json", json);
}

// Save calibration from query string (?wet=1234&dry=3456)
void handleConfig() {
  bool updated = false;
  bool historyCleared = false;

  if (server.hasArg("wet")) {
    int w = server.arg("wet").toInt();
    if (w > 0 && w < 4096) {
      wetValue = w;
      prefs.putInt("wet", wetValue);
      updated = true;
    }
  }

  if (server.hasArg("dry")) {
    int d = server.arg("dry").toInt();
    if (d > 0 && d < 4096) {
      dryValue = d;
      prefs.putInt("dry", dryValue);
      updated = true;
    }
  }

  if (server.hasArg("cooldown")) {
    uint32_t cd = (uint32_t) server.arg("cooldown").toInt();
    if (cd >= 60000 && cd <= 24UL * 60UL * 60UL * 1000UL) {
      notifCooldownMs = cd;
      prefs.putUInt("cooldown", notifCooldownMs);
      updated = true;
    }
  }

  bool lowArg  = server.hasArg("alertLow");
  bool highArg = server.hasArg("alertHigh");
  if (lowArg || highArg) {
    int requestedLow  = lowArg  ? server.arg("alertLow").toInt()  : alertLowThreshold;
    int requestedHigh = highArg ? server.arg("alertHigh").toInt() : alertHighThreshold;
    bool lowValid  = requestedLow  >= 0 && requestedLow  <= 100;
    bool highValid = requestedHigh >= 0 && requestedHigh <= 100;
    if (lowValid && highValid && requestedLow < requestedHigh) {
      if (lowArg && requestedLow != alertLowThreshold) {
        alertLowThreshold = requestedLow;
        prefs.putUInt("alertLow", (uint32_t)alertLowThreshold);
        updated = true;
      }
      if (highArg && requestedHigh != alertHighThreshold) {
        alertHighThreshold = requestedHigh;
        prefs.putUInt("alertHigh", (uint32_t)alertHighThreshold);
        updated = true;
      }
    }
  }

  if (server.hasArg("alerts")) {
    alertsEnabled = server.arg("alerts").toInt() != 0;
    prefs.putBool("alertsEnabled", alertsEnabled);
    updated = true;
  }

  if (server.hasArg("name")) {
    String requested = server.arg("name");
    requested.trim();
    if (requested.length() > 0 && requested.length() <= 32) {
      sensorName = requested;
      prefs.putString("sensorName", sensorName);
      updated = true;
    }
  }

  if (server.hasArg("clearHistory") && server.arg("clearHistory").toInt() != 0) {
    clearHistoryStorage();
    historyCleared = true;
    updated = true;
  }

  String json = "{\"ok\":";
  json += (updated ? "true" : "false");
  json += ",\"wet\":" + String(wetValue) + ",\"dry\":" + String(dryValue);
  json += ",\"cooldown\":" + String(notifCooldownMs);
  json += ",\"alertLow\":" + String(alertLowThreshold);
  json += ",\"alertHigh\":" + String(alertHighThreshold);
  json += ",\"alertsEnabled\":";
  json += (alertsEnabled ? "true" : "false");
  json += ",\"name\":\"" + jsonEscape(sensorName) + "\"";
  json += ",\"historyCleared\":";
  json += (historyCleared ? "true" : "false");
  json += "}";
  server.send(200, "application/json", json);
}

void handleFavicon() {
  server.send(204);
}

void handleNotFound() {
  server.send(404, "text/plain", "Not found");
}

// ------------- Setup -------------
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("ESP32 Soil Monitor with Chart.js + calibration");

  if (SPIFFS.begin(true)) {
    spiffsReady = true;
    Serial.println("SPIFFS mounted");
    loadHistoryFromFlash();
  } else {
    Serial.println("SPIFFS mount failed");
  }

  // Load calibration from NVS (flash)
  prefs.begin("soilmon", false); // namespace "soilmon"
  wetValue = prefs.getInt("wet", WET_DEFAULT);
  dryValue = prefs.getInt("dry", DRY_DEFAULT);
  notifCooldownMs = prefs.getUInt("cooldown", NOTIF_COOLDOWN_DEFAULT_MS);
  sensorName = prefs.getString("sensorName", "Soil Sensor");
  alertLowThreshold  = (int)prefs.getUInt("alertLow", ALERT_LOW_DEFAULT);
  alertHighThreshold = (int)prefs.getUInt("alertHigh", ALERT_HIGH_DEFAULT);
  alertsEnabled      = prefs.getBool("alertsEnabled", true);

  Serial.print("Calibration - wet: ");
  Serial.print(wetValue);
  Serial.print(" dry: ");
  Serial.println(dryValue);

  // Wi-Fi
  connectWiFiBlocking();
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Starting offline; background retries enabled.");
  }

  // Time via NTP
  configTime(gmtOffset_sec, daylightOffset, ntpServer);
  Serial.println("NTP time requested…");

  // Routes
  server.on("/", handleRoot);
  server.on("/data", handleData);
  server.on("/history", handleHistory);
  server.on("/config", handleConfig);
  server.on("/favicon.ico", handleFavicon);
  server.onNotFound(handleNotFound);
  server.begin();
  Serial.println("HTTP server started on port 80");

  // First reading
  updateSensorIfNeeded();
}

// ------------- Main loop -------------
void loop() {
  ensureWiFi();
  updateSensorIfNeeded();
  server.handleClient();
}
