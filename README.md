# Realtime Internet Speed Runner

Lightweight Android foreground-service app that shows current download speed as a minimal, always-on notification indicator.

## Behavior

- Uses `TrafficStats.getTotalRxBytes()` every 1 second.
- Calculates current download speed from received-byte deltas.
- Applies a 3-sample moving average to reduce jitter.
- Adapts units to `B/s`, `KB/s`, or `MB/s`.
- Keeps the notification compact and uncluttered (example: `↓ 120 KB/s`).

## Core Files

- `app/src/main/java/com/Speed/speedtest/service/SpeedTestService.kt`
  - Foreground service.
  - Coroutine-based 1-second sampling loop.
  - Ongoing notification updates with full status-bar unit labels (`120KB/s`, `6MB/s`).
- `app/src/main/java/com/Speed/speedtest/util/SpeedTester.kt`
  - Device-level Rx-byte sampling.
  - 3-point moving average and speed text formatting.
- `app/src/main/java/com/Speed/speedtest/LauncherActivity.kt`
  - Requests notification permission on Android 13+.
  - Starts the foreground service and exits immediately (`Theme.NoDisplay`).

## Emulator Notes

- `HWUI`/`EGL` warnings like `Failed to choose config ...` are common emulator graphics messages and are not service crashes.

## Permissions

- `android.permission.INTERNET`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.POST_NOTIFICATIONS` (Android 13+ runtime permission)

## Build

```powershell
Set-Location "F:\AndroidStudioProjects"
.\gradlew.bat :app:assembleDebug
```

## Run and Verify

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.Speed.speedtest/.LauncherActivity
adb logcat -s SpeedTestService
```
