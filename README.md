# Speed — Realtime Network Speed Monitor

A lightweight, always-on Android app that displays your current download and upload speed directly in the status bar notification area. No UI, no battery-draining background polling — just a clean, persistent indicator that starts on boot and stays out of your way.

**Copyright © 2026 Speed App. All rights reserved.**

---

## What It Does

- **Real-time download & upload speed** in the notification (e.g., `↓ 76 KB/s ↑ 12 KB/s`)
- **Download speed in status bar icon** (e.g., `76 KB/s` rendered as a dynamic bitmap)
- **Today's data usage** (Wi-Fi + Mobile) shown in the notification body
- **Auto-start on boot** — no need to manually open the app after restarting
- **Survives task removal** — continues running even if swiped away from recents
- **Zero UI** — the app has no visible activity; it launches, starts the service, and exits
- **Battery smart** — stops all work when screen is off, resumes instantly on unlock
- **Anti-tampering protection** — signature verification prevents repackaging

---

## Architecture Overview

```
┌─────────────────────┐
│   LauncherActivity   │  ← Tap app icon → requests permissions → starts service → exits
└──────────┬──────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────┐
│  SpeedTestService (Foreground Service - specialUse)      │
│  ┌───────────────────┐                                   │
│  │ IntegrityChecker   │  ← Signature + package verify    │
│  └───────────────────┘                                   │
│  ┌───────────────────┐                                   │
│  │ Sampling Loop (1s) │  ← Coroutine (screen-on only)    │
│  │  SpeedTester       │──▶ Reads TrafficStats Rx+Tx      │
│  │  DataUsageTracker  │──▶ Queries NetworkStatsManager    │
│  └───────────────────┘                                   │
│  ┌───────────────────┐                                   │
│  │ Notification       │  ← ↓ download ↑ upload + usage   │
│  │ Icon Renderer      │  ← Bitmap icon with download val │
│  └───────────────────┘                                   │
│  ┌───────────────────┐                                   │
│  │ Screen State       │  ← BroadcastReceiver: ON/OFF     │
│  │ Receiver           │  ← Cancels jobs on off, restarts │
│  └───────────────────┘                                   │
└─────────────────────────────────────────────────────────┘
           ▲
┌──────────┴──────────┐
│    BootReceiver      │  ← Starts service on boot / app update
└─────────────────────┘
```

---

## Features in Detail

### 1. Real-Time Speed Measurement (Download + Upload)

The app reads the device's traffic counters from the kernel's `/proc/net/dev` via:
- `TrafficStats.getTotalRxBytes()` — download (received bytes)
- `TrafficStats.getTotalTxBytes()` — upload (transmitted bytes)

Every 1 second (while screen is on):

1. Reads current cumulative Rx and Tx bytes
2. Computes delta from last sample: `rxDelta = currentRx - lastRx`, `txDelta = currentTx - lastTx`
3. Calculates bytes/second: `(delta × 1000) / elapsedMs`
4. Applies a **3-sample moving average** (separate for download and upload) to smooth jitter
5. Formats with adaptive units:
   - `0 KB/s` when idle
   - `XXX B/s` for speeds < 1 KB/s
   - `XXX KB/s` for speeds < 1 MB/s
   - `X.XX MB/s` for speeds ≥ 1 MB/s

**No random data. No fake values.** Every number comes from real kernel traffic counters.

### 2. Status Bar Icon

The **download** speed value is rendered as a **dynamic bitmap icon** directly in the status bar (next to the clock):

```
┌─────────┐
│   76    │  ← Speed value (55% of icon height, bold)
│  KB/s   │  ← Unit label (42% of icon height, bold)
└─────────┘
```

Technical details:
- Canvas size: 48dp (min 96px) with `ARGB_8888` config
- Bitmap density set to match screen density — prevents Android rescaling blur
- Font weight 800 on API 28+, `Typeface.DEFAULT_BOLD` fallback
- `ANTI_ALIAS_FLAG`, `SUBPIXEL_TEXT_FLAG`, `LINEAR_TEXT_FLAG`, and `HINTING_ON` for maximum sharpness
- Text sized using `getTextBounds()` for pixel-perfect glyph-aware positioning
- Adapts to light/dark theme automatically
- Icon cache keyed on `value|unit|fontScale|nightMode` — avoids redundant renders

### 3. Notification Layout

The custom notification displays both speeds and today's data usage:

```
┌──────────────────────────────────────────────────────┐
│  ↓ 120 KB/s    ↑ 34 KB/s       📶 2.3 GB │ 📱 156 MB │
│  download      upload           Wi-Fi       Mobile    │
└──────────────────────────────────────────────────────┘
```

