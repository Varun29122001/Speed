# Speed — Realtime Network Speed Monitor

A lightweight, always-on Android app that displays your current download speed directly in the status bar notification area. No UI, no battery-draining background polling — just a clean, persistent indicator that starts on boot and stays out of your way.

---

## What It Does

- **Real-time download speed** in the status bar icon (e.g., `76 KB/s`, `2 MB/s`)
- **Today's data usage** (Wi-Fi + Mobile) shown in the notification body
- **Auto-start on boot** — no need to manually open the app after restarting
- **Survives task removal** — continues running even if swiped away from recents
- **Zero UI** — the app has no visible activity; it launches, starts the service, and exits

---

## Architecture Overview

```
┌─────────────────────┐
│   LauncherActivity   │  ← Tap app icon → requests permissions → starts service → exits
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  SpeedTestService    │  ← Foreground service (runs indefinitely)
│  ┌───────────────┐   │
│  │ Sampling Loop  │   │  ← Coroutine: every 1 second
│  │  SpeedTester   │──▶│  ← Reads TrafficStats Rx byte counters
│  │  DataUsageTracker──▶│  ← Queries NetworkStatsManager for today's usage
│  └───────────────┘   │
│  ┌───────────────┐   │
│  │ Notification   │   │  ← Updates status bar icon + custom content view
│  │ Icon Renderer  │   │  ← Bitmap-based dynamic icon with speed value/unit
│  └───────────────┘   │
└─────────────────────┘
           ▲
┌──────────┴──────────┐
│    BootReceiver      │  ← Starts SpeedTestService on boot / app update
└─────────────────────┘
```

---

## Features in Detail

### 1. Real-Time Speed Measurement

The app reads the device's total received bytes from the kernel's `/proc/net/dev` counters via `TrafficStats.getTotalRxBytes()`. Every 1 second:

1. Reads current cumulative Rx bytes
2. Computes delta from last sample: `rxDelta = currentRx - lastRx`
3. Calculates bytes/second: `(rxDelta × 1000) / elapsedMs`
4. Applies a **3-sample moving average** to smooth out spikes and jitter
5. Formats with adaptive units:
   - `0 KB/s` when idle
   - `XXX B/s` for speeds < 1 KB/s
   - `XXX KB/s` for speeds < 1 MB/s
   - `X.XX MB/s` for speeds ≥ 1 MB/s

**No random data. No fake values.** Every number comes from real kernel traffic counters.

### 2. Status Bar Icon

The speed value is rendered as a **dynamic bitmap icon** directly in the status bar (next to the clock). The icon displays:

```
┌─────────┐
│   76    │  ← Speed value (63% of icon height, bold)
│  KB/s   │  ← Unit label (30% of icon height, bold)
└─────────┘
```

Technical details:
- Canvas size: 48dp (min 96px) with `ARGB_8888` config
- Bitmap density set to match screen density — prevents Android rescaling blur
- `Typeface.DEFAULT_BOLD` with `ANTI_ALIAS_FLAG`, `SUBPIXEL_TEXT_FLAG`, `LINEAR_TEXT_FLAG`, and `HINTING_ON` for maximum sharpness
- Text sized using `getTextBounds()` for pixel-perfect glyph-aware positioning
- Both value and unit are laid out as a single centered block with zero wasted space
- Adapts to light/dark theme automatically

### 3. Today's Data Usage

Displays today's total Wi-Fi and Mobile data consumption in the notification body:

```
[↓] 120 KB/s     📶 2.3 GB  |  📱 156.4 MB
     speed        WiFi today    Mobile today
```

- Queries `NetworkStatsManager.querySummaryForDevice()` — the official Android API for network statistics
- Sums both `rxBytes` (download) and `txBytes` (upload) for total usage
- Requires **Usage Access** permission (prompted on first launch)
- Gracefully falls back to empty if permission not granted

### 4. Persistent Foreground Notification

- **IMPORTANCE_HIGH** channel — appears at the top of the notification shade
- **Ongoing + sticky** — cannot be accidentally swiped away
- **Auto-restore on dismiss** — if somehow dismissed, a `deleteIntent` triggers re-creation
- **Custom collapsed view** with speed + data usage in a clean horizontal layout
- **Silent** — no sound, no vibration, no LED
- **Visible on lock screen** (`VISIBILITY_PUBLIC`)
- Tapping the notification opens the notification channel settings

### 5. Dynamic Launcher Shortcut

The app updates a dynamic shortcut with the current speed text and icon, so the speed is visible in the launcher's shortcut menu.

### 6. Boot Persistence

