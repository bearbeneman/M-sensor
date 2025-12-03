# M-sensor

This repo now contains both the ESP32 firmware (`Soil sensor.ino`) and the scaffolding for your Netlify alert service.

## Secrets workflow

1. Copy `include/wifi_secrets_example.h` → `include/wifi_secrets.h`.
2. Fill in `WIFI_SSID`, `WIFI_PASSWORD`, and `ALERT_SHARED_KEY`.
3. Never commit `include/wifi_secrets.h` (it’s ignored via `.gitignore`).

## Netlify setup

1. Install deps: `npm install`.
2. Log in: `netlify login` then `netlify init` to link this folder to your Netlify site.
3. Copy `env.template` → `.env`, fill in the values, then import them with `netlify env:import .env` (or paste manually in the UI). If you use the CLI, the vars end up in the Netlify dashboard automatically.
4. Required environment variables:
   - `ALERT_SHARED_SECRET` – must match `ALERT_SHARED_KEY` in `wifi_secrets.h`.
   - `FIREBASE_PROJECT_ID`
   - `FIREBASE_CLIENT_EMAIL`
   - `FIREBASE_PRIVATE_KEY` (paste the JSON private key, escaping newlines as `\n`, or leave it multi-line in `.env` and let the CLI import it as-is).
   - Optional: `FCM_TOPIC` (default `soil-alerts`) and `ALERT_COOLDOWN_MS` (default 20000 ms) to control topic/cooldown behaviour.
5. Link the local folder to your Netlify site (once): `netlify link --id <your-site-id>`.
6. Deploy: `git push` and Netlify will build automatically, or test locally with `npm run dev`.

`netlify/functions/alert.js` validates the shared secret, enforces a 20 s cooldown in-memory (best effort), and broadcasts to the FCM topic (`soil-alerts` by default). ESP32 webhooks only need to send `{ secret, state: "low"|"high", moisture }`.

If you install the Firebase Admin credentials locally (via `.env`) you can run `npm run dev` and POST to `http://localhost:8888/.netlify/functions/alert` for testing.

## ESP32 firmware

- Update Wi-Fi + secrets via `include/wifi_secrets.h`.
- `ALERT_SHARED_KEY` must match the Netlify/Firebase backend (`ALERT_SHARED_SECRET`).
- `ALERT_WEBHOOK_HOST` / `ALERT_WEBHOOK_PATH` in the sketch should point to your Netlify site (defaults to `soil-sensor.netlify.app`).
- The firmware automatically sends a webhook whenever moisture crosses the low/high thresholds (30 % / 80 %) and respects the 20 s cooldown configured above.
- Build/flash with `arduino-cli` as before (`arduino-cli compile ...` then `arduino-cli upload ...`).

## Android receiver

- See `android/README.md` for build instructions.
- Copy your `google-services.json` into `android/SoilAlertApp/app/`, open that folder in Android Studio, and run on a device.
- The app subscribes to the `soil-alerts` FCM topic and shows heads-up notifications using `SoilAlertMessagingService`.
- If you change the topic name or thresholds in the backend, update `MainActivity.TOPIC` to match.

## Manual test page

`test-alert.html` is a static helper page you can open via Netlify (or locally with `npx serve`) to manually POST to `/.netlify/functions/alert`. It never stores your shared secret—enter it when prompted along with the desired state/moisture values to validate the whole stack without touching the ESP32.

