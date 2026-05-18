package com.Speed.speedtest.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import android.os.IBinder
import android.os.PowerManager
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
import com.Speed.speedtest.util.SpeedTester
import com.Speed.speedtest.util.DataUsageTracker
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null
    private var usageRefreshJob: Job? = null
    @Volatile private var cachedWifiText: String = ""
    @Volatile private var cachedMobileText: String = ""
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var lastIconLabel: String = ""
    private var lastNotificationText: String = ""
    private var lastWifiText: String = ""
    private var lastMobileText: String = ""
    private var lastShortcutText: String = ""
    private var shortcutRegistered: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var lastIcon: IconCompat
    private var lastBitmap: android.graphics.Bitmap? = null
    // Sentinel guarantees the first tick re-renders even if uiMode happens to equal 0.
    private var lastNightMode: Int = Int.MIN_VALUE
    // Set when onCreate detects POST_NOTIFICATIONS is denied; suppresses sampling
    // and onStartCommand work so a self-stop tears down without any further activity.
    private var permissionGated: Boolean = false
    private val iconValuePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        hinting = Paint.HINTING_ON
    }
    private val iconUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textScaleX = 0.85f // Squeeze horizontally so it can grow much taller without hitting width limits
        hinting = Paint.HINTING_ON
    }
    private val valueBounds = Rect()
    private val unitBounds = Rect()

    // returns foreground color matching system light/dark theme
    private fun getForegroundColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpeedTestService.onCreate() called - initializing service")

        // Defensive gate: if POST_NOTIFICATIONS is denied (Android 13+), the foreground
        // notification cannot be displayed and any caller that used startForegroundService()
        // would see a ForegroundServiceDidNotStartInTimeException unless we still call
        // startForeground() once before stopping. Post a minimal silent notification to
        // satisfy the contract, then tear down without acquiring the wake lock or
        // starting the sampling loop.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS denied; satisfying startForeground contract then self-stopping")
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
                startForeground(
                    NOTIFICATION_ID,
                    placeholder,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                Log.w(TAG, "Placeholder startForeground failed (likely non-foreground entry): ${e.message}")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        acquireCpuWakeLock() // acquired once; released in onDestroy
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        startForegroundNotification()
        ensureDynamicShortcutRegistered()
        Log.d(TAG, "Speed service created and foreground notification started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If onCreate gated us off due to missing permission, do not run any sampling
        // and do not request a sticky restart.
        if (permissionGated) {
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESTORE_FROM_DISMISS) {
            // User/system dismissed notification: immediately restore foreground notification.
            startForegroundNotification()
        }

        if (samplingJob?.isActive != true) {
            SpeedTester.resetSampler()
            startSamplingLoop()
            startUsageRefreshLoop()
            Log.d(TAG, "Sampling started")
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Download speed",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.enableLights(false)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        // Ensure sticky: disable importance reduction
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            channel.setAllowBubbles(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val createdChannel = manager?.getNotificationChannel(CHANNEL_ID)
        Log.d(
            TAG,
            "Channel ready id=$CHANNEL_ID importance=${createdChannel?.importance} notificationsEnabled=${notificationManager.areNotificationsEnabled()}"
        )
    }

    private fun startForegroundNotification() {
        // Seed the icon cache key with the current font-scale and night-mode so the
        // first sample tick can short-circuit when value/unit haven't changed.
        val fontScaleKey = (resources.configuration.fontScale * 100f).roundToInt()
        val nightModeKey = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastIconLabel = "0|KB/s|$fontScaleKey|$nightModeKey"
        lastNotificationText = INITIAL_SPEED_TEXT
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
            .setContentIntent(buildChannelSettingsPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_manage,
                getString(R.string.notification_action_channel_settings),
                buildChannelSettingsPendingIntent()
            )
            .setDeleteIntent(buildRestorePendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setAutoCancel(false)
            .setSilent(true)

        applyCollapsedViewOnly(INITIAL_SPEED_TEXT, "", "")

        try {
            // Check permission before starting foreground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS permission not granted - notification may not display")
                }
            }
            
            startForeground(
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            Log.i(TAG, "Foreground notification started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }
    }

    private fun startSamplingLoop() {
        samplingJob = serviceScope.launch {
            while (isActive) {
                performSpeedSample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /**
     * Refreshes today's Wi-Fi/Mobile usage on a slower cadence (every 5 s) so that
     * NetworkStatsManager queries — which can take hundreds of ms on some OEM
     * builds — never block the per-second speed sampling/notification update.
     * Runs on its own coroutine; the speed loop reads the cached results.
     */
    private fun startUsageRefreshLoop() {
        usageRefreshJob = serviceScope.launch {
            while (isActive) {
                try {
                    val usage = DataUsageTracker.getTodayUsage(this@SpeedTestService)
                    cachedWifiText = usage?.wifiDisplayText ?: ""
                    cachedMobileText = usage?.mobileDisplayText ?: ""
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce  // never swallow cancellation
                } catch (e: Exception) {
                    Log.d(TAG, "Usage refresh failed: ${e.message}")
                }
                delay(USAGE_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun performSpeedSample() {
        try {
            val result = SpeedTester.sampleRealtimeSpeed()
            val speedText = if (result == null) "0 KB/s" else result.displayText

            // Read cached usage values populated by startUsageRefreshLoop.
            updateNotification(speedText, cachedWifiText, cachedMobileText)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce  // never swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Error during speed sampling: ${e.message}", e)
            updateNotification("0 KB/s", cachedWifiText, cachedMobileText)
        }
    }

    private fun updateNotification(text: String, wifiText: String, mobileText: String) {
        try {
            val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val nightModeChanged = currentNightMode != lastNightMode
            if (!nightModeChanged &&
                text == lastNotificationText &&
                wifiText == lastWifiText &&
                mobileText == lastMobileText
            ) return

            // On a theme toggle, force the launcher shortcut path to refresh its icon
            // even when speedText itself has not changed (its own short-circuit keys on text).
            if (nightModeChanged) {
                lastShortcutText = ""
            }

            applyCollapsedViewOnly(text, wifiText, mobileText)
            val fullSpeedText = text.trim()
            val speedIcon = getOrCreateStatusIcon(fullSpeedText)
            notificationBuilder.setSmallIcon(speedIcon)
            updateLauncherShortcutIcon(fullSpeedText, speedIcon)

            // Check permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS permission not granted; unable to post notification")
                    return
                }
            }

            try {
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                Log.d(TAG, "Notification updated: $text")
                lastNotificationText = text
                lastWifiText = wifiText
                lastMobileText = mobileText
                lastNightMode = currentNightMode
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException posting notification: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}", e)
        }
    }

    private fun ensureDynamicShortcutRegistered(): Boolean {
        if (shortcutRegistered) return true

        return try {
            val launchIntent = Intent(this, LauncherActivity::class.java).apply {
                action = Intent.ACTION_MAIN
            }
            val seedShortcut = ShortcutInfoCompat.Builder(this, DYNAMIC_SHORTCUT_ID)
                .setShortLabel("0 KB/s")
                .setLongLabel("Realtime speed 0 KB/s")
                .setIcon(buildStatusIcon("0", "KB/s"))
                .setIntent(launchIntent)
                .build()

            // Remove stale dynamic legacy entry; pinned copies (if any) are updated in updateLauncherShortcutIcon.
            ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(LEGACY_SHORTCUT_ID))
            val added = ShortcutManagerCompat.pushDynamicShortcut(this, seedShortcut)
            shortcutRegistered = added
            added
        } catch (e: Exception) {
            Log.d(TAG, "Dynamic shortcut init skipped: ${e.message}")
            false
        }
    }

    private fun updateLauncherShortcutIcon(speedText: String, icon: IconCompat) {
        try {
            if (speedText == lastShortcutText) return
            if (!ensureDynamicShortcutRegistered()) return

            val launchIntent = Intent(this, LauncherActivity::class.java).apply {
                action = Intent.ACTION_MAIN
            }
            val liveShortcut = ShortcutInfoCompat.Builder(this, DYNAMIC_SHORTCUT_ID)
                .setShortLabel(speedText)
                .setLongLabel("Realtime speed $speedText")
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()

            val legacyShortcut = ShortcutInfoCompat.Builder(this, LEGACY_SHORTCUT_ID)
                .setShortLabel(speedText)
                .setLongLabel("Realtime speed $speedText")
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()

            ShortcutManagerCompat.updateShortcuts(this, listOf(liveShortcut, legacyShortcut))
            lastShortcutText = speedText
        } catch (e: Exception) {
            Log.d(TAG, "Could not update launcher shortcut: ${e.message}")
        }
    }

    private fun buildRestorePendingIntent(): PendingIntent {
        val intent = Intent(this, SpeedTestService::class.java).apply {
            action = ACTION_RESTORE_FROM_DISMISS
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, 1002, intent, flags)
    }

    private fun buildChannelSettingsPendingIntent(): PendingIntent {
        val settingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 1003, settingsIntent, flags)
    }

    private fun applyCollapsedViewOnly(text: String, wifiText: String, mobileText: String) {
        val fgColor = getForegroundColor()
        // Build a FRESH RemoteViews on every update. Reusing a long-lived RemoteViews
        // and mutating it works on AOSP, but several OEM skins occasionally fall back
        // to the standard notification template (showing raw "W:/M:" text) when the
        // mutated action list is parceled. A fresh instance per notify() avoids that.
        val view = RemoteViews(packageName, R.layout.notification_speed_compact)
        view.setTextViewText(R.id.text_speed, text)
        view.setTextColor(R.id.text_speed, fgColor)
        view.setTextViewText(R.id.text_wifi_usage, wifiText)
        view.setTextColor(R.id.text_wifi_usage, fgColor)
        view.setTextViewText(R.id.text_mobile_usage, mobileText)
        view.setTextColor(R.id.text_mobile_usage, fgColor)
        view.setTextColor(R.id.text_separator, fgColor)
        view.setInt(R.id.icon_status, "setColorFilter", fgColor)
        view.setInt(R.id.icon_wifi, "setColorFilter", fgColor)
        view.setInt(R.id.icon_mobile, "setColorFilter", fgColor)
        // Hide the separator when both usage values are empty so the standard template
        // fallback never displays a stray "|" with nothing around it.
        if (wifiText.isEmpty() && mobileText.isEmpty()) {
            view.setViewVisibility(R.id.text_separator, android.view.View.GONE)
        }

        // System-template fallback text (used when the system can't render the
        // custom view). Keep it readable.
        val fallback = buildString {
            append(text)
            if (wifiText.isNotEmpty()) append("  Wi-Fi $wifiText")
            if (mobileText.isNotEmpty()) append("  Mobile $mobileText")
        }
        notificationBuilder.setContentText(fallback)
        notificationBuilder.setCustomContentView(view)
        // Explicitly clear expanded and heads-up custom layouts so only contracted content is defined.
        notificationBuilder.setCustomBigContentView(null)
        notificationBuilder.setCustomHeadsUpContentView(null)
    }

    private fun getOrCreateStatusIcon(speedText: String): IconCompat {
        val (value, unit) = toIconParts(speedText)
        val fontScaleKey = (resources.configuration.fontScale * 100f).roundToInt()
        val nightModeKey = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val iconKey = "$value|$unit|$fontScaleKey|$nightModeKey"
        if (iconKey == lastIconLabel) {
            return lastIcon
        }

        lastIcon = buildStatusIcon(value, unit)
        lastIconLabel = iconKey
        return lastIcon
    }

    private fun buildStatusIcon(value: String, unit: String): IconCompat {
        // Note: do NOT recycle lastBitmap here — it may still be referenced by the
        // currently posted notification's IconCompat, the launcher dynamic shortcut's
        // IconCompat, or an in-flight system render pass. Intermediate bitmaps are
        // small (ARGB_8888 ~96×96, ~36 KB) and are reclaimed by the GC. The most
        // recent bitmap is recycled exactly once in onDestroy().
        val iconSizePx = (ICON_BASE_DP * resources.displayMetrics.density).roundToInt().coerceAtLeast(ICON_MIN_PX)
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        // Tell Android this bitmap is already at screen density — prevents rescaling blur
        bitmap.density = resources.displayMetrics.densityDpi
        lastBitmap = bitmap
        val canvas = Canvas(bitmap)

        val fgColor = getForegroundColor()
        
        val sysTypeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, 800, false)
        } else {
            Typeface.DEFAULT_BOLD
        }
        
        iconValuePaint.apply {
            color = fgColor
            typeface = sysTypeface
        }
        iconUnitPaint.apply {
            color = fgColor
            typeface = sysTypeface
        }

        val sz = iconSizePx.toFloat()
        val gap = sz * 0.03f  // tiny gap between value and unit

        // --- Step 1: Size the value text (target ~55% of icon height) ---
        val targetValueH = sz * 0.55f
        iconValuePaint.textSize = targetValueH
        iconValuePaint.getTextBounds(value, 0, value.length, valueBounds)
        // Fit width first
        var vw = iconValuePaint.measureText(value)
        if (vw > sz) {
            iconValuePaint.textSize *= sz / vw
            iconValuePaint.getTextBounds(value, 0, value.length, valueBounds)
        }
        // Then fit height
        var vh = valueBounds.height().toFloat()
        if (vh > 0f && vh != targetValueH) {
            val scale = targetValueH / vh
            val widthAfter = iconValuePaint.measureText(value) * scale
            iconValuePaint.textSize *= if (widthAfter > sz) sz / iconValuePaint.measureText(value) else scale
            iconValuePaint.getTextBounds(value, 0, value.length, valueBounds)
        }
        vw = iconValuePaint.measureText(value)

        // --- Step 2: Size the unit text (target ~42% of icon height) ---
        val targetUnitH = sz * 0.42f
        iconUnitPaint.textSize = targetUnitH
        iconUnitPaint.getTextBounds(unit, 0, unit.length, unitBounds)
        var uw = iconUnitPaint.measureText(unit)
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
        uw = iconUnitPaint.measureText(unit)

        // --- Step 3: Lay out both as a single centered block ---
        vh = valueBounds.height().toFloat()
        uh = unitBounds.height().toFloat()
        val totalBlockH = vh + gap + uh
        // Top of the block, centered vertically in the icon
        val blockTop = (sz - totalBlockH) / 2f

        // Value baseline: blockTop is where top of value glyph sits
        // baseline = blockTop - valueBounds.top  (ascent is negative in Rect)
        val valueBaseline = blockTop - valueBounds.top.toFloat()
        // Unit baseline: starts after value + gap
        val unitBaseline = blockTop + vh + gap - unitBounds.top.toFloat()

        // Horizontally center both texts in the middle of the icon
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

    override fun onDestroy() {
        super.onDestroy()
        samplingJob?.cancel()
        usageRefreshJob?.cancel()
        serviceScope.cancel()
        releaseCpuWakeLock()
        lastBitmap?.recycle()
        lastBitmap = null
        Log.d(TAG, "Speed service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed; service will continue as foreground due to stopWithTask=false")
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            val lock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SpeedCpuLock")
            lock?.setReferenceCounted(false)
            lock?.acquire() // no timeout — lifecycle managed in onDestroy
            wakeLock = lock
            Log.i(TAG, "CPU wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "CPU wake lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock cleanly: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
