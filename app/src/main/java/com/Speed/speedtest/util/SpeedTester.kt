package com.Speed.speedtest.util

import android.net.TrafficStats
import android.os.SystemClock
import kotlin.math.max

class SpeedTester {
    companion object {
        data class SpeedSnapshot(
            val downloadKBps: Double,
            val uploadKBps: Double,
            val totalKBps: Double,
            val downloadText: String,
            val uploadText: String,
            val totalText: String
        )

        private var lastRxBytes: Long = -1L
        private var lastTxBytes: Long = -1L
        private var lastSampleTimeMs: Long = -1L

        fun resetSampler() {
            lastRxBytes = -1L
            lastTxBytes = -1L
            lastSampleTimeMs = -1L
        }

        /**
         * Samples realtime network speed from device-level traffic counters.
         * Returns null when counters are unsupported.
         */
        fun sampleRealtimeSpeed(): SpeedSnapshot? {
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()
            if (currentRx == TrafficStats.UNSUPPORTED.toLong() || currentTx == TrafficStats.UNSUPPORTED.toLong()) {
                return null
            }

            val now = SystemClock.elapsedRealtime()
            if (lastSampleTimeMs <= 0L) {
                lastRxBytes = currentRx
                lastTxBytes = currentTx
                lastSampleTimeMs = now
                return SpeedSnapshot(
                    downloadKBps = 0.0,
                    uploadKBps = 0.0,
                    totalKBps = 0.0,
                    downloadText = formatAdaptiveSpeed(0.0),
                    uploadText = formatAdaptiveSpeed(0.0),
                    totalText = formatAdaptiveSpeed(0.0)
                )
            }

            val elapsedMs = max(1L, now - lastSampleTimeMs)
            val rxDelta = max(0L, currentRx - lastRxBytes)
            val txDelta = max(0L, currentTx - lastTxBytes)

            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastSampleTimeMs = now

            val elapsedSeconds = elapsedMs / 1000.0
            val downloadKBps = (rxDelta / 1024.0) / elapsedSeconds
            val uploadKBps = (txDelta / 1024.0) / elapsedSeconds
            val totalKBps = downloadKBps + uploadKBps

            return SpeedSnapshot(
                downloadKBps = downloadKBps,
                uploadKBps = uploadKBps,
                totalKBps = totalKBps,
                downloadText = formatAdaptiveSpeed(downloadKBps),
                uploadText = formatAdaptiveSpeed(uploadKBps),
                totalText = formatAdaptiveSpeed(totalKBps)
            )
        }

        fun formatAdaptiveSpeed(speedKBps: Double): String {
            return if (speedKBps >= 1024.0) {
                "${"%.3f".format(speedKBps / 1024.0)} MB/s"
            } else {
                "${"%.2f".format(speedKBps)} KB/s"
            }
        }

    }
}
