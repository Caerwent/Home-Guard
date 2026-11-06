package org.grise.homeguard

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.grise.homeguard.util.ReentrantMutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plain mutex at both capture levels would either self-deadlock (motion burst holds the lock and
 * then calls the capture helper, which takes it again) or leave the burst unprotected. These tests
 * pin both halves of that contract.
 */
class ReentrantMutexTest {

    @Test
    fun `nesting on the same coroutine does not deadlock`() = runTest {
        val lock = ReentrantMutex()
        var inner = 0

        lock.withLock {
            lock.withLock {
                lock.withLock { inner++ }
            }
        }

        assertEquals(1, inner)
    }

    @Test
    fun `nested sections on one coroutine return their values`() = runTest {
        val lock = ReentrantMutex()

        val result = lock.withLock {
            lock.withLock { "inner" }
        }

        assertEquals("inner", result)
    }

    @Test
    fun `a second coroutine waits for the first to finish`() = runTest {
        val lock = ReentrantMutex()
        val order = mutableListOf<String>()

        coroutineScope {
            val first = async {
                lock.withLock {
                    order.add("first:start")
                    delay(50)
                    order.add("first:end")
                }
            }
            // Yield so `first` genuinely acquires the lock before the second contends for it.
            delay(10)

            val second = async {
                lock.withLock { order.add("second") }
            }

            first.await()
            second.await()
        }

        assertEquals(listOf("first:start", "first:end", "second"), order)
    }

    @Test
    fun `releasing an inner section does not release the outer one`() = runTest {
        val lock = ReentrantMutex()
        var otherEntered = true

        coroutineScope {
            lock.withLock {
                lock.withLock { }
                // The burst must still own the lock here, otherwise a `!picture` command could cut
                // in between two captures of the same motion sequence.
                val other = async {
                    lock.withLock { otherEntered = true }
                }
                delay(30)
                otherEntered = other.isCompleted
                other.cancel()
            }
        }

        assertTrue("another coroutine entered while the burst still held the lock", !otherEntered)
    }

    @Test
    fun `the lock is free again once the outer section exits`() = runTest {
        val lock = ReentrantMutex()

        lock.withLock { lock.withLock { } }

        var entered = false
        lock.withLock { entered = true }
        assertTrue(entered)
    }

    @Test
    fun `an exception inside a nested section still frees the lock`() = runTest {
        val lock = ReentrantMutex()

        val thrown = runCatching {
            lock.withLock {
                lock.withLock { error("boom") }
            }
        }.exceptionOrNull()

        assertEquals("boom", thrown?.message)

        var entered = false
        lock.withLock { entered = true }
        assertTrue(entered)
    }

    @Test
    fun `an exception in the outer section still frees the underlying mutex`() = runTest {
        val lock = ReentrantMutex()

        runCatching { lock.withLock { error("boom") } }

        var entered = false
        lock.withLock { entered = true }
        assertTrue(entered)
    }
}
