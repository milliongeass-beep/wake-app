# WakeQuest ⏰

An alarm clock that won't turn off until you're actually awake.

## Install
Open the **[latest release](../../releases/latest)** on your phone, tap **WakeQuest.apk**, and allow installing from your browser when Android asks.

After the first launch, open **Settings → Make sure alarms ring** and fix anything marked ❌.

## Features
- **Missions** to turn the alarm off (up to 3 per alarm): math, typing, memory, shake, walk, photo of a spot, barcode scan
- **Wake-up check**: a few minutes after you dismiss, tap "I'm awake" within 60 s or it rings again
- Alarm goes quiet while you do a mission and comes back if you stop
- Gradually louder alarm, forced volume, vibration, custom sound, snooze limits
- **Bedtime reminder** based on your next alarm and sleep goal
- **Plan for tomorrow** notes, shown on the **morning screen** with weather and a wake-up routine
- **Sleep sounds** (rain, ocean, brown/pink/white noise) with a fade-out timer
- **Stats**: time to get up, snoozes, wake-time consistency, history

## Build
Every push to `main` builds the APK, runs unit tests and device tests on an Android 14 emulator, and publishes the APK to the `latest` release. Logs and screenshots go to the `ci-results` branch.
