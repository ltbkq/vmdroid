package io.github.ltbkq.vmdroid.engine.avf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AvfDownloadsShare.connectWithRetry — the pure retry helper
 * behind AvfEngine's vsock connect to the guest's 9p rendezvous listener.
 * These tests never touch a real vsock socket or ParcelFileDescriptor; they
 * drive the generic retry policy (now suspend, so it is cancellable in
 * production) with a plain marker object and a no-op suspend sleeper via
 * runBlocking, so the exhausted-budget case does not really sleep.
 */
class AvfDownloadsShareTest {

    @Test fun `succeeds within budget after a couple of failures`() = runBlocking {
        val marker = Any()
        var calls = 0
        var sleeps = 0
        val result = AvfDownloadsShare.connectWithRetry(
            connect = { calls++; if (calls < 3) null else marker },
            attempts = 5,
            sleep = { sleeps++ },
        )
        assertEquals(marker, result)
        assertEquals(3, calls)
        assertEquals(2, sleeps) // slept between attempt 1->2 and 2->3, not after success
    }

    @Test fun `a throwing supplier counts as a failed attempt, not a propagated exception`() = runBlocking {
        val marker = Any()
        var calls = 0
        val result = AvfDownloadsShare.connectWithRetry(
            connect = { calls++; if (calls == 1) throw RuntimeException("boom") else marker },
            attempts = 5,
            sleep = {},
        )
        assertEquals(marker, result)
        assertEquals(2, calls)
    }

    @Test fun `returns null after exhausting attempts without sleeping in real time`() = runBlocking {
        var calls = 0
        var sleeps = 0
        val startNanos = System.nanoTime()
        val result = AvfDownloadsShare.connectWithRetry(
            connect = { calls++; null },
            attempts = 30,
            sleep = { sleeps++ }, // no-op suspend sleeper: never calls delay/Thread.sleep
        )
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        assertNull(result)
        assertEquals(30, calls)
        assertEquals(29, sleeps) // between attempts only, never after the last
        // A real 500ms backoff x 29 gaps would take ~14.5s; the no-op sleeper
        // keeps this well under a second, proving no real sleep happened.
        assertTrue("test took ${elapsedMs}ms; sleeper was not a no-op", elapsedMs < 2000)
    }
}
