/*
 * Copyright (C) 2026 Speed App. All rights reserved.
 *
 * This source code is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, via any medium, is
 * strictly prohibited without express written permission from the copyright holder.
 *
 * Licensed under a proprietary license. See LICENSE file in the project root.
 */

package com.Speed.speedtest.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.Speed.speedtest.LauncherActivity
import com.Speed.speedtest.R
import com.Speed.speedtest.security.IntegrityChecker
import com.Speed.speedtest.util.SpeedTester
import com.Speed.speedtest.util.DataUsageTracker
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlin.math.roundToInt

class SpeedTestService : Service() {
    companion object {
        private const val TAG = "SpeedTestService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "speed_test_channel_v5_top"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val USAGE_REFRESH_INTERVAL_MS = 5_000L
        private const val INITIAL_SPEED_TEXT = "0 KB/s"
        private const val ICON_BASE_DP = 48f
        private const val ICON_MIN_PX = 96
        private const val ACTION_RESTORE_FROM_DISMISS = "com.Speed.speedtest.action.RESTORE_FROM_DISMISS"
        private const val DYNAMIC_SHORTCUT_ID = "speed_dynamic"
        private const val LEGACY_SHORTCUT_ID = "speed_shortcut"
        private const val SHORTCUT_UPDATE_THROTTLE_MS = 5_000L
    }

    // --- Threading architecture ---
    // Sampling:      Dedicated single-thread (avoids Default pool contention)
    // Notification:  Main thread (required for NotificationManager IPC)
    // Usage queries: IO dispatcher (NetworkStatsManager can block 100-500ms)
    // Integrity:     IO dispatcher (SharedPreferences + PackageManager I/O)

    private lateinit var samplingThread: HandlerThread
    private lateinit var samplingHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var samplingJob: Job? = null
    private var usageRefreshJob: Job? = null

    @Volatile private var cachedWifiText: String = ""
    @Volatile private var cachedMobileText: String = ""
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var lastIconLabel: String = ""
    private var lastNotificationText: String = ""
    private var lastUploadText: String = ""
    private var lastWifiText: String = ""
    private var lastMobileText: String = ""
    private var lastShortcutText: String = ""
    private var lastShortcutUpdateMs: Long = 0L
    private var shortcutRegistered: Boolean = false
    private var screenStateReceiver: BroadcastReceiver? = null
    private lateinit var lastIcon: IconCompat
    // Sentinel guarantees the first tick re-renders even if uiMode happens to equal 0.
    private var lastNightMode: Int = Int.MIN_VALUE
    private var permissionGated: Boolean = false

    // --- Bitmap pool: reuse a single bitmap to avoid GC pressure ---
    private var iconBitmap: Bitmap? = null
    private var iconSizePx: Int = 0

