package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-context-error-classifier] Pattern contract for the context-overflow
 * classifier (audit P0 #2). Every pattern here is a REAL provider error
 * string observed in the wild (audit + OpenMinis#133 + the iFlow/Jenny
 * corpora) — a regression means the recovery path silently dies for that
 * provider, which is exactly the class of bug this classifier exists to
 * prevent. The old substring list missed variants; these tests pin the
 * variants.
 */
class ContextErrorClassifierTest {

    // ── Context overflow: provider-native forms ──────────────────────────────

    @Test
    fun `openai context_length_exceeded code`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("Error code: 400 - {'error': {'message': \"This model's maximum context length is 8192 tokens. However, your messages resulted in 12000 tokens\", 'type': 'invalid_request_error', 'param': 'messages', 'code': 'context_length_exceeded'}}"),
        )
    }

    @Test
    fun `openai maximum context length prose`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("This model's maximum context length is 128000 tokens. However, you requested 130001 tokens."),
        )
    }

    @Test
    fun `anthropic prompt is too long`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("Error: prompt is too long: 210000 tokens > 200000 maximum"),
        )
    }

    @Test
    fun `anthropic input_length exceed variant`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("input_length and input_tokens exceed limit {:input_length=>96435, :input_tokens=>100000}"),
        )
    }

    @Test
    fun `gemini input token count exceeds`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("Error 400: input token count (1500000) exceeds the maximum number of tokens (1048576) allowed per request"),
        )
    }

    @Test
    fun `openminis133 context window of this model`() {
        // The exact miss that motivated the audit item.
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("[context_length_exceeded] Your input exceeds the context window of this model. Consider compacting the conversation."),
        )
    }

    @Test
    fun `openrouter wraps overflow in 429-shaped body`() {
        // Overflow must win over the 429 envelope — OpenRouter pads upstream
        // context errors in rate-limit-styled bodies.
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("Rate limit reached for model. Original error: prompt is too long: 250000 tokens > 200000 maximum"),
        )
    }

    @Test
    fun `generic reduce the length guidance`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("400 Please reduce the length of the messages."),
        )
    }

    @Test
    fun `openai request too large`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("413 Request Entity Too Large: request too large"),
        )
    }

    // ── Other classes ─────────────────────────────────────────────────────────

    @Test
    fun `rate limit classifies as rate limit`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.RATE_LIMIT,
            ContextErrorClassifier.classify("Error code: 429 - {'error': {'message': 'Rate limit reached for requests'}}"),
        )
    }

    @Test
    fun `auth classifies as auth`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.AUTH,
            ContextErrorClassifier.classify("Error code: 401 - {'error': {'message': 'Invalid API key provided'}}"),
        )
    }

    @Test
    fun `5xx classifies as transient`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.TRANSIENT,
            ContextErrorClassifier.classify("Error code: 502 - bad gateway: upstream connect error"),
        )
    }

    @Test
    fun `timeout classifies as transient`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.TRANSIENT,
            ContextErrorClassifier.classify("Request timeout: read timed out after 30000ms"),
        )
    }

    @Test
    fun `unknown stays unknown`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.UNKNOWN,
            ContextErrorClassifier.classify("decoding error: unexpected JSON at position 12"),
        )
    }

    @Test
    fun `null and blank classify unknown`() {
        assertEquals(ContextErrorClassifier.ErrorClass.UNKNOWN, ContextErrorClassifier.classify(null))
        assertEquals(ContextErrorClassifier.ErrorClass.UNKNOWN, ContextErrorClassifier.classify("  "))
    }

    // ── Throwable convenience ─────────────────────────────────────────────────

    @Test
    fun `throwable with cause chain finds overflow`() {
        val cause = IllegalStateException("prompt is too long: 300000 tokens > 200000 maximum")
        val wrapped = RuntimeException("provider request failed", cause)
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify(wrapped),
        )
    }

    // ── Priority ordering ─────────────────────────────────────────────────────

    @Test
    fun `overflow beats rate limit even with both present`() {
        assertEquals(
            ContextErrorClassifier.ErrorClass.CONTEXT_OVERFLOW,
            ContextErrorClassifier.classify("429 rate limit: too many tokens in context_length_exceeded request"),
        )
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    @Test
    fun `banner mentions compacted count when present`() {
        val msg = ContextErrorClassifier.overflowBannerMessage(42)
        assert(msg.contains("42"))
        assert(msg.contains("/compact"))
    }

    @Test
    fun `banner without count still suggests compact`() {
        val msg = ContextErrorClassifier.overflowBannerMessage(0)
        assert(msg.contains("/compact"))
    }
}
