package org.grise.homeguard.util

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * G-force deviation from a stationary device, or null if [x]/[y]/[z] cannot form a vector.
 *
 * Extracted from the accelerometer callback so the "did the device actually move?" decision can be
 * unit-tested against synthetic samples instead of requiring someone to shake a phone.
 */
fun gForceChangeOf(x: Float, y: Float, z: Float): Float {
    val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    return abs(magnitude - SensorManager.GRAVITY_EARTH)
}

/** True when a sample deviating by [gForceChange] exceeds [threshold]. */
fun isMotion(gForceChange: Float, threshold: Float): Boolean = gForceChange > threshold
