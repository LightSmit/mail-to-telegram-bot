package io.github.lightsmit.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    private var now = 1_000_000L
    private val limiter = RateLimiter(limit = 3, windowSeconds = 60, clock = { now })

    @Test
    fun `allows up to the limit and then refuses`() {
        repeat(3) { assertTrue(limiter.tryAcquire(1)) }
        assertFalse(limiter.tryAcquire(1))
    }

    @Test
    fun `users have separate counters`() {
        repeat(3) { limiter.tryAcquire(1) }
        assertTrue(limiter.tryAcquire(2))
    }

    @Test
    fun `window slides and frees places`() {
        repeat(3) { limiter.tryAcquire(1) }
        now += 30_000
        assertFalse(limiter.tryAcquire(1))
        assertEquals(30, limiter.retryAfterSeconds(1))
        now += 30_001
        assertTrue(limiter.tryAcquire(1))
    }
}
