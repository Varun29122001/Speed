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
        private const val INITIAL_SPEED_TEXT = "0 KB/s"
        private const val ICON_BASE_DP = 48f
        private const val ICON_MIN_PX = 96
        private const val ACTION_RESTORE_FROM_DISMISS = "com.Speed.speedtest.action.RESTORE_FROM_DISMISS"
        private const val DYNAMIC_SHORTCUT_ID = "speed_dynamic"
        private const val LEGACY_SHORTCUT_ID = "speed_shortcut"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var compactView: RemoteViews
    private var lastIconLabel: String = ""
    private var lastNotificationText: String = ""
    private var lastWifiText: String = ""
    private var lastMobileText: String = ""
    private var lastShortcutText: String = ""
    private var shortcutRegistered: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var lastIcon: IconCompat
    private var lastBitmap: android.graphics.Bitmap? = null
    private val iconValuePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        hinting = Paint.HINTING_ON
    }
    private val iconUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
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
        acquireCpuWakeLock() // acquired once; released in onDestroy
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        startForegroundNotification()
        ensureDynamicShortcutRegistered()
        Log.d(TAG, "Speed service created and foreground notification started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_FROM_DISMISS) {
            // User/system dismissed notification: immediately restore foreground notification.
            startForegroundNotification()
        }

        if (samplingJob?.isActive != true) {
            SpeedTester.resetSampler()
            startSamplingLoop()
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
        lastIconLabel = "0|KB/s"
        lastNotificationText = INITIAL_SPEED_TEXT
        lastIcon = buildStatusIcon("0", "KB/s")
        compactView = buildCompactRemoteViews()
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

    private fun performSpeedSample() {
        try {
            val result = SpeedTester.sampleRealtimeSpeed()
            val speedText = if (result == null) "0 KB/s" else result.displayText

            // Get today's Wi-Fi / Mobile data usage from system stats
            val usage = DataUsageTracker.getTodayUsage(this)
            updateNotification(
                speedText,
                usage?.wifiDisplayText ?: "",
                usage?.mobileDisplayText ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during speed sampling: ${e.message}", e)
            updateNotification("0 KB/s", "", "")
        }
    }

    private fun updateNotification(text: String, wifiText: String, mobileText: String) {
        try {
            if (text == lastNotificationText && wifiText == lastWifiText && mobileText == lastMobileText) return

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

    private fun buildCompactRemoteViews(): RemoteViews {
        val view = RemoteViews(packageName, R.layout.notification_speed_compact)
        val fgColor = getForegroundColor()
        // Speed text
        view.setTextViewText(R.id.text_speed, INITIAL_SPEED_TEXT)
        view.setTextColor(R.id.text_speed, fgColor)
        // WiFi usage
        view.setTextViewText(R.id.text_wifi_usage, "")
        view.setTextColor(R.id.text_wifi_usage, fgColor)
        // Mobile usage
        view.setTextViewText(R.id.text_mobile_usage, "")
        view.setTextColor(R.id.text_mobile_usage, fgColor)
        // Separator
        view.setTextColor(R.id.text_separator, fgColor)
        // Icon tints
        view.setInt(R.id.icon_status, "setColorFilter", fgColor)
        view.setInt(R.id.icon_wifi, "setColorFilter", fgColor)
        view.setInt(R.id.icon_mobile, "setColorFilter", fgColor)
        return view
    }

    private fun applyCollapsedViewOnly(text: String, wifiText: String, mobileText: String) {
        val fgColor = getForegroundColor()
        // Speed
        compactView.setTextViewText(R.id.text_speed, text)
        compactView.setTextColor(R.id.text_speed, fgColor)
        // WiFi data usage
        compactView.setTextViewText(R.id.text_wifi_usage, wifiText)
        compactView.setTextColor(R.id.text_wifi_usage, fgColor)
        // Mobile data usage
        compactView.setTextViewText(R.id.text_mobile_usage, mobileText)
        compactView.setTextColor(R.id.text_mobile_usage, fgColor)
        // Separator & icon tints for dark mode
        compactView.setTextColor(R.id.text_separator, fgColor)
        compactView.setInt(R.id.icon_status, "setColorFilter", fgColor)
        compactView.setInt(R.id.icon_wifi, "setColorFilter", fgColor)
        compactView.setInt(R.id.icon_mobile, "setColorFilter", fgColor)
        // System template fallback text
        val fallback = buildString {
            append(text)
            if (wifiText.isNotEmpty()) append("  W: $wifiText")
            if (mobileText.isNotEmpty()) append("  M: $mobileText")
        }
        notificationBuilder.setContentText(fallback)
        notificationBuilder.setCustomContentView(compactView)
        // Explicitly clear expanded and heads-up custom layouts so only contracted content is defined.
        notificationBuilder.setCustomBigContentView(null)
        notificationBuilder.setCustomHeadsUpContentView(null)
    }

    private fun getOrCreateStatusIcon(speedText: String): IconCompat {
        val (value, unit) = toIconParts(speedText)
        val fontScaleKey = (resources.configuration.fontScale * 100f).roundToInt()
        val iconKey = "$value|$unit|$fontScaleKey"
        if (iconKey == lastIconLabel) {
            return lastIcon
        }

        lastIcon = buildStatusIcon(value, unit)
        lastIconLabel = iconKey
        return lastIcon
    }

    private fun buildStatusIcon(value: String, unit: String): IconCompat {
        lastBitmap?.recycle()
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

        // --- Step 1: Size the value text (target ~63% of icon height) ---
        val targetValueH = sz * 0.63f
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

        // --- Step 2: Size the unit text (target ~30% of icon height) ---
        val targetUnitH = sz * 0.30f
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
