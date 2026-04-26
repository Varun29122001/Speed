# Realtime Internet Speed Runner
Headless Android app that continuously samples internet download speed and publishes live values in a persistent notification.
## What It Does
- No UI screens or launcher activity.
- Starts `SpeedTestService` from broadcast triggers.
- Continuously updates current speed in `KB/s` and `MB/s`.
- Shows live values in the status bar notification.
## Runtime Components
- `app/src/main/java/com/Speed/speedtest/receiver/BootReceiver.kt`
  - Handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, and `PACKAGE_ADDED`.
  - Starts the foreground service via `ContextCompat.startForegroundService(...)`.
- `app/src/main/java/com/Speed/speedtest/service/SpeedTestService.kt`
  - Foreground service with `dataSync` type.
  - Samples speed every 5 seconds.
  - Updates ongoing notification text: `Live: <KB/s> | <MB/s>`.
- `app/src/main/java/com/Speed/speedtest/util/SpeedTester.kt`
  - Downloads a bounded sample from `https://speed.hetzner.de/1MB.bin`.
  - Computes live speed from bytes read and elapsed time.
## Notes About Auto-Run After Install
Android background-start behavior varies by OS/device policy. This project includes install/update/boot broadcast triggers, but some devices may still defer background execution until the app process is allowed to run by the system.
## Build
```powershell
cd F:\AndroidStudioProjects
.\gradlew :app:assembleDebug
```
## Verify Logs
```powershell
adb logcat -s SpeedTestService SpeedTester BootReceiver
```
