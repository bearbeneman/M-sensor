# Android Soil Alert App

This folder contains a minimal Android app that subscribes to the `soil-alerts`
Firebase Cloud Messaging topic and displays high-priority notifications when
your Netlify function broadcasts an alert.

## Getting started

1. Install [Android Studio Hedgehog or newer](https://developer.android.com/studio).
2. In the Firebase console, open the `soil-sensor-alerts` project and add an
   Android app with the package name `com.bearbeneman.soilsensor`. Download the
   generated `google-services.json`.
3. Copy `google-services.json` into `android/SoilAlertApp/app/`. The file is
   listed in `.gitignore` so you won’t commit it.
4. Open `android/SoilAlertApp` in Android Studio. When prompted, let Gradle
   sync the project (it uses AGP 8.5+ and Kotlin).
5. Build & run on a device/emulator with Google Play services. On first launch
   the app automatically subscribes to the `soil-alerts` topic.

## Customising

- Change the topic name inside `MainActivity.kt` if you update the backend.
- You can style the notification or add extra UI by editing
  `SoilAlertMessagingService.kt`.
- Make sure the ESP32 + Netlify backend are using the same shared secret and
  thresholds so alerts reach the device promptly.

