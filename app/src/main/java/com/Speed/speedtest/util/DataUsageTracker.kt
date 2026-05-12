package com.Speed.speedtest.util

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import java.util.Calendar

/**
 * Queries today's Wi-Fi and Mobile data usage from Android's built-in NetworkStatsManager.
 * Requires the user to grant "Usage Access" (PACKAGE_USAGE_STATS) in system settings.
 */
object DataUsageTracker {
    private const val TAG = "DataUsageTracker"

    data class UsageInfo(
        val wifiBytes: Long,
        val mobileBytes: Long,
        val wifiDisplayText: String,
        val mobileDisplayText: String
    )

    /**
     * Returns today's total Wi-Fi and Mobile data usage (download + upload).
     * Returns null if usage access is not granted or stats are unavailable.
     */
    fun getTodayUsage(context: Context): UsageInfo? {
        if (!hasUsageAccess(context)) return null

        return try {
            val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return null

            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val now = System.currentTimeMillis()

            @Suppress("DEPRECATION")
            val wifiBytes = queryDeviceUsage(nsm, ConnectivityManager.TYPE_WIFI, startOfDay, now)
            @Suppress("DEPRECATION")
            val mobileBytes = queryDeviceUsage(nsm, ConnectivityManager.TYPE_MOBILE, startOfDay, now)

            UsageInfo(
                wifiBytes = wifiBytes,
                mobileBytes = mobileBytes,
                wifiDisplayText = SpeedTester.formatDataSize(wifiBytes),
                mobileDisplayText = SpeedTester.formatDataSize(mobileBytes)
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Usage access not granted: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting data usage: ${e.message}", e)
            null
        }
    }

    private fun queryDeviceUsage(
        nsm: NetworkStatsManager,
        networkType: Int,
        startTime: Long,
        endTime: Long
    ): Long {
        return try {
            val bucket = nsm.querySummaryForDevice(networkType, null, startTime, endTime)
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            Log.d(TAG, "Could not query network type $networkType: ${e.message}")
            0L
        }
    }

    /** Checks whether the user has granted Usage Access permission for this app. */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }
}
