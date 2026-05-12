package com.Speed.speedtest.util

import android.net.TrafficStats
import android.os.SystemClock
import java.util.Locale
import kotlin.math.max

object SpeedTester {
    data class SpeedSnapshot(
        val downloadBytesPerSecond: Double,
        val displayText: String
    )

    data class DataUsage(
        val downloadBytes: Long,
        val uploadBytes: Long,
        val totalBytes: Long,
        val displayText: String
    )

    private var lastRxBytes: Long = -1L
    private var lastSampleTimeMs: Long = -1L
    private val smoothingWindow = LongArray(3)
    private var smoothingIndex = 0
    private var smoothingCount = 0
    private var smoothingSum = 0L

    // Session data-usage tracking
    private var initialRxBytes: Long = -1L
    private var initialTxBytes: Long = -1L

    fun resetSampler() {
        lastRxBytes = -1L
        lastSampleTimeMs = -1L
        smoothingWindow.fill(0L)
        smoothingIndex = 0
        smoothingCount = 0
        smoothingSum = 0L
        initialRxBytes = -1L
        initialTxBytes = -1L
    }

    /**
     * Samples realtime network speed from device-level traffic counters.
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

    /**
     * Returns cumulative data usage (download + upload) since the service started.
     * Returns null when counters are unsupported.
     */
    fun getSessionDataUsage(): DataUsage? {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        if (currentRx == TrafficStats.UNSUPPORTED.toLong()) return null

        if (initialRxBytes < 0L) {
            initialRxBytes = currentRx
            initialTxBytes = currentTx
        }

        val dlBytes = max(0L, currentRx - initialRxBytes)
        val ulBytes = max(0L, currentTx - initialTxBytes)
        val total = dlBytes + ulBytes

        return DataUsage(
            downloadBytes = dlBytes,
            uploadBytes = ulBytes,
            totalBytes = total,
            displayText = "↓${formatDataSize(dlBytes)}  ↑${formatDataSize(ulBytes)}"
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
        return when {
            bytesPerSecond <= 0.0 ->
                "0 KB/s"
            bytesPerSecond >= 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024.0 ->
                String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
            else ->
                String.format(Locale.US, "%.0f B/s", bytesPerSecond)
        }
    }

    fun formatDataSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L ->
                String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else ->
                String.format(Locale.US, "%d B", bytes)
        }
    }
}

