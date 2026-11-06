package org.grise.homeguard

import org.grise.homeguard.util.shouldCaptureInBurst
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the motion burst that used to be silently dropped.
 *
 * The old loop was `while (now - lastMotionTimestamp < COOLDOWN) { capture(); delay() }`, evaluated
 * against a timestamp observed at a 1s polling boundary. A burst ending just before the burst was
 * picked up could satisfy neither the "still cooling down" condition nor produce a photo, so the
 * `while` body never ran and the motion was lost with nothing in the log.
 */
class MotionBurstTest {

    private val cooldown = 15_000L

    @Test
    fun `burst that just started captures even when its trigger already looks stale`() {
        // Motion 16s ago, burst picked up now: the old condition was false on entry, so the whole
        // burst captured nothing. At least one photo is the required behaviour.
        assertTrue(
            shouldCaptureInBurst(
                nowMs = 100_000,
                lastMotionMs = 84_000,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 0,
            )
        )
    }

    @Test
    fun `burst triggered one polling tick ago still captures`() {
        // The realistic drift case: the trigger was observed up to a tick late.
        assertTrue(
            shouldCaptureInBurst(
                nowMs = 101_000,
                lastMotionMs = 100_000,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 0,
            )
        )
    }

    @Test
    fun `burst keeps capturing while the device is still moving`() {
        assertTrue(
            shouldCaptureInBurst(
                nowMs = 200_000,
                lastMotionMs = 195_000,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 30_000,
            )
        )
    }

    @Test
    fun `burst stops once the device has been still for the whole cooldown`() {
        assertFalse(
            shouldCaptureInBurst(
                nowMs = 200_000,
                lastMotionMs = 180_000,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 30_000,
            )
        )
    }

    @Test
    fun `settled burst stops exactly at the cooldown boundary`() {
        assertFalse(
            shouldCaptureInBurst(
                nowMs = 200_000,
                lastMotionMs = 185_000,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 30_000,
            )
        )
        assertTrue(
            shouldCaptureInBurst(
                nowMs = 200_000,
                lastMotionMs = 185_001,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 30_000,
            )
        )
    }

    @Test
    fun `a settled burst cannot run forever on a stale trigger`() {
        // Guards the grace period: elapsedSinceStartMs must not keep a dead burst alive.
        assertFalse(
            shouldCaptureInBurst(
                nowMs = 600_000,
                lastMotionMs = 84_000,
                cooldownMs = cooldown,
                elapsedSinceStartMs = 30_000,
            )
        )
    }
}
