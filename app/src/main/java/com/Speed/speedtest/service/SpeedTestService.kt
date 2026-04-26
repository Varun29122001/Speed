package com.Speed.speedtest.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.Speed.speedtest.util.SpeedTester
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SpeedTestService : Service() {
    companion object {
        private const val TAG = "SpeedTestService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "speed_test_channel"
        private const val SPEED_TEST_INTERVAL_MS = 1000L
    }

    private val isRunning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val speedTestRunnable = object : Runnable {
        override fun run() {
            worker.execute { performSpeedSample() }
            mainHandler.postDelayed(this, SPEED_TEST_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SpeedTestService created")
        createNotificationChannel()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SpeedTestService started")
        if (isRunning.compareAndSet(false, true)) {
            startSpeedTesting()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "Speed Test Service",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Realtime Internet Speed Runner")
            .setContentText("Initializing realtime speed monitor...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }
    }

    private fun startSpeedTesting() {
        Log.d(TAG, "Starting continuous speed testing")
        SpeedTester.resetSampler()
        worker.execute { performSpeedSample() }
        mainHandler.postDelayed(speedTestRunnable, SPEED_TEST_INTERVAL_MS)
    }

    private fun performSpeedSample() {
        try {
            val result = SpeedTester.sampleRealtimeSpeed()

            if (result != null) {
                val message = "Download ${result.downloadText}"
                Log.i(TAG, message)
                updateNotification(message)
            } else {
                val message = "Download 0.00 KB/s"
                Log.w(TAG, message)
                updateNotification(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during speed sampling: ${e.message}", e)
            updateNotification("Realtime speed error: ${e.message}")
        }
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Test Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val manager = getSystemService(android.app.NotificationManager::class.java)
        try {
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        mainHandler.removeCallbacks(speedTestRunnable)
        worker.shutdownNow()
        Log.d(TAG, "SpeedTestService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
