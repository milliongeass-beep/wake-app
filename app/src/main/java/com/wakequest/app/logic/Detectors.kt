package com.wakequest.app.logic

import kotlin.math.sqrt

private const val G = 9.80665f

/** Counts a shake each time total acceleration exceeds [thresholdG] (with a short cooldown). */
class ShakeDetector(private val thresholdG: Float, private val minIntervalMs: Long = 250) {
    private var last = Long.MIN_VALUE / 2

    fun onSample(x: Float, y: Float, z: Float, tMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / G
        if (g > thresholdG && tMs - last >= minIntervalMs) {
            last = tMs
            return true
        }
        return false
    }
}

/**
 * Simple pedometer: tracks gravity with a low-pass filter and counts a step when the
 * acceleration rises above it by [threshold] m/s² (with hysteresis and a cooldown).
 */
class StepDetector(private val threshold: Float, private val minIntervalMs: Long = 280) {
    private var gravity = G
    private var initialized = false
    private var above = false
    private var last = Long.MIN_VALUE / 2

    fun onSample(x: Float, y: Float, z: Float, tMs: Long): Boolean {
        val m = sqrt(x * x + y * y + z * z)
        if (!initialized) {
            gravity = m
            initialized = true
            return false
        }
        gravity = gravity * 0.92f + m * 0.08f
        val d = m - gravity
        if (!above && d > threshold) {
            above = true
            if (tMs - last >= minIntervalMs) {
                last = tMs
                return true
            }
        } else if (above && d < threshold * 0.3f) {
            above = false
        }
        return false
    }
}

object StatsMath {
    fun stdDev(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }
}
