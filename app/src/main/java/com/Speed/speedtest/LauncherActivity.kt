package com.Speed.speedtest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.Speed.speedtest.service.SpeedTestService

/**
 * Minimal bootstrap activity: starts the foreground speed service and exits immediately.
 */
class LauncherActivity : android.app.Activity() {
    companion object {
        private const val TAG = "LauncherActivity"
        private const val REQ_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                return
            }
        }

        startSpeedServiceAndFinish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFICATIONS) return

        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(this, "Allow notifications to see realtime speed in panel", Toast.LENGTH_LONG).show()
        }
        startSpeedServiceAndFinish()
    }

    private fun startSpeedServiceAndFinish() {
        try {
            val serviceIntent = Intent(this, SpeedTestService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.i(TAG, "SpeedTestService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start service: ${e.message}", e)
        }
        finish()
    }
}

