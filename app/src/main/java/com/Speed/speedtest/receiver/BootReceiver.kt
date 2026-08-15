/*
 * Copyright (C) 2026 Speed App. All rights reserved.
 *
 * This source code is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, via any medium, is
 * strictly prohibited without express written permission from the copyright holder.
 *
 * Licensed under a proprietary license. See LICENSE file in the project root.
 */

package com.Speed.speedtest.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.Speed.speedtest.service.SpeedTestService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // Don't start the service if POST_NOTIFICATIONS is denied (Android 13+).
        // Without it, the foreground notification cannot display and startForeground
        // will throw on Android 14+; the user must re-launch the app and grant the
        // permission to opt back in.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS denied; skipping service start for action=$action")
            return
        }

        try {
            val serviceIntent = Intent(context, SpeedTestService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "Requested SpeedTestService start for action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start SpeedTestService on boot/update: ${e.message}", e)
        }
    }
}
