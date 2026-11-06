package org.grise.homeguard

import android.hardware.SensorManager
import org.grise.homeguard.util.gForceChangeOf
import org.grise.homeguard.util.isMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold behaviour for the accelerometer callback.
 *
 * The expected values depend on [SensorManager.GRAVITY_EARTH] being a real constant rather than a
 * zeroed stub, which is why this runs with `unitTests.isReturnDefaultValues = true` avoided: the
 * assertion below pins the constant so the rest of the file cannot quietly pass against 0f.
 */
class MotionThresholdTest {

    private val g = SensorManager.GRAVITY_EARTH
    private val threshold = 3.0f

    @Test
    fun `GRAVITY_EARTH is a usable constant under unit tests`() {
        assertEquals(9.80665f, g, 0.00001f)
    }

    @Test
    fun `a stationary device reads zero deviation`() {
        assertEquals(0f, gForceChangeOf(0f, 0f, g), 0.0001f)
    }

    @Test
    fun `gravity along any single axis is not motion`() {
        assertEquals(0f, gForceChangeOf(g, 0f, 0f), 0.0001f)
        assertEquals(0f, gForceChangeOf(0f, g, 0f), 0.0001f)
        assertEquals(0f, gForceChangeOf(0f, 0f, -g), 0.0001f)
    }

    @Test
    fun `gravity split across axes still reads zero deviation`() {
        val diag = g / sqrt3
        assertEquals(0f, gForceChangeOf(diag, diag, diag), 0.0001f)
    }

    @Test
    fun `a shove above the threshold is motion`() {
        val deviation = 5.0f
        val change = gForceChangeOf(0f, 0f, g + deviation)
        assertEquals(deviation, change, 0.0001f)
        assertTrue(isMotion(change, threshold))
    }

    @Test
    fun `a deviation below the threshold is ignored`() {
        val deviation = 1.0f
        val change = gForceChangeOf(0f, 0f, g + deviation)
        assertFalse(isMotion(change, threshold))
    }

    @Test
    fun `a deviation exactly at the threshold is ignored`() {
        // isMotion is strictly greater-than, matching the previous callback behaviour.
        assertFalse(isMotion(threshold, threshold))
        assertTrue(isMotion(threshold + 0.0001f, threshold))
    }

    @Test
    fun `free fall reads as motion`() {
        // The magnitude collapses to zero, so the deviation is a full g.
        assertTrue(isMotion(gForceChangeOf(0f, 0f, 0f), threshold))
    }

    private val sqrt3 = 1.7320508f
}
