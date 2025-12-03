# M-sensor

This repo now contains both the ESP32 firmware (`Soil sensor.ino`) and the scaffolding for your Netlify alert service.

## Secrets workflow

1. Copy `include/wifi_secrets_example.h` → `include/wifi_secrets.h`.
2. Fill in `WIFI_SSID`, `WIFI_PASSWORD`, and `ALERT_SHARED_KEY`.
3. Never commit `include/wifi_secrets.h` (it’s ignored via `.gitignore`).

## Netlify setup

1. Install deps: `npm install`.
2. Log in: `netlify login` then `netlify init` to link this folder to your Netlify site.
3. Set environment variables in Netlify UI (Site → Settings → Build & deploy → Environment):
   - `ALERT_SHARED_SECRET` – must match `ALERT_SHARED_KEY` in `wifi_secrets.h`.
   - `FIREBASE_PROJECT_ID`
   - `FIREBASE_CLIENT_EMAIL`
   - `FIREBASE_PRIVATE_KEY` (paste the JSON private key, escaping newlines as `\n`).
4. Deploy: `git push` and Netlify will build automatically, or test locally with `npm run dev`.

`netlify/functions/alert.js` currently just validates the shared secret and sends a push notification via Firebase Admin SDK. Later we can add persistence/cooldown logic or token registration endpoints.

## ESP32 firmware

- Update Wi-Fi + secrets via `include/wifi_secrets.h`.
- Build/flash with `arduino-cli` as before.
- When thresholds change, the firmware can call the Netlify webhook at `https://<yoursite>.netlify.app/.netlify/functions/alert`.