- Download icon: system `stat_sys_download_done`
- Upload icon: same icon rotated 180° (flipped)
- Data usage on the right side with Wi-Fi/Mobile breakdown

### 4. Today's Data Usage

- Queries `NetworkStatsManager.querySummaryForDevice()` — the official Android API
- Sums both `rxBytes` (download) and `txBytes` (upload) for total usage
- Requires **Usage Access** permission (prompted on first launch)
- Gracefully falls back to empty if permission not granted
- Refreshed every 5 seconds on its own coroutine (never blocks speed sampling)

### 5. Persistent Foreground Notification

- **IMPORTANCE_HIGH** channel — appears at the top of the notification shade
- **Ongoing + sticky** — cannot be accidentally swiped away
- **Auto-restore on dismiss** — if somehow dismissed, a `deleteIntent` triggers re-creation
- **Custom collapsed view** with download/upload speed + data usage
- **Silent** — no sound, no vibration, no LED
- **Visible on lock screen** (`VISIBILITY_PUBLIC`)
- Tapping the notification opens the notification channel settings
- Only updates when values actually change (skips redundant IPC)

### 6. Dynamic Launcher Shortcut

The app updates a dynamic shortcut with the current speed text and icon. Throttled to every 5 seconds to minimize IPC overhead.

### 7. Boot Persistence

`BootReceiver` listens for:
- `BOOT_COMPLETED` — device restart
- `LOCKED_BOOT_COMPLETED` — direct boot (before unlock)
- `MY_PACKAGE_REPLACED` — app update

All three trigger the foreground service to start automatically.

### 8. Battery Optimization — Screen-State Awareness

Instead of holding a wake lock and sampling 24/7, the service registers a `BroadcastReceiver` for `SCREEN_ON` / `SCREEN_OFF`:

- **Screen OFF**: Both coroutine jobs (speed sampling + usage refresh) are fully cancelled. Zero CPU wakeups, zero battery drain.
- **Screen ON**: Sampler resets for a fresh delta, both jobs restart immediately. Speed is displayed within 1 second of unlock.

The foreground service stays alive (notification persists) so no re-launch is needed.

---

## Security & Protection

### Code Obfuscation (Release Builds)

- **R8 minification** enabled with resource shrinking
- **5 optimization passes** for maximum code transformation
- **Class repackaging** — all classes moved to root package, removing package structure clues
- **Custom obfuscation dictionaries** — decompiled names are meaningless single letters
- **Log stripping** — `Log.v()`, `Log.d()`, `Log.i()` calls removed from release builds
- **Resource shrinking** — unused resources removed from APK

### Anti-Tampering (Runtime)

The `IntegrityChecker` runs on every service start and verifies:

| Check | What it does | Failure action |
|-------|-------------|----------------|
| Signature verification | Compares APK signing cert hash against stored value | Service self-stops |
| Package name check | Verifies `com.Speed.speedtest` hasn't been renamed | Service self-stops |
| Debug flag check | Detects if app is running in debuggable mode | Warning logged |
| Installer verification | Can verify app came from trusted store | Configurable |

If someone decompiles the APK, modifies it, and re-signs with a different key — the app silently refuses to run.

### Proprietary License

All source code carries copyright headers and is governed by a proprietary license that prohibits:
- Copying or reproduction
- Modification or derivative works
- Distribution or sublicensing
- Reverse engineering or decompilation
- Repackaging or rebranding

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/Speed/speedtest/
│   ├── LauncherActivity.kt          # Bootstrap: permissions → service → exit
│   ├── receiver/
│   │   └── BootReceiver.kt          # Starts service on boot / app update
│   ├── security/
│   │   └── IntegrityChecker.kt      # Anti-tampering: signature + package verification
│   ├── service/
│   │   └── SpeedTestService.kt      # Core foreground service + notification + icon
│   └── util/
│       ├── SpeedTester.kt           # TrafficStats sampling (Rx+Tx) + moving average
│       └── DataUsageTracker.kt      # NetworkStatsManager queries for today's usage
└── res/
    ├── drawable/
    │   ├── direct_download.png      # App launcher icon asset
    │   ├── ic_launcher_foreground.xml
    │   ├── ic_launcher_background.xml
    │   ├── ic_upload.xml            # Upload icon (rotated download icon)
    │   ├── ic_arrow_down.xml        # Down arrow vector
    │   ├── ic_arrow_up.xml          # Up arrow vector
    │   ├── ic_wifi.xml              # Wi-Fi icon for notification
    │   └── ic_mobile_data.xml       # Mobile data icon for notification
    ├── layout/
    │   └── notification_speed_compact.xml  # Custom notification content view
    └── values/
        └── strings.xml
