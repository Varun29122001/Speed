package com.Speed.speedtest.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.Speed.speedtest.LauncherActivity
import com.Speed.speedtest.R
import com.Speed.speedtest.util.SpeedTester
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
        private const val CHANNEL_ID = "speed_test_channel_v4_top"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val INITIAL_SPEED_TEXT = "↓ 0 KB/s"
        private const val ICON_BASE_DP = 28f
        private const val ICON_MIN_PX = 56
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
    private var lastShortcutText: String = ""
    private var shortcutRegistered: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var lastIcon: IconCompat
    private val iconValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // color set dynamically per-theme when drawing
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val iconUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // smaller unit text paint
        textAlign = Paint.Align.CENTER
        textSize = 14f
    }

    // returns foreground color matching system light/dark theme
    private fun getForegroundColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpeedTestService.onCreate() called - initializing service")
        acquireCpuWakeLock()
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
            NotificationManager.IMPORTANCE_MAX
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
            .setShowWhen(false)
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

        applyCollapsedViewOnly(INITIAL_SPEED_TEXT)

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
            updateNotification("↓ $speedText")
        } catch (e: Exception) {
            Log.e(TAG, "Error during speed sampling: ${e.message}", e)
            updateNotification("↓ 0 KB/s")
        }
    }

    private fun updateNotification(text: String) {
        try {
            if (text == lastNotificationText) return

            applyCollapsedViewOnly(text)
            val fullSpeedText = text.removePrefix("↓ ").trim()
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
        view.setTextViewText(R.id.text_speed, INITIAL_SPEED_TEXT)
        // set initial text color to match system theme
        view.setTextColor(R.id.text_speed, getForegroundColor())
        return view
    }

    private fun applyCollapsedViewOnly(text: String) {
        compactView.setTextViewText(R.id.text_speed, text)
        // update system template fallback text and compact view color
        notificationBuilder.setContentText(text)
        compactView.setTextColor(R.id.text_speed, getForegroundColor())
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
        val iconSizePx = (ICON_BASE_DP * resources.displayMetrics.density).roundToInt().coerceAtLeast(ICON_MIN_PX)
        val bitmap = createBitmap(iconSizePx, iconSizePx)
        val canvas = Canvas(bitmap)
        val fontScale = resources.configuration.fontScale.coerceIn(0.85f, 1.35f)

        // adapt paint colors to system theme for visibility
        val fgColor = getForegroundColor()
        iconValuePaint.color = fgColor
        iconUnitPaint.color = fgColor

        // Top numeric value - larger
        iconValuePaint.textSize = iconSizePx * 0.62f * fontScale
        while (iconValuePaint.measureText(value) > iconSizePx * 0.92f && iconValuePaint.textSize > iconSizePx * 0.28f) {
            iconValuePaint.textSize *= 0.90f
        }

        // Bottom unit label - smaller
        iconUnitPaint.textSize = iconSizePx * 0.26f * fontScale
        while (iconUnitPaint.measureText(unit) > iconSizePx * 0.92f && iconUnitPaint.textSize > iconSizePx * 0.14f) {
            iconUnitPaint.textSize *= 0.90f
        }

        val center = iconSizePx / 2f
        val topBaseline = iconSizePx * 0.36f - (iconValuePaint.descent() + iconValuePaint.ascent()) / 2f
        val bottomBaseline = iconSizePx * 0.76f - (iconUnitPaint.descent() + iconUnitPaint.ascent()) / 2f

        canvas.drawText(value, center, topBaseline, iconValuePaint)
        canvas.drawText(unit, center, bottomBaseline, iconUnitPaint)
        return IconCompat.createWithBitmap(bitmap)
    }

    // ...existing code...

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
        requestSelfRestart()
        Log.d(TAG, "Speed service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        requestSelfRestart()
    }

    private fun requestSelfRestart() {
        try {
            val restartIntent = Intent(applicationContext, SpeedTestService::class.java)
            ContextCompat.startForegroundService(applicationContext, restartIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request self restart: ${e.message}", e)
        }
    }

    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            val lock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SpeedCpuLock")
            lock?.setReferenceCounted(false)
            lock?.acquire()
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
