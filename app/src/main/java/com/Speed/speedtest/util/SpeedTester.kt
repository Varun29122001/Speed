package com.Speed.speedtest.util

import android.net.TrafficStats
import android.os.SystemClock
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object SpeedTester {
    data class SpeedSnapshot(
        val downloadBytesPerSecond: Double,
        val uploadBytesPerSecond: Double,
        val downloadDisplayText: String,
        val uploadDisplayText: String,
        /** Legacy field — same as downloadDisplayText for backward compat */
        val displayText: String
    )

    // Download (Rx) state
    private var lastRxBytes: Long = -1L
    private val rxSmoothingWindow = LongArray(3)
    private var rxSmoothingIndex = 0
    private var rxSmoothingCount = 0
    private var rxSmoothingSum = 0L

    // Upload (Tx) state
    private var lastTxBytes: Long = -1L
    private val txSmoothingWindow = LongArray(3)
    private var txSmoothingIndex = 0
    private var txSmoothingCount = 0
    private var txSmoothingSum = 0L

    // Shared timing
    private var lastSampleTimeMs: Long = -1L

    @Synchronized
    fun resetSampler() {
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastSampleTimeMs = -1L
        rxSmoothingWindow.fill(0L)
        rxSmoothingIndex = 0
        rxSmoothingCount = 0
        rxSmoothingSum = 0L
        txSmoothingWindow.fill(0L)
        txSmoothingIndex = 0
        txSmoothingCount = 0
        txSmoothingSum = 0L
    }

    /**
     * Samples realtime network speed from device-level traffic counters.
     * Uses TrafficStats.getTotalRxBytes() and getTotalTxBytes() which read
     * the kernel's /proc/net/dev counters.
     * Returns null when counters are unsupported.
     */
    @Synchronized
    fun sampleRealtimeSpeed(): SpeedSnapshot? {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        if (currentRx == TrafficStats.UNSUPPORTED.toLong() ||
            currentTx == TrafficStats.UNSUPPORTED.toLong()
        ) {
            return null
        }

        val now = SystemClock.elapsedRealtime()
        if (lastSampleTimeMs <= 0L) {
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastSampleTimeMs = now
            return SpeedSnapshot(
                downloadBytesPerSecond = 0.0,
                uploadBytesPerSecond = 0.0,
                downloadDisplayText = formatAdaptiveSpeed(0.0),
                uploadDisplayText = formatAdaptiveSpeed(0.0),
                displayText = formatAdaptiveSpeed(0.0)
            )
        }

        val elapsedMs = max(1L, now - lastSampleTimeMs)
        val rxDelta = max(0L, currentRx - lastRxBytes)
        val txDelta = max(0L, currentTx - lastTxBytes)

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastSampleTimeMs = now

        // Download speed
        val rxBytesPerSecond = (rxDelta * 1000.0) / elapsedMs
        val smoothRxBps = applyRxMovingAverage(rxBytesPerSecond.roundToLong()).toDouble()

        // Upload speed
        val txBytesPerSecond = (txDelta * 1000.0) / elapsedMs
        val smoothTxBps = applyTxMovingAverage(txBytesPerSecond.roundToLong()).toDouble()

        val dlText = formatAdaptiveSpeed(smoothRxBps)
        val ulText = formatAdaptiveSpeed(smoothTxBps)

        return SpeedSnapshot(
            downloadBytesPerSecond = smoothRxBps,
            uploadBytesPerSecond = smoothTxBps,
            downloadDisplayText = dlText,
            uploadDisplayText = ulText,
            displayText = dlText
        )
    }

    private fun applyRxMovingAverage(currentSampleBytesPerSecond: Long): Long {
        if (rxSmoothingCount < rxSmoothingWindow.size) {
            rxSmoothingWindow[rxSmoothingIndex] = currentSampleBytesPerSecond
            rxSmoothingSum += currentSampleBytesPerSecond
            rxSmoothingCount++
        } else {
            val previous = rxSmoothingWindow[rxSmoothingIndex]
            rxSmoothingWindow[rxSmoothingIndex] = currentSampleBytesPerSecond
            rxSmoothingSum += currentSampleBytesPerSecond - previous
        }
        rxSmoothingIndex = (rxSmoothingIndex + 1) % rxSmoothingWindow.size
        return if (rxSmoothingCount == 0) 0L else rxSmoothingSum / rxSmoothingCount
    }

    private fun applyTxMovingAverage(currentSampleBytesPerSecond: Long): Long {
        if (txSmoothingCount < txSmoothingWindow.size) {
            txSmoothingWindow[txSmoothingIndex] = currentSampleBytesPerSecond
            txSmoothingSum += currentSampleBytesPerSecond
            txSmoothingCount++
        } else {
            val previous = txSmoothingWindow[txSmoothingIndex]
            txSmoothingWindow[txSmoothingIndex] = currentSampleBytesPerSecond
            txSmoothingSum += currentSampleBytesPerSecond - previous
        }
        txSmoothingIndex = (txSmoothingIndex + 1) % txSmoothingWindow.size
        return if (txSmoothingCount == 0) 0L else txSmoothingSum / txSmoothingCount
    }

    fun formatAdaptiveSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0) return "0 KB/s"

        // MB/s tier
        val mb = bytesPerSecond / (1024.0 * 1024.0)
        if (mb >= 1.0) return String.format(Locale.US, "%.2f MB/s", mb)

        // KB/s tier — bump up to MB if rounding would produce the misleading "1024 KB/s".
        val kb = bytesPerSecond / 1024.0
        if (kb >= 1.0) {
            val rounded = kb.roundToInt()
            return if (rounded >= 1024) String.format(Locale.US, "%.2f MB/s", mb)
            else String.format(Locale.US, "%d KB/s", rounded)
        }

        // B/s tier — same: bump up to "1 KB/s" instead of "1024 B/s".
        val rounded = bytesPerSecond.roundToInt()
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