```

---

## Source File Details

### `LauncherActivity.kt`
Minimal bootstrap activity with `Theme.Translucent.NoTitleBar` and `excludeFromRecents`. On launch:
1. Requests `POST_NOTIFICATIONS` permission (Android 13+)
2. Requests battery optimization exemption (best-effort)
3. Requests Usage Access permission for data tracking (best-effort)
4. Starts `SpeedTestService` as a foreground service
5. Calls `finish()` immediately — no UI remains

### `SpeedTestService.kt`
The heart of the app — a foreground service (`specialUse` type) that:
- Runs `IntegrityChecker` on start — kills itself if tampered
- Creates a high-priority notification channel (silent, no badge)
- Registers a screen-state receiver (`RECEIVER_NOT_EXPORTED`) to pause/resume sampling
- Runs a coroutine sampling loop every 1 second (screen-on only)
- On each tick: samples download + upload speed via `SpeedTester`, reads cached usage
- Updates the notification only when values actually change
- Renders a dynamic bitmap status bar icon with download speed
- Manages a dynamic launcher shortcut with live speed (throttled to 5s)
- Handles `START_STICKY` for OS restart resilience

### `SpeedTester.kt`
Thread-safe (`@Synchronized`) utility object:
- `sampleRealtimeSpeed()` — reads `getTotalRxBytes()` + `getTotalTxBytes()`, computes deltas, applies separate 3-sample moving averages, returns `SpeedSnapshot` with both download and upload
- `formatAdaptiveSpeed()` — converts bytes/sec to human-readable string with adaptive units
- `formatDataSize()` — converts byte count to human-readable size (B, KB, MB, GB)
- `resetSampler()` — clears all state for fresh start

### `DataUsageTracker.kt`
Queries Android's `NetworkStatsManager` for real device-level statistics:
- `getTodayUsage()` — returns today's Wi-Fi and Mobile data (rx + tx) from midnight to now
- `hasUsageAccess()` — checks if `PACKAGE_USAGE_STATS` permission is granted

### `BootReceiver.kt`
Simple `BroadcastReceiver` that starts `SpeedTestService` on boot, locked boot, or app update. Skips start if `POST_NOTIFICATIONS` permission is denied.

### `IntegrityChecker.kt`
Anti-tampering module that verifies:
- APK signing certificate matches stored hash (detects repackaging)
- Package name is unchanged
- App is not in debuggable mode
- Installer is from a trusted source (optional)

---

## Permissions

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Run the speed monitoring service in the foreground |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required foreground service type declaration (Android 14+) |
| `POST_NOTIFICATIONS` | Display the speed notification (runtime permission on Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start service on device boot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompt user to exempt app from Doze |
| `PACKAGE_USAGE_STATS` | Query NetworkStatsManager for today's data usage |

---

## Build

```powershell
Set-Location "F:\AndroidStudioProjects"

# Debug build (no obfuscation)
.\gradlew.bat :app:assembleDebug

# Release build (R8 obfuscation + shrinking + log stripping)
.\gradlew.bat :app:assembleRelease
```

## Install & Run

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.Speed.speedtest/.LauncherActivity
```

## Debug Logs

```powershell
adb logcat -s SpeedTestService DataUsageTracker LauncherActivity BootReceiver IntegrityChecker
```

---

## Technical Specs

| Spec | Value |
|---|---|
| Min SDK | 31 (Android 12) |
| Target SDK | 36 |
| Language | Kotlin (bundled with AGP 9.2.1) |
| Build System | Gradle 9.4.1 (Kotlin DSL), AGP 9.2.1 |
| Dependencies | `androidx.core.ktx:1.10.1`, `androidx.lifecycle.runtime.ktx:2.6.1` |
| Notification Channel | `speed_test_channel_v5_top` (IMPORTANCE_HIGH) |
| FG Service Type | `specialUse` |
| Sampling Interval | 1000ms (screen-on only) |
| Usage Refresh Interval | 5000ms (screen-on only) |
| Shortcut Throttle | 5000ms |
| Smoothing Window | 3 samples (moving average, separate for DL/UL) |
| Icon Bitmap Size | 48dp (min 96px), ARGB_8888 |
| Battery Mode | Zero work when screen off; no wake lock |
| Obfuscation | R8, 5 passes, repackaging, custom dictionaries |
| Anti-Tampering | Signature + package + debug verification |

---

## License

This software is proprietary. See [LICENSE](LICENSE) for full terms.

Copyright © 2026 Speed App. All rights reserved.
