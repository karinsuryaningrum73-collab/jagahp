package com.example.camera

import androidx.camera.core.ImageProxy
import kotlin.math.abs

data class MotionDetectionResult(
    val motionActive: Boolean,
    val triggeredNewEvent: Boolean,
    val intensityPercent: Double
)

/**
 * Lightweight luminance-grid frame-diff motion detector.
 * Samples every 12th pixel of the Y plane to minimize CPU and thermal load on old phones.
 */
class MotionDetector(
    private val pixelDeltaThreshold: Int = 22,
    private val changedRatioThreshold: Double = 0.045,
    private val cooldownMs: Long = 10_000L
) {
    private var previousSample: IntArray? = null
    private var lastMotionTimestampMs: Long = 0L
    private var lastAnalysisMs: Long = 0L
    private var wasMotionActive: Boolean = false

    @Synchronized
    fun reset() {
        previousSample = null
        lastMotionTimestampMs = 0L
        lastAnalysisMs = 0L
        wasMotionActive = false
    }

    @Synchronized
    fun analyze(image: ImageProxy, nowMs: Long = System.currentTimeMillis()): MotionDetectionResult {
        // Throttle analysis to ~4 Hz max for extreme battery savings
        if (nowMs - lastAnalysisMs < 240L) {
            val active = (nowMs - lastMotionTimestampMs) < cooldownMs && lastMotionTimestampMs > 0L
            return MotionDetectionResult(
                motionActive = active,
                triggeredNewEvent = false,
                intensityPercent = 0.0
            )
        }
        lastAnalysisMs = nowMs

        val yPlane = image.planes.firstOrNull() ?: return MotionDetectionResult(false, false, 0.0)
        val buffer = yPlane.buffer
        val width = image.width
        val height = image.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val step = 12
        val cols = (width / step).coerceAtLeast(1)
        val rows = (height / step).coerceAtLeast(1)
        val sampleCount = cols * rows
        val currentSample = IntArray(sampleCount)

        var idx = 0
        for (r in 0 until rows) {
            val y = r * step
            val rowOffset = y * rowStride
            for (c in 0 until cols) {
                val x = c * step
                val pos = rowOffset + x * pixelStride
                currentSample[idx++] = if (pos in 0 until buffer.capacity()) {
                    buffer.get(pos).toInt() and 0xFF
                } else {
                    0
                }
            }
        }

        val prev = previousSample
        previousSample = currentSample
        if (prev == null || prev.size != currentSample.size) {
            return MotionDetectionResult(
                motionActive = false,
                triggeredNewEvent = false,
                intensityPercent = 0.0
            )
        }

        var changedCount = 0
        for (i in currentSample.indices) {
            if (abs(currentSample[i] - prev[i]) > pixelDeltaThreshold) {
                changedCount++
            }
        }

        val ratio = changedCount.toDouble() / sampleCount.toDouble()
        val intensity = (ratio * 100.0).coerceIn(0.0, 100.0)
        val spike = ratio >= changedRatioThreshold

        if (spike) {
            lastMotionTimestampMs = nowMs
        }

        val active = lastMotionTimestampMs > 0L && (nowMs - lastMotionTimestampMs) <= cooldownMs
        val newEvent = active && !wasMotionActive
        wasMotionActive = active

        return MotionDetectionResult(
            motionActive = active,
            triggeredNewEvent = newEvent,
            intensityPercent = intensity
        )
    }
}
