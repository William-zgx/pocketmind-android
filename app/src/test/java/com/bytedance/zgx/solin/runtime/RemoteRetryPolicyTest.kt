package com.bytedance.zgx.solin.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRetryPolicyTest {

    private fun policy(
        maxAttempts: Int = 3,
        baseDelayMillis: Long = 500L,
        maxDelayMillis: Long = 4_000L,
        randomFraction: () -> Double = { 0.5 },
    ) = RemoteRetryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMillis = baseDelayMillis,
        maxDelayMillis = maxDelayMillis,
        randomFraction = randomFraction,
    )

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    fun classifiesRetryableHttpStatuses() {
        assertEquals(RemoteFailureClass.RateLimited, RemoteRetryPolicy.classifyHttpStatus(429))
        assertEquals(RemoteFailureClass.ProviderUnavailable, RemoteRetryPolicy.classifyHttpStatus(408))
        assertEquals(RemoteFailureClass.ProviderUnavailable, RemoteRetryPolicy.classifyHttpStatus(500))
        assertEquals(RemoteFailureClass.ProviderUnavailable, RemoteRetryPolicy.classifyHttpStatus(502))
        assertEquals(RemoteFailureClass.ProviderUnavailable, RemoteRetryPolicy.classifyHttpStatus(503))
    }

    @Test
    fun classifiesCredentialAndRequestErrorsAsPermanent() {
        // Retrying these burns the user's quota and always fails the same way.
        listOf(400, 401, 403, 404, 422).forEach { code ->
            assertEquals(
                "HTTP $code must be permanent",
                RemoteFailureClass.Permanent,
                RemoteRetryPolicy.classifyHttpStatus(code),
            )
        }
    }

    @Test
    fun classifiesTransportThrowablesAsRetryable() {
        assertEquals(
            RemoteFailureClass.Transport,
            RemoteRetryPolicy.classifyThrowable(SocketTimeoutException("timeout"), callCancelled = false),
        )
        assertEquals(
            RemoteFailureClass.Transport,
            RemoteRetryPolicy.classifyThrowable(UnknownHostException("dns"), callCancelled = false),
        )
        assertEquals(
            RemoteFailureClass.Transport,
            RemoteRetryPolicy.classifyThrowable(IOException("reset"), callCancelled = false),
        )
    }

    @Test
    fun classifiesCancellationAsNonRetryable() {
        assertEquals(
            RemoteFailureClass.Cancelled,
            RemoteRetryPolicy.classifyThrowable(IOException("io"), callCancelled = true),
        )
        assertEquals(
            RemoteFailureClass.Cancelled,
            RemoteRetryPolicy.classifyThrowable(CancellationException("cancelled"), callCancelled = false),
        )
    }

    // ── Core decision rules ───────────────────────────────────────────────────

    @Test
    fun retriesTransientFailureWithinAttemptBudget() {
        val decision = policy().decide(
            failureClass = RemoteFailureClass.ProviderUnavailable,
            attempt = 1,
            hasEmittedOutput = false,
        )

        assertTrue("transient failure must be retried", decision is RemoteRetryDecision.Retry)
        assertEquals(2, (decision as RemoteRetryDecision.Retry).attempt)
    }

    @Test
    fun givesUpOncePartialOutputHasBeenEmitted() {
        // Streaming safety: replaying a request after tokens reached the user would duplicate text.
        RemoteFailureClass.entries.forEach { failureClass ->
            val decision = policy().decide(
                failureClass = failureClass,
                attempt = 1,
                hasEmittedOutput = true,
            )
            assertEquals(
                "$failureClass must not retry after output was emitted",
                RemoteRetryDecision.GiveUp,
                decision,
            )
        }
    }

    @Test
    fun neverRetriesPermanentOrCancelledFailures() {
        listOf(RemoteFailureClass.Permanent, RemoteFailureClass.Cancelled).forEach { failureClass ->
            assertEquals(
                "$failureClass must not be retried",
                RemoteRetryDecision.GiveUp,
                policy().decide(failureClass, attempt = 1, hasEmittedOutput = false),
            )
        }
    }

    @Test
    fun stopsAtMaxAttempts() {
        val policy = policy(maxAttempts = 3)

        assertTrue(policy.decide(RemoteFailureClass.Transport, attempt = 1, hasEmittedOutput = false) is RemoteRetryDecision.Retry)
        assertTrue(policy.decide(RemoteFailureClass.Transport, attempt = 2, hasEmittedOutput = false) is RemoteRetryDecision.Retry)
        assertEquals(
            RemoteRetryDecision.GiveUp,
            policy.decide(RemoteFailureClass.Transport, attempt = 3, hasEmittedOutput = false),
        )
    }

    // ── Backoff ───────────────────────────────────────────────────────────────

    @Test
    fun backoffGrowsExponentiallyAndStaysBounded() {
        // randomFraction = 0.5 -> jitter factor exactly 1.0, so delays are deterministic here.
        val policy = policy(baseDelayMillis = 500L, maxDelayMillis = 4_000L, randomFraction = { 0.5 })

        val first = policy.decide(RemoteFailureClass.Transport, attempt = 1, hasEmittedOutput = false)
        val second = policy.decide(RemoteFailureClass.Transport, attempt = 2, hasEmittedOutput = false)

        assertEquals(500L, (first as RemoteRetryDecision.Retry).delayMillis)
        assertEquals(1_000L, (second as RemoteRetryDecision.Retry).delayMillis)
    }

    @Test
    fun backoffNeverExceedsMaxDelayEvenWithLargeAttemptAndJitter() {
        val policy = policy(
            maxAttempts = 20,
            baseDelayMillis = 500L,
            maxDelayMillis = 4_000L,
            randomFraction = { 1.0 }, // maximum jitter
        )

        (1..10).forEach { attempt ->
            val decision = policy.decide(RemoteFailureClass.Transport, attempt = attempt, hasEmittedOutput = false)
            val delay = (decision as RemoteRetryDecision.Retry).delayMillis
            assertTrue("delay $delay must stay within max", delay in 0L..4_000L)
        }
    }

    @Test
    fun honoursRetryAfterHintOverBackoff() {
        val decision = policy().decide(
            failureClass = RemoteFailureClass.RateLimited,
            attempt = 1,
            hasEmittedOutput = false,
            retryAfterMillis = 2_000L,
        )

        assertEquals(2_000L, (decision as RemoteRetryDecision.Retry).delayMillis)
    }

    @Test
    fun clampsHostileRetryAfterHint() {
        val decision = policy(maxDelayMillis = 4_000L).decide(
            failureClass = RemoteFailureClass.RateLimited,
            attempt = 1,
            hasEmittedOutput = false,
            retryAfterMillis = 10 * 60 * 1_000L, // provider says "wait 10 minutes"
        )

        assertEquals(
            "an excessive Retry-After must be clamped so the app cannot stall",
            4_000L,
            (decision as RemoteRetryDecision.Retry).delayMillis,
        )
    }

    // ── Retry-After parsing ───────────────────────────────────────────────────

    @Test
    fun parsesDeltaSecondsRetryAfterHeader() {
        assertEquals(3_000L, RemoteRetryPolicy.parseRetryAfterMillis("3"))
        assertEquals(0L, RemoteRetryPolicy.parseRetryAfterMillis(" 0 "))
    }

    @Test
    fun ignoresUnparseableOrNegativeRetryAfterHeader() {
        assertEquals(null, RemoteRetryPolicy.parseRetryAfterMillis(null))
        assertEquals(null, RemoteRetryPolicy.parseRetryAfterMillis(""))
        assertEquals(null, RemoteRetryPolicy.parseRetryAfterMillis("-5"))
        // HTTP-date form is not supported; caller falls back to exponential backoff.
        assertEquals(null, RemoteRetryPolicy.parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT"))
    }
}
