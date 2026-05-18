package com.Speed.speedtest.util

import android.net.TrafficStats
import android.os.SystemClock
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

object SpeedTester {
    data class SpeedSnapshot(
        val downloadBytesPerSecond: Double,
        val displayText: String
    )

    private var lastRxBytes: Long = -1L
    private var lastSampleTimeMs: Long = -1L
    private val smoothingWindow = LongArray(3)
    private var smoothingIndex = 0
    private var smoothingCount = 0
    private var smoothingSum = 0L

    fun resetSampler() {
        lastRxBytes = -1L
        lastSampleTimeMs = -1L
        smoothingWindow.fill(0L)
        smoothingIndex = 0
        smoothingCount = 0
        smoothingSum = 0L
    }

    /**
     * Samples realtime network speed from device-level traffic counters.
     * Uses TrafficStats.getTotalRxBytes() which reads the kernel's /proc/net/dev counters.
     * Returns null when counters are unsupported.
     */
    fun sampleRealtimeSpeed(): SpeedSnapshot? {
        val currentRx = TrafficStats.getTotalRxBytes()
        if (currentRx == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }

        val now = SystemClock.elapsedRealtime()
        if (lastSampleTimeMs <= 0L) {
            lastRxBytes = currentRx
            lastSampleTimeMs = now
            return SpeedSnapshot(
                downloadBytesPerSecond = 0.0,
                displayText = formatAdaptiveSpeed(0.0)
            )
        }

        val elapsedMs = max(1L, now - lastSampleTimeMs)
        val rxDelta = max(0L, currentRx - lastRxBytes)

        lastRxBytes = currentRx
        lastSampleTimeMs = now

        val bytesPerSecond = (rxDelta * 1000.0) / elapsedMs
        val smoothBytesPerSecond = applyMovingAverage(bytesPerSecond.toLong()).toDouble()

        return SpeedSnapshot(
            downloadBytesPerSecond = smoothBytesPerSecond,
            displayText = formatAdaptiveSpeed(smoothBytesPerSecond)
        )
    }

    private fun applyMovingAverage(currentSampleBytesPerSecond: Long): Long {
        if (smoothingCount < smoothingWindow.size) {
            smoothingWindow[smoothingIndex] = currentSampleBytesPerSecond
            smoothingSum += currentSampleBytesPerSecond
            smoothingCount++
        } else {
            val previous = smoothingWindow[smoothingIndex]
            smoothingWindow[smoothingIndex] = currentSampleBytesPerSecond
            smoothingSum += currentSampleBytesPerSecond - previous
        }

        smoothingIndex = (smoothingIndex + 1) % smoothingWindow.size
        return if (smoothingCount == 0) 0L else smoothingSum / smoothingCount
    }

    fun formatAdaptiveSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0) return "0 KB/s"

        // MB/s tier
        val mb = bytesPerSecond / (1024.0 * 1024.0)
        if (mb >= 1.0) return String.format(Locale.US, "%.2f MB/s", mb)

        // KB/s tier — bump up to MB if rounding would produce the misleading "1024 KB/s".
        val kb = bytesPerSecond / 1024.0
        if (kb >= 1.0) {
            val rounded = kb.toInt() + if (kb - kb.toInt() >= 0.5) 1 else 0
            return if (rounded >= 1024) String.format(Locale.US, "%.2f MB/s", mb)
            else String.format(Locale.US, "%d KB/s", rounded)
        }

        // B/s tier — same: bump up to "1 KB/s" instead of "1024 B/s".
        val rounded = bytesPerSecond.toInt() +
            if (bytesPerSecond - bytesPerSecond.toInt() >= 0.5) 1 else 0
        return if (rounded >= 1024) "1 KB/s"
        else String.format(Locale.US, "%d B/s", rounded)
    }

    fun formatDataSize(bytes: Long): String {
        if (bytes < 0L) return "0 B"

        // GB tier
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }

        // MB tier — bump up to GB if rounding would produce "1024.0 MB".
        if (bytes >= 1024L * 1024L) {
            val mb = bytes / (1024.0 * 1024.0)
            val rounded = (mb * 10.0).roundToLong()  // tenth-precision rounding
            return if (rounded >= 10240L) String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            else String.format(Locale.US, "%.1f MB", mb)
        }

        // KB tier — bump up to MB if rounding would produce "1024 KB".
        if (bytes >= 1024L) {
            val kb = bytes / 1024.0
            val rounded = kb.roundToLong()
            return if (rounded >= 1024L) String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else String.format(Locale.US, "%d KB", rounded)
        }

        return String.format(Locale.US, "%d B", bytes)
    }
}

