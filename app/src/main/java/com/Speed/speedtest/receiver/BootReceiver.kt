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

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action == null) return

        val action = intent.action
        val isInstallBroadcastForThisApp =
            action == Intent.ACTION_PACKAGE_ADDED && intent.data?.schemeSpecificPart == context.packageName

        val shouldStart =
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                isInstallBroadcastForThisApp

        if (!shouldStart) return

        try {
            Log.d(TAG, "Startup trigger received: $action")
            val serviceIntent = Intent(context, SpeedTestService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeedTestService: ${e.message}", e)
        }
    }
}
