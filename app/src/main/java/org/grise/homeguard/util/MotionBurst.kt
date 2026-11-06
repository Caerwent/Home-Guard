package org.grise.homeguard.util

/**
 * Decide whether a motion burst should capture again.
 *
 * This is deliberately not inlined in the service: it used to be a raw
 * `System.currentTimeMillis() - lastMotionTimestamp < MOTION_COOLDOWN_MS` comparison evaluated
 * against a timestamp observed at a polling boundary. That made a burst missable, and it could only
 * be exercised on a physical device with an accelerometer. As a pure function over a clock it can be
 * regression-tested directly.
 *
 * @param nowMs current wall-clock milliseconds.
 * @param lastMotionMs wall-clock milliseconds of the most recent motion trigger.
 * @param cooldownMs how long after the last trigger the burst keeps capturing.
 * @param elapsedSinceStartMs how long the current burst has been running. A burst that has just been
 *   picked up has not captured anything yet, so it must capture once even if its trigger already
 *   looks stale; without this, a burst whose events stopped just before it was observed captures
 *   nothing at all.
 */
fun shouldCaptureInBurst(
    nowMs: Long,
    lastMotionMs: Long,
    cooldownMs: Long,
    elapsedSinceStartMs: Long = Long.MAX_VALUE,
): Boolean {
    if (elapsedSinceStartMs < cooldownMs) return true
    return (nowMs - lastMotionMs) < cooldownMs
}
