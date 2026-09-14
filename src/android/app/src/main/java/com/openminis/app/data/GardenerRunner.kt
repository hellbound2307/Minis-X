package com.openminis.app.data

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * [T-gardener] Audit P0 #6 — the runner behind the session-end trigger.
 *
 * One pass = ONE candidate journal (least-recently-gardened eligible
 * file), ONE model call (same provider path as Dream/compaction), at most
 * 3 pages written. Guards:
 *
 *   - trigger only when ALL sessions are idle (the completion hook fires
 *     on every stream end; gardening while another chat streams would
 *     fight it — Jenny's silence-clock)
 *   - re-check idle before EVERY page write; stand down mid-pass if the
 *     user came back (user-always-wins)
 *   - pass scope is its own SupervisorJob — a crash here must never touch
 *     the completion path
 *   - every trigger no-ops cheaply when nothing is eligible (the common
 *     case: distance clock 6h, age ≥2d, most triggers find nothing)
 *
 * Wiki lands in shared/wiki/ — guest-visible at /var/minis/shared/wiki,
 * survives sandbox resets, browsable in the app.
 */
object GardenerRunner {

    private const val TAG = "Gardener"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Anti-reentry: one pass at a time across all triggers. */
    @Volatile
    private var running = false

    fun maybeGarden(context: Context) {
        if (running) return
        val app = context.applicationContext as? MinisApp ?: return
        if (!app.subsystemsReady()) return
        // Silence clock: all sessions idle at trigger time.
        if (SessionActivityTrackerActiveSessions.value.isNotEmpty()) return
        running = true
        scope.launch {
            try {
                gardenOnce(app)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "gardener pass failed (non-fatal): ${t.message}")
            } finally {
                running = false
            }
        }
    }

    private suspend fun gardenOnce(app: MinisApp) {
        val sharedDir = File(app.filesDir, "minis-global/shared")
        val memoryDir = File(app.filesDir, "minis-global/memory")
        val candidate = Gardener.pickCandidate(memoryDir, sharedDir)
        if (candidate == null) return
        AppLogger.info(TAG, "gardener: considering ${candidate.name} (${candidate.lines} lines, last=${candidate.lastGardenedMs})")

        // Provider: same resolution Dream uses — the chat default. Dream
        // goes through ChatViewModel's currentProvider; the runner has no
        // VM, so resolve through the repository the same way ScheduledAgentRunner
        // does for its headless runs. Simplified: reuse the FIRST configured
        // provider instance's stream-capable provider via ProviderFactory.
        val provider = resolveProvider(app) ?: run {
            AppLogger.warning(TAG, "gardener: no provider — skipping this pass")
            return
        }

        val userMessage = Gardener.PROMPT + "\n\n=== JOURNAL FILE: ${candidate.name} ===\n\n" +
            candidate.content + "\n\n=== END OF JOURNAL ==="
        val response = provider.sendMessage(
            messages = listOf(
                com.openminis.app.data.model.LLMMessage(
                    role = com.openminis.app.data.model.LLMMessage.Role.USER,
                    content = userMessage,
                ),
            ),
            systemPrompt = "You are a knowledge gardener. Output only the PAGE blocks requested — no prose outside them.",
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = com.openminis.app.data.model.ThinkingLevel.OFF,
        )

        // Parse === PAGE blocks. Stand down between writes if the user returned.
        val text = response.text
        var pagesWritten = 0
        val blocks = Regex("(?s)=== PAGE: (.+?)\\n(.*?)(?:\\n)?=== END").findAll(text).toList()
        for (match in blocks.take(3)) {
            val title = match.groupValues[1].trim()
            var body = match.groupValues[2].trim()
            // state line inside body?
            val stateMatch = Regex("(?m)^state:\\s*(\\w+)\\s*$").find(body)
            val state = stateMatch?.groupValues?.get(1)?.lowercase() ?: "open"
            if (stateMatch != null) {
                body = body.replace(stateMatch.value, "").trim()
            }
            if (title.isBlank() || body.isBlank()) continue
            // User-always-wins: re-check idle before every write.
            if (SessionActivityTrackerActiveSessions.value.isNotEmpty()) {
                AppLogger.info(TAG, "gardener: user returned mid-pass — standing down ($pagesWritten page(s) written)")
                break
            }
            val page = Gardener.writePage(sharedDir, title, state, candidate.name, body)
            if (page != null) {
                pagesWritten++
                AppLogger.info(TAG, "gardener: promoted [$title] (${page.name}) state=$state from ${candidate.name}")
            }
        }
        Gardener.markGardened(sharedDir, candidate.name)
        AppLogger.info(TAG, "gardener pass done: ${candidate.name} → $pagesWritten page(s)")
    }

    private fun resolveProvider(app: MinisApp): com.openminis.app.provider.LLMProvider? {
        return try {
            val config = app.providerRepository.config.value
            // First credentialed entry (same gate the chat uses:
            // hasAnyCredential — OAuth counts, key-less doesn't).
            val entry = config.modelEntries.firstOrNull { entry ->
                app.providerRepository.instance(entry.providerInstanceId)?.let { inst ->
                    app.providerRepository.hasAnyCredential(inst)
                } == true
            } ?: return null
            val instance = app.providerRepository.instance(entry.providerInstanceId) ?: return null
            val apiKey = app.providerRepository.usableApiKey(instance) ?: ""
            com.openminis.app.provider.ProviderFactory.create(instance, apiKey, entry.model, app)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "gardener provider resolution failed: ${t.message}")
            null
        }
    }

    /** Idle check shared with the pass — the tracker's live session set. */
    private object SessionActivityTrackerActiveSessions {
        val value: Set<String>
            get() = com.openminis.app.service.SessionActivityTracker.activeSessions.value
    }
}
