package com.Speed.speedtest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.Speed.speedtest.service.SpeedTestService

/**
 * Minimal bootstrap activity: starts the foreground speed service and exits immediately.
 */
class LauncherActivity : android.app.Activity() {
    companion object {
        private const val TAG = "LauncherActivity"
    }

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bootstrapAndFinish()
    }

    override fun onResume() {
        super.onResume()
        // Theme.NoDisplay activities must finish before onResume completes.
        if (!isFinishing) {
            finish()
        }
    }

    private fun bootstrapAndFinish() {
        if (launched) {
            if (!isFinishing) finish()
            return
        }
        launched = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted; starting service without prompting from NoDisplay activity")
            }
        }

        requestIgnoreBatteryOptimizationsBestEffort()

        try {
            val serviceIntent = Intent(this, SpeedTestService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.i(TAG, "SpeedTestService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start service: ${e.message}", e)
        }
        finish()
    }

    private fun requestIgnoreBatteryOptimizationsBestEffort() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return

            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Requested battery optimization exemption")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open battery optimization exemption screen: ${e.message}")
        }
    }
}

