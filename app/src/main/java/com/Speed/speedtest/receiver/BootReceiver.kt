package com.Speed.speedtest.receiver

/**
 * BootReceiver was removed from active use. Keeping a minimal no-op stub to avoid
 * accidental broadcast registration or build issues. If you want to delete this file
 * completely from the repository, you can remove it manually.
 */
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Intentionally left blank — autostart behavior disabled.
    }
}
