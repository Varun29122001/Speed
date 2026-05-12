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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.Speed.speedtest.service.SpeedTestService

/**
 * Minimal bootstrap activity: starts the foreground speed service and exits immediately.
 */
class LauncherActivity : android.app.Activity() {
    companion object {
        private const val TAG = "LauncherActivity"
        private const val NOTIFICATION_PERMISSION_CODE = 42
    }

    private var launched = false
    private var shouldFinishAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bootstrapAndFinish()
    }

    override fun onResume() {
        super.onResume()
        // Only auto-finish if we are NOT waiting for the permission dialog result.
        if (!shouldFinishAfterPermission && !isFinishing) {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            shouldFinishAfterPermission = false
            startServiceAndFinish()
        }
    }

    private fun bootstrapAndFinish() {
        if (launched) {
            if (!isFinishing) finish()
            return
        }
        launched = true

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
                // Request the permission (this will trigger onRequestPermissionsResult)
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
                shouldFinishAfterPermission = true
                return
            }
        }

        startServiceAndFinish()
    }

    private fun startServiceAndFinish() {
        requestIgnoreBatteryOptimizationsBestEffort()

        try {
            val serviceIntent = Intent(this, SpeedTestService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.i(TAG, "SpeedTestService start requested successfully")
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

