package org.grise.homeguard.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A coroutine mutex that a coroutine may already be holding without deadlocking on re-entry.
 *
 * [org.grise.homeguard.service.MonitoringService] serialises captures so two of them never drive
 * the camera at once. A motion burst takes the lock for the *whole* burst and then calls
 * [org.grise.homeguard.service.MonitoringService.performTimedCapture] repeatedly, while a remote
 * `!picture` command calls that same function without holding it. A plain [Mutex] therefore cannot
 * be used at both levels: either the nested call self-deadlocks, or the outer burst is not actually
 * protected.
 *
 * Re-entry is counted per coroutine, identified by its job, which is exactly the granularity
 * needed here: each capture sequence runs in its own coroutine, and different triggers are always
 * different coroutines, so genuine contention is still respected.
 *
 * Only use this where the outer holder does not suspend between acquiring and the nested call in a
 * way that could let the "am I still the holder?" check go stale.
 */
internal class ReentrantMutex {
    private val mutex = Mutex()

    private var owner: Any? = null
    private var holdCount = 0

    suspend fun <T> withLock(block: suspend () -> T): T {
        // The issuing coroutine's job identifies it. Coroutine start is a suspension point, so the
        // job is already installed by the time any suspend function can run.
        val token = currentCoroutineContext()[Job]
        // The recorded owner is only trusted while its coroutine is still active. If a holder was
        // cancelled mid-section, its `finally` may have been skipped, and reusing a stale owner as
        // "reentrant" would let a later burst skip mutual exclusion entirely.
        val ownerIsLive = (owner != null) &&
                ((owner as? Job)?.isActive ?: true)
        if (owner != null && !ownerIsLive) {
            owner = null
            holdCount = 0
        }
        val reentrant = ownerIsLive && (owner === token)

        if (reentrant) {
            holdCount++
            val result: T
            try {
                result = block()
            } finally {
                if (--holdCount == 0) owner = null
            }
            return result
        }

        return mutex.withLock {
            owner = token
            holdCount = 1
            try {
                block()
            } finally {
                owner = null
                holdCount = 0
            }
        }
    }
}
