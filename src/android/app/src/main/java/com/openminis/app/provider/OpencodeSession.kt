package com.openminis.app.provider

import android.content.Context
import okhttp3.Interceptor
import java.util.UUID

/**
 * [T-android-opencode-session] `x-opencode-session` support.
 *
 * OpenCode's API rejects requests that omit this header. The upstream fix for
 * this (hgfs7273-pixel/OpenMinis1:fix/x-opencode-session) works, but injects the
 * header from five places across two platforms, sends it to EVERY provider
 * including OAuth flows, and mints a fresh ID on every Android launch while iOS
 * persists one — with the justification "would require Context plumbing", which
 * is not true in this codebase.
 *
 * Three deliberate differences here:
 *
 * 1. ONE chokepoint. An OkHttp [Interceptor] on the provider client, rather than
 *    a helper call at each request-builder site. A per-site approach is the same
 *    add-it-in-N-places pattern that shipped three mount bugs in this project;
 *    the interceptor cannot be forgotten at a call site that does not exist yet.
 *
 * 2. HOST-GATED. The header is attached only when the request actually targets
 *    an OpenCode endpoint. A stable per-install UUID is a device identifier —
 *    spraying it at Anthropic, Google and the ChatGPT OAuth backend would be a
 *    tracking vector handed out for free, and nonstandard headers on consumer
 *    OAuth endpoints are exactly what anti-abuse heuristics watch for. The
 *    upstream comment asserts "harmless on chatgpt.com"; that is an assumption,
 *    not a measurement, and gating makes it moot.
 *
 * 3. PERSISTED per install, matching iOS. The upstream Android side mints a new
 *    UUID every process start, so the "stable session id" is stable only until
 *    the next app launch. Whatever the server does with it (dedup, rate
 *    accounting, continuity), Android and iOS should not disagree.
 */
object OpencodeSession {

    const val HEADER = "x-opencode-session"

    private const val PREFS = "minis_provider_session"
    private const val KEY = "opencode.session_id"

    private val lock = Any()

    @Volatile
    private var cached: String? = null

    @Volatile
    private var appContext: Context? = null

    /** Called once from Application.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Stable per-install id. Persisted on first use; falls back to an
     * in-memory value if prefs are unavailable (a request must never fail
     * because of a header we are adding opportunistically).
     */
    val id: String
        get() {
            cached?.let { return it }
            synchronized(lock) {
                if (cached == null) {
                    val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val existing = prefs?.getString(KEY, null)
                    if (!existing.isNullOrBlank()) {
                        cached = existing
                    } else {
                        val minted = UUID.randomUUID().toString()
                        runCatching {
                            prefs?.edit()?.putString(KEY, minted)?.apply()
                        }
                        cached = minted
                    }
                }
            }
            return cached ?: UUID.randomUUID().toString()
        }

    /**
     * True when [host] belongs to an OpenCode endpoint. Kept permissive on the
     * `opencode` label rather than an exact host list so a regional or
     * self-hosted gateway still works, while every other provider is excluded.
     */
    fun isOpencodeHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "opencode.ai" || h.endsWith(".opencode.ai") || h.contains("opencode")
    }

    /**
     * The chokepoint. Register once per provider OkHttp client.
     */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (isOpencodeHost(request.url.host)) {
            chain.proceed(request.newBuilder().header(HEADER, id).build())
        } else {
            chain.proceed(request)
        }
    }
}
