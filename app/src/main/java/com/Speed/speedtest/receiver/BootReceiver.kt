package com.Speed.speedtest.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        try {
            val serviceIntent = Intent(context, SpeedTestService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "Requested SpeedTestService start for action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start SpeedTestService on boot/update: ${e.message}", e)
        }
    }
}