    // Pre-allocated paints and rects (zero allocations per frame)
    private val iconValuePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        hinting = Paint.HINTING_ON
    }
    private val iconUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textScaleX = 0.85f
        hinting = Paint.HINTING_ON
    }
    private val valueBounds = Rect()
    private val unitBounds = Rect()

    // Cached typeface (created once)
    private lateinit var cachedTypeface: Typeface

    // Cached PendingIntents (created once, reused)
    private lateinit var channelSettingsPendingIntent: PendingIntent
    private lateinit var restorePendingIntent: PendingIntent

    private fun getForegroundColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpeedTestService.onCreate()")

        // Permission gate — must still call startForeground to avoid crash
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionGated = true
            try {
                notificationManager = NotificationManagerCompat.from(this)
                createNotificationChannel()
                val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(INITIAL_SPEED_TEXT)
                    .setOngoing(false)
                    .setSilent(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build()
                startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Initialize core resources
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()

        // Cache expensive objects once
        cachedTypeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, 800, false)
        } else {
            Typeface.DEFAULT_BOLD
        }
        channelSettingsPendingIntent = buildChannelSettingsPendingIntent()
        restorePendingIntent = buildRestorePendingIntent()

        // Pre-allocate the icon bitmap (reused every frame — zero GC)
        iconSizePx = (ICON_BASE_DP * resources.displayMetrics.density).roundToInt().coerceAtLeast(ICON_MIN_PX)
        iconBitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888).apply {
            density = resources.displayMetrics.densityDpi
        }

        // Start foreground immediately (before async integrity check)
        startForegroundNotification()

        // Dedicated sampling thread — avoids Default pool contention
        samplingThread = HandlerThread("SpeedSampler").apply { start() }
        samplingHandler = Handler(samplingThread.looper)

        // Run integrity check off main thread — it does I/O (SharedPrefs, PackageManager)
        serviceScope.launch(Dispatchers.IO) {
            if (!IntegrityChecker.verifyIntegrity(this@SpeedTestService)) {
                Log.e(TAG, "Integrity check FAILED")
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }
            // All clear — register receiver and start loops on main
            withContext(Dispatchers.Main) {
                ensureDynamicShortcutRegistered()
                registerScreenStateReceiver()
            }
        }

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (permissionGated) return START_NOT_STICKY

        if (intent?.action == ACTION_RESTORE_FROM_DISMISS) {
            startForegroundNotification()
        }

        if (samplingJob?.isActive != true) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isInteractive != false) {
                SpeedTester.resetSampler()
                startSamplingLoop()
            }
        }
        if (usageRefreshJob?.isActive != true) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isInteractive != false) {
                startUsageRefreshLoop()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Network speed", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setAllowBubbles(false)
            }
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val fontScaleKey = (resources.configuration.fontScale * 100f).roundToInt()
        val nightModeKey = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastIconLabel = "0|KB/s|$fontScaleKey|$nightModeKey"
        lastNotificationText = INITIAL_SPEED_TEXT
        lastUploadText = INITIAL_SPEED_TEXT
        lastNightMode = nightModeKey
        lastIcon = buildStatusIcon("0", "KB/s")

        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(lastIcon)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentText(INITIAL_SPEED_TEXT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(Long.MAX_VALUE)
            .setShowWhen(false)
            .setSortKey("0000")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(channelSettingsPendingIntent)
            .addAction(android.R.drawable.ic_menu_manage, getString(R.string.notification_action_channel_settings), channelSettingsPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setAutoCancel(false)
            .setSilent(true)

        applyCollapsedViewOnly(INITIAL_SPEED_TEXT, INITIAL_SPEED_TEXT, "", "")

        try {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}", e)
        }
    }

    // --- Sampling on dedicated thread (not Default pool, not Main) ---

    private fun startSamplingLoop() {
        samplingJob = serviceScope.launch(samplingHandler.asCoroutineDispatcher("SpeedSampler")) {
            while (isActive) {
                val result = SpeedTester.sampleRealtimeSpeed()
                val downloadText = result?.downloadDisplayText ?: "0 KB/s"
                val uploadText = result?.uploadDisplayText ?: "0 KB/s"
                val wifi = cachedWifiText
                val mobile = cachedMobileText

                // Post notification update to main thread (required for IPC)
                mainHandler.post {
                    updateNotification(downloadText, uploadText, wifi, mobile)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    // --- Usage refresh on IO dispatcher (can block 100-500ms on some OEMs) ---

    private fun startUsageRefreshLoop() {
        usageRefreshJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val usage = DataUsageTracker.getTodayUsage(this@SpeedTestService)
                    cachedWifiText = usage?.wifiDisplayText ?: ""
                    cachedMobileText = usage?.mobileDisplayText ?: ""
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {}
                delay(USAGE_REFRESH_INTERVAL_MS)
            }
        }
    }

    // --- Notification update (always called on main thread) ---

    private fun updateNotification(downloadText: String, uploadText: String, wifiText: String, mobileText: String) {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val nightModeChanged = currentNightMode != lastNightMode

        // Skip if nothing changed
        if (!nightModeChanged &&
            downloadText == lastNotificationText &&
            uploadText == lastUploadText &&
            wifiText == lastWifiText &&
            mobileText == lastMobileText
        ) return

        if (nightModeChanged) lastShortcutText = ""

        applyCollapsedViewOnly(downloadText, uploadText, wifiText, mobileText)
        val speedIcon = getOrCreateStatusIcon(downloadText.trim())
        notificationBuilder.setSmallIcon(speedIcon)

        // Permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
        } catch (_: SecurityException) {}

        lastNotificationText = downloadText
        lastUploadText = uploadText
        lastWifiText = wifiText
        lastMobileText = mobileText
        lastNightMode = currentNightMode

        // Shortcut update (throttled, non-critical)
        updateLauncherShortcutThrottled(downloadText.trim(), speedIcon)
    }

    private fun updateLauncherShortcutThrottled(speedText: String, icon: IconCompat) {
        if (speedText == lastShortcutText) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShortcutUpdateMs < SHORTCUT_UPDATE_THROTTLE_MS) return
        if (!ensureDynamicShortcutRegistered()) return

        try {
            val launchIntent = Intent(this, LauncherActivity::class.java).apply { action = Intent.ACTION_MAIN }
            val shortcut = ShortcutInfoCompat.Builder(this, DYNAMIC_SHORTCUT_ID)
                .setShortLabel(speedText)
                .setLongLabel("Realtime speed $speedText")
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()
            ShortcutManagerCompat.updateShortcuts(this, listOf(shortcut))
            lastShortcutText = speedText
            lastShortcutUpdateMs = now
        } catch (_: Exception) {}
    }

    private fun ensureDynamicShortcutRegistered(): Boolean {
        if (shortcutRegistered) return true
        return try {
            val launchIntent = Intent(this, LauncherActivity::class.java).apply { action = Intent.ACTION_MAIN }
            val seed = ShortcutInfoCompat.Builder(this, DYNAMIC_SHORTCUT_ID)
                .setShortLabel("0 KB/s")
                .setLongLabel("Realtime speed 0 KB/s")
                .setIcon(lastIcon)
                .setIntent(launchIntent)
                .build()
            ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(LEGACY_SHORTCUT_ID))
            shortcutRegistered = ShortcutManagerCompat.pushDynamicShortcut(this, seed)
            shortcutRegistered
        } catch (_: Exception) { false }
    }

    // --- Notification view building ---

    private fun applyCollapsedViewOnly(downloadText: String, uploadText: String, wifiText: String, mobileText: String) {
        val fgColor = getForegroundColor()
        val view = RemoteViews(packageName, R.layout.notification_speed_compact)

        view.setTextViewText(R.id.text_download_speed, downloadText)
        view.setTextColor(R.id.text_download_speed, fgColor)
        view.setInt(R.id.icon_download, "setColorFilter", fgColor)

        view.setTextViewText(R.id.text_upload_speed, uploadText)
        view.setTextColor(R.id.text_upload_speed, fgColor)
        view.setInt(R.id.icon_upload, "setColorFilter", fgColor)

        view.setTextViewText(R.id.text_wifi_usage, wifiText)
        view.setTextColor(R.id.text_wifi_usage, fgColor)
        view.setTextViewText(R.id.text_mobile_usage, mobileText)
        view.setTextColor(R.id.text_mobile_usage, fgColor)
        view.setTextColor(R.id.text_separator, fgColor)
        view.setInt(R.id.icon_wifi, "setColorFilter", fgColor)
        view.setInt(R.id.icon_mobile, "setColorFilter", fgColor)

        if (wifiText.isEmpty() && mobileText.isEmpty()) {
            view.setViewVisibility(R.id.text_separator, android.view.View.GONE)
            view.setViewVisibility(R.id.icon_wifi, android.view.View.GONE)
            view.setViewVisibility(R.id.icon_mobile, android.view.View.GONE)
        }

        val fallback = buildString {
            append("\u2193$downloadText \u2191$uploadText")
            if (wifiText.isNotEmpty()) append("  W:$wifiText")
            if (mobileText.isNotEmpty()) append("  M:$mobileText")
        }
        notificationBuilder.setContentText(fallback)
        notificationBuilder.setCustomContentView(view)
        notificationBuilder.setCustomBigContentView(null)
        notificationBuilder.setCustomHeadsUpContentView(null)
    }

    // --- Icon rendering with bitmap reuse (zero allocations per frame) ---

    private fun getOrCreateStatusIcon(speedText: String): IconCompat {
        val (value, unit) = toIconParts(speedText)
        val fontScaleKey = (resources.configuration.fontScale * 100f).roundToInt()
        val nightModeKey = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val iconKey = "$value|$unit|$fontScaleKey|$nightModeKey"
        if (iconKey == lastIconLabel) return lastIcon

        lastIcon = buildStatusIcon(value, unit)
        lastIconLabel = iconKey
        return lastIcon
    }

    private fun buildStatusIcon(value: String, unit: String): IconCompat {
        // Reuse the pre-allocated bitmap — erase it instead of creating a new one
        val bitmap = iconBitmap ?: Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888).also {
            it.density = resources.displayMetrics.densityDpi
            iconBitmap = it
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)

        val fgColor = getForegroundColor()
        iconValuePaint.apply { color = fgColor; typeface = cachedTypeface }
        iconUnitPaint.apply { color = fgColor; typeface = cachedTypeface }

        val sz = iconSizePx.toFloat()
        val gap = sz * 0.03f

        // Size value text (target ~55% height)
        val targetValueH = sz * 0.55f
        iconValuePaint.textSize = targetValueH
        iconValuePaint.getTextBounds(value, 0, value.length, valueBounds)
        val vw = iconValuePaint.measureText(value)
        if (vw > sz) {
            iconValuePaint.textSize *= sz / vw
            iconValuePaint.getTextBounds(value, 0, value.length, valueBounds)
        }
        var vh = valueBounds.height().toFloat()
        if (vh > 0f && vh != targetValueH) {
            val scale = targetValueH / vh
            val widthAfter = iconValuePaint.measureText(value) * scale
            iconValuePaint.textSize *= if (widthAfter > sz) sz / iconValuePaint.measureText(value) else scale
            iconValuePaint.getTextBounds(value, 0, value.length, valueBounds)
        }

        // Size unit text (target ~42% height)
        val targetUnitH = sz * 0.42f
        iconUnitPaint.textSize = targetUnitH
        iconUnitPaint.getTextBounds(unit, 0, unit.length, unitBounds)
        val uw = iconUnitPaint.measureText(unit)
        if (uw > sz) {
            iconUnitPaint.textSize *= sz / uw
            iconUnitPaint.getTextBounds(unit, 0, unit.length, unitBounds)
        }
        var uh = unitBounds.height().toFloat()
        if (uh > 0f && uh != targetUnitH) {
            val scale = targetUnitH / uh
            val widthAfter = iconUnitPaint.measureText(unit) * scale
            iconUnitPaint.textSize *= if (widthAfter > sz) sz / iconUnitPaint.measureText(unit) else scale
            iconUnitPaint.getTextBounds(unit, 0, unit.length, unitBounds)
        }

        // Layout
        vh = valueBounds.height().toFloat()
        uh = unitBounds.height().toFloat()
        val totalBlockH = vh + gap + uh
        val blockTop = (sz - totalBlockH) / 2f
        val valueBaseline = blockTop - valueBounds.top.toFloat()
        val unitBaseline = blockTop + vh + gap - unitBounds.top.toFloat()
        val cx = sz / 2f

        canvas.drawText(value, cx, valueBaseline, iconValuePaint)
        canvas.drawText(unit, cx, unitBaseline, iconUnitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    private fun toIconParts(speedText: String): Pair<String, String> {
        val parts = speedText.trim().split(" ")
        if (parts.size < 2) return "0" to "KB/s"
        val value = parts[0].toDoubleOrNull() ?: return "0" to "KB/s"
        val unit = parts[1]
        return when (unit) {
            "MB/s" -> String.format(Locale.US, "%d", value.roundToInt().coerceIn(0, 999)) to "MB/s"
            "KB/s" -> String.format(Locale.US, "%d", value.roundToInt().coerceIn(0, 999)) to "KB/s"
            "B/s" -> String.format(Locale.US, "%d", value.roundToInt().coerceIn(0, 999)) to "B/s"
            else -> "0" to "KB/s"
        }
    }

    // --- PendingIntent builders (called once in onCreate) ---

    private fun buildRestorePendingIntent(): PendingIntent {
        val intent = Intent(this, SpeedTestService::class.java).apply { action = ACTION_RESTORE_FROM_DISMISS }
        return PendingIntent.getService(this, 1002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildChannelSettingsPendingIntent(): PendingIntent {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(this, 1003, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // --- Lifecycle ---

    override fun onDestroy() {
        super.onDestroy()
        samplingJob?.cancel()
        usageRefreshJob?.cancel()
        serviceScope.cancel()
        unregisterScreenStateReceiver()
        if (::samplingThread.isInitialized) samplingThread.quitSafely()
        iconBitmap?.recycle()
        iconBitmap = null
        Log.d(TAG, "Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        SpeedTester.resetSampler()
                        if (samplingJob?.isActive != true) startSamplingLoop()
                        if (usageRefreshJob?.isActive != true) startUsageRefreshLoop()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        samplingJob?.cancel()
                        samplingJob = null
                        usageRefreshJob?.cancel()
                        usageRefreshJob = null
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        screenStateReceiver = receiver
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenStateReceiver = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
