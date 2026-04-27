package com.Speed.speedtest.service

import android.Manifest
import android.app.Notification
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.Speed.speedtest.R
import com.Speed.speedtest.util.SpeedTester
import kotlin.math.roundToInt
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SpeedTestService : Service() {
    companion object {
        private const val TAG = "SpeedTestService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "speed_test_channel_v3"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val INITIAL_SPEED_TEXT = "↓ 0 KB/s"
        private const val ICON_BASE_DP = 28f
        private const val ICON_MIN_PX = 56
        private const val ACTION_RESTORE_FROM_DISMISS = "com.Speed.speedtest.action.RESTORE_FROM_DISMISS"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var compactView: RemoteViews
    private var lastIconLabel: String = ""
    private var lastNotificationText: String = ""
    private lateinit var lastIcon: IconCompat
    private val iconValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        startForegroundNotification()
        Log.d(TAG, "Speed service created")
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
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.enableLights(false)
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC

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
        lastIcon = buildStatusIcon("0K")
        compactView = buildCompactRemoteViews()
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(lastIcon)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentText(INITIAL_SPEED_TEXT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(buildRestorePendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        applyCollapsedViewOnly(INITIAL_SPEED_TEXT)

        try {
            startForeground(
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
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
            notificationBuilder.setSmallIcon(getOrCreateStatusIcon(fullSpeedText))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
            lastNotificationText = text
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}", e)
        }
    }

    private fun buildRestorePendingIntent(): PendingIntent {
        val intent = Intent(this, SpeedTestService::class.java).apply {
            action = ACTION_RESTORE_FROM_DISMISS
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, 1002, intent, flags)
    }

    private fun buildCompactRemoteViews(): RemoteViews {
        val view = RemoteViews(packageName, R.layout.notification_speed_compact)
        view.setTextViewText(R.id.text_speed, INITIAL_SPEED_TEXT)
        return view
    }

    private fun applyCollapsedViewOnly(text: String) {
        compactView.setTextViewText(R.id.text_speed, text)
        notificationBuilder.setContentText(text)
        notificationBuilder.setCustomContentView(compactView)
        // Explicitly clear expanded and heads-up custom layouts so only contracted content is defined.
        notificationBuilder.setCustomBigContentView(null)
        notificationBuilder.setCustomHeadsUpContentView(null)
    }

    private fun getOrCreateStatusIcon(speedText: String): IconCompat {
        val (value, unit) = toIconParts(speedText)
        val fontScaleKey = (resources.configuration.fontScale * 100f).roundToInt()
        val iconLabel = toStatusIconLabel(value, unit)
        val iconKey = "$iconLabel|$fontScaleKey"
        if (iconKey == lastIconLabel) {
            return lastIcon
        }

        lastIcon = buildStatusIcon(iconLabel)
        lastIconLabel = iconKey
        return lastIcon
    }

    private fun buildStatusIcon(iconLabel: String): IconCompat {
        val iconSizePx = (ICON_BASE_DP * resources.displayMetrics.density).roundToInt().coerceAtLeast(ICON_MIN_PX)
        val bitmap = createBitmap(iconSizePx, iconSizePx)
        val canvas = Canvas(bitmap)
        val fontScale = resources.configuration.fontScale.coerceIn(0.85f, 1.35f)

        iconValuePaint.textSize = iconSizePx * 0.74f * fontScale
        while (iconValuePaint.measureText(iconLabel) > iconSizePx * 0.94f && iconValuePaint.textSize > iconSizePx * 0.34f) {
            iconValuePaint.textSize *= 0.90f
        }

        val center = iconSizePx / 2f
        val baseline = center - (iconValuePaint.descent() + iconValuePaint.ascent()) / 2f

        canvas.drawText(iconLabel, center, baseline, iconValuePaint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun toStatusIconLabel(value: String, unit: String): String {
        val normalized = value.toIntOrNull()?.coerceIn(0, 999) ?: 0
        return when (unit) {
            "MB/s" -> "${normalized}M"
            else -> "${normalized}K"
        }
    }

    private fun toIconParts(speedText: String): Pair<String, String> {
        val parts = speedText.trim().split(" ")
        if (parts.size < 2) return "0" to "KB/s"

        val value = parts[0].toDoubleOrNull() ?: return "0" to "KB/s"
        val unit = parts[1]

        return when (unit) {
            "MB/s" -> String.format(Locale.US, "%d", value.roundToInt().coerceIn(0, 999)) to "MB/s"
            "KB/s" -> String.format(Locale.US, "%d", value.roundToInt().coerceIn(0, 999)) to "KB/s"
            else -> "0" to "KB/s"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        samplingJob?.cancel()
        serviceScope.cancel()
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

    override fun onBind(intent: Intent?): IBinder? = null
}
