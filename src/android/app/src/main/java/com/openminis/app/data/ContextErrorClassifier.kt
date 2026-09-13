package com.openminis.app.data

/**
 * [T-context-error-classifier] Pure-logic classifier for provider error
 * strings — audit P0 #2. The agent loop's catch-site already branches on
 * LLMError TYPE (RateLimited / NetworkError / ProviderError-with-5xx),
 * but context-overflow errors arrive as generic ProviderError/Unknown with
 * only their TEXT distinguishing them, and every provider words the
 * refusal differently. The old [ChatViewModel.isContextTooLargeError]
 * substring list was provably incomplete (OpenMinis#133's
 * `[context_length_exceeded]` variant slipped past) — and a miss meant
 * no recovery, just a raw error bubble.
 *
 * This class is the single shared matcher for that family. Two jobs:
 *
 *   1. classify(errorText) → ErrorClass — what KIND of failure is this?
 *      Used by the agent-loop catch to decide retry/compact/fallback/surface.
 *   2. Patterns are regex-anchored, ordered by specificity (provider-native
 *      codes first, generic phrasings last) so a provider's own wording
 *      wins over a coincidental generic match.
 *
 * Kept dependency-free (java.util.regex only) so it runs on the JVM in
 * unit tests — the classifier is pure and carries the real risk here:
 * a pattern miss silently disables the overflow recovery path.
 *
 * Patterns sourced from the competitive audit (Jenny's classifiers —
 * AGPL-3.0, architecture-compatible) verified against:
 *   - OpenAI:      `context_length_exceeded`, "maximum context length",
 *                  "This model's maximum context length is N tokens"
 *   - Anthropic:   "prompt is too long: N tokens > M maximum",
 *                  `input_length and input_tokens exceed limit`
 *   - Gemini:      "input token count exceeds the maximum number of tokens"
 *   - Generic:     "too many tokens", "context window", "request too large"
 */
object ContextErrorClassifier {

    /** Coarse failure classes the agent loop reacts to differently. */
    enum class ErrorClass {
        /** History exceeded the model's context window → compact + retry. */
        CONTEXT_OVERFLOW,

        /** Provider quota / 429 → existing fallback path (no retry-same). */
        RATE_LIMIT,

        /** Server-side 5xx / upstream → existing transient retry path. */
        TRANSIENT,

        /** Bad credentials → surface immediately, never retry. */
        AUTH,

        /** Anything else — no special handling. */
        UNKNOWN,
    }

    // ── Rate limit / transient / auth ───────────────────────────────────────────
    // These overlap the existing TYPE-based branches; the classifier is the
    // text-level fallback for providers that wrap everything in a generic
    // ProviderError body.

    private val RATE_LIMIT_PATTERNS = listOf(
        // OpenAI / OpenRouter style
        Regex("""(?i)\b429\b"""),
        Regex("""(?i)rate[ _-]?limit"""),
        Regex("""(?i)too many requests"""),
        Regex("""(?i)quota exceeded"""),
        Regex("""(?i)insufficient[ _]?(credits?|quota)"""),
    )

    private val TRANSIENT_PATTERNS = listOf(
        Regex("""(?i)\b5[0-9]{2}\b"""),  // 500-599 status mention
        Regex("""(?i)overloaded"""),
        Regex("""(?i)internal server error"""),
        Regex("""(?i)bad gateway"""),
        Regex("""(?i)service unavailable"""),
        Regex("""(?i)upstream"""),
        Regex("""(?i)timeout|timed out"""),
    )

    private val AUTH_PATTERNS = listOf(
        Regex("""(?i)invalid[ _-]?api[ _-]?key"""),
        Regex("""(?i)unauthorized|\b401\b"""),
        Regex("""(?i)permission denied"""),
        Regex("""(?i)authentication"""),
    )