`BootReceiver` listens for:
- `BOOT_COMPLETED` — device restart
- `LOCKED_BOOT_COMPLETED` — direct boot (before unlock)
- `MY_PACKAGE_REPLACED` — app update

All three trigger the foreground service to start automatically.

### 7. Wake Lock

A `PARTIAL_WAKE_LOCK` keeps the CPU active for consistent 1-second sampling even when the screen is off. Acquired in `onCreate()`, released in `onDestroy()`.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/Speed/speedtest/
│   ├── LauncherActivity.kt          # Bootstrap: permissions → service → exit
│   ├── receiver/
│   │   └── BootReceiver.kt          # Starts service on boot / app update
│   ├── service/
│   │   └── SpeedTestService.kt      # Core foreground service + notification + icon
│   └── util/
│       ├── SpeedTester.kt           # TrafficStats sampling + moving average + formatting
│       └── DataUsageTracker.kt      # NetworkStatsManager queries for today's usage
└── res/
    ├── drawable/
    │   ├── direct_download.png      # App launcher icon asset
    │   ├── ic_launcher_foreground.xml
    │   ├── ic_launcher_background.xml
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
The heart of the app — a foreground service that:
- Creates a high-priority notification channel (silent, no badge)
- Runs a coroutine sampling loop every 1 second
- On each tick: samples speed via `SpeedTester`, queries usage via `DataUsageTracker`
- Updates the notification only when values actually change (efficiency)
- Renders a dynamic bitmap status bar icon with the current speed
- Manages a dynamic launcher shortcut with live speed
- Handles `START_STICKY` for OS restart resilience
- Holds a `PARTIAL_WAKE_LOCK` for background sampling consistency

### `SpeedTester.kt`
Pure utility object (no Android context needed):
- `sampleRealtimeSpeed()` — reads `TrafficStats.getTotalRxBytes()`, computes delta/time, applies 3-sample moving average, returns `SpeedSnapshot` with bytes/sec and formatted display text
- `formatAdaptiveSpeed()` — converts bytes/sec to human-readable string with adaptive units
- `formatDataSize()` — converts byte count to human-readable size (B, KB, MB, GB)
- `resetSampler()` — clears all state for fresh start

### `DataUsageTracker.kt`
Queries Android's `NetworkStatsManager` for real device-level statistics:
- `getTodayUsage()` — returns today's Wi-Fi and Mobile data (rx + tx) from midnight to now
- `hasUsageAccess()` — checks if `PACKAGE_USAGE_STATS` permission is granted
- Returns `null` gracefully if permission not granted or stats unavailable

### `BootReceiver.kt`
Simple `BroadcastReceiver` that starts `SpeedTestService` on boot, locked boot, or app update.

---

## Permissions

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Run the speed monitoring service in the foreground |
| `FOREGROUND_SERVICE_DATA_SYNC` | Required foreground service type declaration (Android 14+) |
| `POST_NOTIFICATIONS` | Display the speed notification (runtime permission on Android 13+) |
| `WAKE_LOCK` | Keep CPU active for consistent background sampling |
| `RECEIVE_BOOT_COMPLETED` | Auto-start service on device boot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompt user to exempt app from Doze |
| `PACKAGE_USAGE_STATS` | Query NetworkStatsManager for today's data usage |

---

## Build

```powershell
Set-Location "F:\AndroidStudioProjects"
.\gradlew.bat :app:assembleDebug
```

## Install & Run

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.Speed.speedtest/.LauncherActivity
```

## Debug Logs

```powershell
adb logcat -s SpeedTestService DataUsageTracker LauncherActivity BootReceiver
```

---

## Technical Specs

| Spec | Value |
|---|---|
| Min SDK | 31 (Android 12) |
| Target SDK | 36 |
| Language | Kotlin |
| Build System | Gradle (Kotlin DSL) |
| Dependencies | `androidx.core.ktx`, `androidx.lifecycle.runtime.ktx` |
| Notification Channel | `speed_test_channel_v5_top` (IMPORTANCE_HIGH) |
| Sampling Interval | 1000ms |
| Smoothing Window | 3 samples (moving average) |
| Icon Bitmap Size | 48dp (min 96px), ARGB_8888 |

---

## Emulator Notes

- `HWUI` / `EGL` warnings like `Failed to choose config ...` are common emulator graphics messages and do not indicate service crashes.
- `TrafficStats` counters may return `UNSUPPORTED` on some emulator images — the app handles this gracefully by showing `0 KB/s`.
- `NetworkStatsManager` requires Usage Access permission which must be granted manually in emulator settings.
