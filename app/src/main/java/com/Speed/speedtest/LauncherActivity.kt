/*
 * Copyright (C) 2026 Speed App. All rights reserved.
 *
 * This source code is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, via any medium, is
 * strictly prohibited without express written permission from the copyright holder.
 *
 * Licensed under a proprietary license. See LICENSE file in the project root.
 */

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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.Speed.speedtest.service.SpeedTestService
import com.Speed.speedtest.util.DataUsageTracker

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
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startServiceAndFinish()
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS denied; not starting service")
                Toast.makeText(
                    this,
                    R.string.notification_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
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
        requestUsageAccessBestEffort()

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

    private fun requestUsageAccessBestEffort() {
        try {
            if (DataUsageTracker.hasUsageAccess(this)) return

            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Requested usage access permission")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open usage access settings: ${e.message}")
        }
    }
}