    // ── Context overflow ────────────────────────────────────────────────────────
    // Ordered most-specific first: provider-native codes, then their prose
    // forms, then generic phrasings. A hit anywhere in this list is an
    // overflow NO MATTER what else the body says — overflow is checked
    // FIRST in classify() because providers sometimes wrap the overflow
    // message inside a rate-limit-shaped envelope (OpenRouter does this:
    // a 429-styled body whose detail is the upstream context error).

    private val CONTEXT_OVERFLOW_PATTERNS = listOf(
        // OpenAI / OpenAI-compatible (vLLM, Together, DeepSeek, Bailian, GLM…)
        Regex("""(?i)context[ _]length[ _]exceeded"""),
        Regex("""(?i)context[ _-]?length[ _]exceeded"""), // [context_length_exceeded]
        Regex("""(?i)maximum[ _]context[ _]length"""),   // "maximum context length is 8192 tokens"
        Regex("""(?i)prompt[ _]is[ _]too[ _]long"""),     // Anthropic native
        Regex("""(?i)prompt[ _]too[ _]long"""),
        Regex("""(?i)input[ _]token[ _]count[ _]exceeds"""), // Gemini native
        Regex("""(?i)exceeds?[ _]the[ _]maximum[ _]number[ _]of[ _]tokens"""),
        Regex("""(?i)input_length[ _]and[ _]input_tokens[ _]exceed"""), // Anthropic API
        Regex("""(?i)too[ _]many[ _]tokens"""),
        Regex("""(?i)request[ _]too[ _]large"""),
        Regex("""(?i)content[ _]is[ _]too[ _]long"""),
        Regex("""(?i)exceeds?[ _]the[ _]model'?s?[ _]context"""),
        Regex("""(?i)exceeds?[ _]the[ _]context[ _]window"""),
        Regex("""(?i)context[ _]window[ _]of[ _]this[ _]model"""), // OpenMinis#133
        Regex("""(?i)reduce[ _]the[ _]length[ _]of[ _]the[ _](input|messages|prompt)"""),
        Regex("""(?i)max_[ _]?tokens?[ _]?(limit|exceeded|reached)"""),
        Regex("""(?i)maximum[ _]number[ _]of[ _]tokens"""),
    )

    /**
     * Classify an error body. Overflow is checked FIRST (see patterns doc);
     * then auth (never retry); then rate-limit; then transient; else unknown.
     */
    fun classify(errorText: String?): ErrorClass {
        if (errorText.isNullOrBlank()) return ErrorClass.UNKNOWN
        if (CONTEXT_OVERFLOW_PATTERNS.any { it.containsMatchIn(errorText) }) {
            return ErrorClass.CONTEXT_OVERFLOW
        }
        if (AUTH_PATTERNS.any { it.containsMatchIn(errorText) }) {
            return ErrorClass.AUTH
        }
        if (RATE_LIMIT_PATTERNS.any { it.containsMatchIn(errorText) }) {
            return ErrorClass.RATE_LIMIT
        }
        if (TRANSIENT_PATTERNS.any { it.containsMatchIn(errorText) }) {
            return ErrorClass.TRANSIENT
        }
        return ErrorClass.UNKNOWN
    }

    /** Convenience: classify a Throwable's rendered text. */
    fun classify(error: Throwable): ErrorClass =
        classify(buildString {
            append(error.message ?: "")
            append(" ")
            append(error.toString())
            // Some providers only put the detail in the CAUSE chain.
            var c = error.cause
            var depth = 0
            while (c != null && depth < 4) {
                append(" ")
                append(c.message ?: "")
                c = c.cause
                depth++
            }
        })

    /**
     * Human-facing one-liner for the overflow banner. Kept here so the
     * classifier + its presentation can't drift apart.
     */
    fun overflowBannerMessage(compactedMessages: Int): String =
        if (compactedMessages > 0) {
            "Context exceeded this model's window — summarized $compactedMessages older messages and retried. " +
                "Consider /compact or a new chat if this repeats."
        } else {
            "Context exceeded this model's window. Use /compact or start a new chat to continue."
        }
}
