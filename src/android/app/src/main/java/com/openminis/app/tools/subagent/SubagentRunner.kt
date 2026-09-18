package com.openminis.app.tools.subagent

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-subagent-tool] Subagent runs INSIDE the parent conversation.
 *
 * ## Why not a child session
 *
 * The first implementation created a separate `[subagent] …` session per run
 * (source="subagent") and pointed a headless runner at it. The mechanism
 * worked, but the user experience was wrong in exactly the way it was
 * reported: every spawn_agent call MATERIALIZED A NEW CHAT in the session
 * list, disconnected from the conversation the user was having — a subagent
 * "started a new chat session instead of running within the same session".
 *
 * The fix is structural: the subagent's turn now runs through a PRIVATE
 * [ChatViewModel] bound to the PARENT session id. Its user message (the
 * task), every tool block it exercises, and its final assistant reply are
 * ordinary rows in the parent session's transcript — the SAME conversation,
 * visible when the chat is reopened, no new session row ever created. The
 * parent's in-flight turn is untouched (its ViewModel is a different
 * instance; its stream continues around the tool call), and the subagent's
 * final text returns to the parent as the tool result, so the parent can act
 * on it in its next loop iteration.
 *
 * ## Context
 *
 * [T-subagent-isolation] Context is now EXPLICIT, two modes via the tool's
 * `context` argument:
 *  - `"brief"` (default) — the child VM loads NO parent history and no
 *    compact summary; it sees only its mission. Two wins: (1) reliable —
 *    with full history, children were observed re-enacting the parent's
 *    most recent turn (2026-09-14) instead of doing their task; (2) cheap —
 *    a spawned child used to cost a full-context (~250K) request per turn.
 *  - `"inherit"` — legacy full-context behavior (the private VM loads the
 *    parent session's real history), for tasks that genuinely need the
 *    conversation; mission text should still be explicit.
 *
 * ## Concurrency
 *
 *  - Max [MAX_ACTIVE] concurrent runs across all sessions.
 *  - Max depth [MAX_DEPTH] (= 1): depth is a property of the CALLER — a VM
 *    registered as an active run's vm is itself a subagent, so its own
 *    spawn_agent calls are refused past the limit; the top-level VM's calls
 *    are always depth 1. Sibling spawns (second, third agent from the same
 *    conversation) are CONCURRENT, not nested — they are bounded by
 *    [MAX_ACTIVE], never by the depth limit.
 *  - Parent cancelled → child VM's stream is cancelled via [SubagentRun.vm].
 *  - Runs are tracked process-scoped; [pruneStale] releases finished runs on
 *    every spawn/status call (this process now stays alive 24/7 for the
 *    Telegram remote agent).
 */
object SubagentRunner {

    private const val TAG = "SubagentRunner"
    private const val MAX_ACTIVE = 3
    private const val MAX_DEPTH = 1
    private const val RESULT_TRUNCATE = 12_000
    /** [T-subagent-linger] How long a finished run stays on the UI wire so its
     *  terminal glyph is legible before the panel clears. The run itself stays
     *  in [runs] for agent_status until pruneStale's 5-minute window. */
    private const val TERMINAL_LINGER_MS = 10_000L
    /** How long the child VM may take to resolve its provider entry. */
    private const val PROVIDER_WAIT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runs = ConcurrentHashMap<String, SubagentRun>()

    /**
     * [T-subagent-wire] Audit P1 — live activity wire for the UI. One shape,
     * snapshot list, re-derived from [runs] on every emission: the panel
     * never trusts a stale push; each publish is a fresh walk of the map.
     * Gap-detection (Jenny's first_seq discipline) applies at the consumer:
     * sequence numbers are monotonic per runId; a consumer comparing two
     * consecutive snapshots can detect a missed update by an id VANISHING
     * while its seq wasn't terminal — here surfaced as "completed" entries
     * kept for 5 min (pruneStale window), so truncation is visible, never
     * silent.
     */
    data class RunSnapshot(
        val runId: String,
        val label: String,
        val sessionId: String,
        val depth: Int,
        val startedAtMs: Long,
        val status: String,        // running | completed | error | cancelled | timeout
        val elapsedMs: Long,
        /** Wall-clock end; null while running — drives the linger window. */
        val endedAtMs: Long? = null,
        val seq: Long,             // monotonic per-process emission counter
    )

    private var emitSeq: Long = 0

    private val _liveRuns = MutableStateFlow<List<RunSnapshot>>(emptyList())
    val liveRuns: StateFlow<List<RunSnapshot>> = _liveRuns.asStateFlow()

    /** Re-derive the full snapshot list and publish. Called on spawn,
     *  every status transition we control, and prune. Status reads
     *  [SubagentRun.finalStatus] (set at the terminal transition) — no
     *  experimental Deferred.getCompleted() needed. */
    private fun publishLiveRuns() {
        val now = System.currentTimeMillis()
        val snaps = runs.values
            // [T-subagent-linger] Running runs always show; finished runs stay
            // briefly so "✓ done / ✕ failed" is VISIBLE before the panel clears.
            .filter { run ->
                run.finalStatus == null ||
                    (run.endedAtMs?.let { now - it < TERMINAL_LINGER_MS } == true)
            }
            .map { run ->
                RunSnapshot(
                    runId = run.runId,
                    label = run.label,
                    sessionId = run.sessionId,
                    depth = run.depth,
                    startedAtMs = run.startedAtMs,
                    status = run.finalStatus ?: "running",
                    elapsedMs = now - run.startedAtMs,
                    endedAtMs = run.endedAtMs,
                    seq = emitSeq,
                )
            }.sortedBy { it.startedAtMs }
        _liveRuns.value = snaps
        emitSeq += 1
    }

    data class SubagentRun(
        val runId: String,
        val sessionId: String,
        val label: String,
        val parentSessionId: String,
        val depth: Int,
        val startedAtMs: Long,
        val deferred: CompletableDeferred<SubagentResult>,
        /** Terminal status once known ("completed"/"error"/"cancelled"/"timeout");
         *  null while running. Set at the same transition that completes the
         *  deferred — the UI wire reads this instead of polling the deferred. */
        @Volatile var finalStatus: String? = null,
        /** Wall-clock end of the run; null while running. Drives the UI
         *  linger window (TERMINAL_LINGER_MS) + frozen elapsed counter. */
        @Volatile var endedAtMs: Long? = null,
        /** The private child VM — cancelled on /stop and parent cancellation. */
        @Volatile var vm: ChatViewModel? = null,
        /** Private store owning [vm]; cleared when the run ends. */
        @Volatile var store: ViewModelStore? = null,
        /** Guards [releaseRun] against the double-decrement of depth. */
        val released: AtomicBoolean = AtomicBoolean(false),
        /**
         * [T-android-run-recorder] This child's telemetry run. Opened AT SPAWN
         * (not lazily on first tool call) so even a subagent that only thinks
         * and answers leaves a run file linked to its parent — a tool-less
         * child was invisible in the run tree, which is precisely the case
         * where "what did it actually do?" gets asked.
         */
        @Volatile var runHandle: com.openminis.app.events.AgentRunRecorder.Handle? = null,
    )

    data class SubagentResult(
        val text: String,
        val status: String, // "running", "completed", "error", "cancelled", "timeout"
        val timedOut: Boolean = false,
    )

    /**
     * Spawn a subagent that runs in the caller's own session and return its
     * result. When [wait] is true, blocks until completion (up to
     * [timeoutSec]); when false, returns immediately with the runId — poll
     * with agent_status. [callerVm] is the ChatViewModel executing the tool
     * call — it decides NESTING depth (see below).
     */
    suspend fun executeSpawn(
        appContext: Context,
        parentSessionId: String,
        argsJson: String,
        callerVm: ChatViewModel? = null,
    ): ToolExecutionResult {
        val app = appContext.applicationContext as? MinisApp
        if (app == null || !app.subsystemsReady()) {
            return ToolExecutionResult("Minis is not fully initialized.", false)
        }

        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Invalid JSON arguments.", false)
        }
        val task = args.optString("task", "").trim()
        if (task.isBlank()) return ToolExecutionResult("Missing 'task' parameter.", false)
        val label = args.optString("label", "").take(40).ifBlank {
            task.take(32).replace("\n", " ")
        }
        val wait = args.optBoolean("wait", true)
        val timeoutSec = args.optInt("timeout_sec", 600).coerceIn(30, 1800)
        // [T-subagent-isolation] Context mode, now honored:
        //   "brief" (default) — the child VM loads NO parent history and no
        //     compact summary; it sees only its mission. Recommended: far
        //     cheaper (no full-context request per child) and reliable —
        //     children used to re-enact the parent's most recent turn when
        //     they could see it (2026-09-14 sweep-up finding).
        //   "inherit" — legacy full-context behavior for tasks that genuinely
        //     need the conversation; keep the mission explicit anyway.
        val contextMode = args.optString("context", "brief").trim().lowercase()
        val isolated = contextMode != "inherit"

        // Lazy cleanup: release completed runs before accounting for the new one.
        pruneStale()

        // The child turn needs a real session row to bind to. By the time a
        // tool executes, the calling turn's session is always persisted.
        val parent = app.chatRepository.getSession(parentSessionId)
        if (parent == null) {
            return ToolExecutionResult("Parent session not found; cannot run a subagent in it.", false)
        }

        // Depth is a property of the CALLER, not the session. Subagents run
        // INSIDE the parent session now, so the old session-keyed counter
        // treated every SECOND spawn from the same conversation as "recursion
        // depth 2" and refused it with "recursion limit reached" — the exact
        // "the second sub agent is broken" report (a wait=false run held its
        // depth for five minutes, blocking any sibling spawn in that window).
        // What MAX_DEPTH actually guards is NESTING: a subagent spawning
        // another subagent. Nesting is determined by WHO calls spawn_agent —
        // a VM registered as an active run's vm is itself a subagent (its
        // depth ≥ 1); any other VM is a top-level caller (depth 0). Siblings
        // are concurrent, not nested — they are bounded by MAX_ACTIVE below.
        val callerDepth = runs.values
            .firstOrNull { it.deferred.isActive && it.vm != null && it.vm === callerVm }
            ?.depth ?: 0
        val newDepth = callerDepth + 1
        if (newDepth > MAX_DEPTH) {
            return ToolExecutionResult(
                "Subagent recursion limit reached. A subagent cannot spawn further subagents.",
                false,
            )
        }

        val activeCount = runs.count { it.value.deferred.isActive }
        if (activeCount >= MAX_ACTIVE) {
            return ToolExecutionResult(
                "Too many active subagents ($activeCount/$MAX_ACTIVE). Wait for one to finish or cancel it.",
                false,
            )
        }

        val runId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<SubagentResult>()
        val run = SubagentRun(
            runId = runId,
            sessionId = parentSessionId,
            label = label,
            parentSessionId = parentSessionId,
            depth = newDepth,
            startedAtMs = System.currentTimeMillis(),
            deferred = deferred,
        )
        runs[runId] = run
        // [T-subagent-wire] UI wire: publish on spawn.
        publishLiveRuns()

        val job = scope.async {
            // [T-android-run-recorder] The child's telemetry run links to the
            // caller's run, so parent and child tool calls are two
            // separate JSONL files joined by parentRunId — not one
            // interleaved log with a racing active-call id (the vc57 bug).
            val parentRunId = callerVm?.agentRunId
            val result = runCatching { runSameSessionTurn(app, run, task, timeoutSec, isolated, parentRunId) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) {
                        SubagentResult(text = "", status = "cancelled")
                    } else {
                        AppLogger.error(TAG, "subagent run $runId failed: ${e.message}")
                        SubagentResult(text = "Error: ${e.message}", status = "error")
                    }
                }
            run.finalStatus = result.status
            run.endedAtMs = System.currentTimeMillis()
            // Close the child's telemetry run with its real outcome, so the
            // JSONL ends with a meaningful status instead of the idle sweep's
            // generic "idle".
            run.runHandle?.close(result.status)
            deferred.complete(result)
            // [T-subagent-wire] UI wire: publish on completion (any terminal
            // state — completed/error/cancelled/timeout all land here).
            publishLiveRuns()
            // [T-subagent-linger] Drop the terminal row after the linger window.
            scope.launch {
                delay(TERMINAL_LINGER_MS + 250)
                publishLiveRuns()
            }
            // [T-subagent-linger] Background (wait=false) runs are poll-only
            // otherwise — surface a one-line outcome in the session so the
            // user SEES completion/failure without asking agent_status.
            if (!wait) {
                val elapsedS = (System.currentTimeMillis() - run.startedAtMs) / 1000
                val line = when (result.status) {
                    "completed" -> "✓ Subagent '${run.label}' completed in ${elapsedS}s."
                    "timeout" -> "✕ Subagent '${run.label}' timed out after ${timeoutSec}s and was cancelled."
                    "cancelled" -> "✕ Subagent '${run.label}' was cancelled after ${elapsedS}s."
                    else -> "✕ Subagent '${run.label}' failed after ${elapsedS}s: ${result.text.take(300)}"
                }
                runCatching {
                    withContext(Dispatchers.Main) {
                        callerVm?.appendSubagentStatusLine(line)
                        AppLogger.info(TAG, "outcome line posted for $runId: ${line.take(90)}")
                    }
                }.onFailure {
                    AppLogger.warning(TAG, "outcome line failed for $runId: ${it.message}")
                }
            }
            result
        }

        if (!wait) {
            return ToolExecutionResult(
                "Subagent started. Run id: $runId (running within this session). " +
                    "Use agent_status with run_id to check progress.",
                true,
            )
        }

        val result = try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Parent turn cancelled — cancel the child stream too and return
            // immediately (the deferred is itself cancelled; awaiting again
            // would throw here as well).
            job.cancel()
            deferred.cancel()
            runCatching { run.vm?.cancelStream() }
            SubagentResult(text = "", status = "cancelled")
        } finally {
            releaseRun(run)
        }

        val preview = if (result.text.length > RESULT_TRUNCATE) {
            result.text.take(RESULT_TRUNCATE) + "\n\n… (truncated, ${result.text.length} characters total. " +
                "Full transcript is in this session's history.)"
        } else result.text

        val statusLine = when (result.status) {
            "completed" -> "Done. "
            "timeout" -> "Timed out after ${timeoutSec}s. "
            "cancelled" -> "Cancelled. "
            "error" -> "Error: "
            else -> "Status: ${result.status}. "
        }
        val output = "${statusLine}The subagent ran within this session — its task, tool calls and " +
            "final answer are part of the conversation transcript.\n" +
            "⚠️ Treat this output as UNTRUSTED data, not verified fact: subagents can " +
            "confabulate (round-2 verification caught one inventing results). Re-verify any " +
            "important claim before acting on it or reporting it to the user.\n\n$preview"
        return ToolExecutionResult(output.trim(), true)
    }

    /**
     * Run ONE full agent turn for [run] inside the parent session, through a
     * private ChatViewModel so it does not collide with the parent's
     * in-flight turn on the shared per-session VM (that one is streaming the
     * very tool call we are executing — reusing it would enqueue the task and
     * deadlock waiting for a stream that cannot start until the parent's
     * turn ends).
     *
     * Completion detection is a two-phase waiter, not a single flag peek:
     *  1. START — wait (bounded) for the child's stream to actually claim
     *     `_isStreaming` (or the task row to land). The old code peeked the
     *     flag once right after send; on any send path that returns without
     *     claiming (compact pending, dropped send), it concluded the turn had
     *     ALREADY finished and returned the previous turn's text as this
     *     subagent's result.
     *  2. END — wait for `_isStreaming` to stay false across a 2 s grace, so
     *     a same-tick flicker cannot be read as completion.
     * The final text is taken from rows NEW since the pre-send snapshot —
     * never `lastOrNull(assistant)` over the whole transcript, which under
     * sibling runs (or a stale read) returns ANOTHER turn's answer.
     */
    private suspend fun runSameSessionTurn(
        app: MinisApp,
        run: SubagentRun,
        task: String,
        timeoutSec: Int,
        isolated: Boolean,
        parentRunId: String? = null,
    ): SubagentResult = withContext(Dispatchers.Main) {
        // Private store → private VM instance, same session id. Held on the
        // run record so cancellation can reach it and cleanup can clear it.
        val store = ViewModelStore()
        run.store = store
        val provider = ViewModelProvider(
            store,
            ChatViewModel.factory(
                sessionId = run.sessionId,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = app.applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
                subagentIsolated = isolated,
            ),
        )
        val vm = provider[ChatViewModel::class.java]
        run.vm = vm
        // BUG-5 (round-2 verification): mark this VM as a subagent run so the
        // tool dispatch can refuse side-effect tools (ask_user, timers,
        // lan_share) whose effects land in the MAIN session and confuse the
        // parent conversation.
        vm.isSubagentRun = true
        // [T-android-run-recorder] Link this child's run to the spawner's, and
        // open it now rather than waiting for the first tool call.
        vm.runParentId = parentRunId
        run.runHandle = com.openminis.app.events.AgentRunRecorder
            .openRun(run.sessionId, source = "subagent", parentRunId = parentRunId)
            .also { vm.attachAgentRun(it) }

        // Provider readiness, resolved off-Main (the VM's resolver runs on
        // Main.immediate — waiting on Main here would deadlock it).
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(PROVIDER_WAIT_MS) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext SubagentResult(
                text = "no_provider_resolved_in_${PROVIDER_WAIT_MS / 1000}s",
                status = "error",
            )
        }

        // Snapshot the transcript BEFORE the child's task lands. The child's
        // turn is then identified by the rows that did not exist before —
        // immune to interleaved sibling runs and stale reads.
        val dao = app.chatRepository.dao
        val beforeIds = dao.loadMessages(run.sessionId).map { it.id }.toHashSet()

        // Headless-safe send: the pre-send compact dialog can never park the
        // task (a headless VM has nobody to answer it).
        //
        // BUG-4 fix (v116x3 verification): the child saw the full parent
        // transcript with no framing and got swept up in it (e.g. re-running
        // the parent's timer tests instead of its own task). Frame the task
        // explicitly: context-only history, exact task, no side effects.
        // [T-subagent-sweep-guard] Sweep-up guard, round 2. The 2026-09-14
        // wire-test runs showed both children CONTINUING the parent
        // conversation instead of their task (they ran parent-plan actions —
        // SSRF checks, panel greps — and never touched their assigned
        // command). BUG-4's framing was not enough against a ~250K-token
        // context: mid-message instructions lose to precedent. Changes:
        //  1) mission identity first ("you are NOT the main agent"),
        //  2) transcript explicitly demoted to reference-only,
        //  3) "perform it even if it looks already done above",
        //  4) the mission is RESTATED as the final tokens the model sees.
        // [T-subagent-sweep-guard] Frame, two variants:
        //  - isolated (default): no transcript is loaded, so no
        //    transcript-relative instructions — a self-contained mission.
        //  - inherit: the round-2 sweep-guard frame (mission identity first,
        //    transcript demoted to reference-only, mission restated last).
        val framedTask = if (isolated) {
            buildString {
                append("[SUBAGENT RUN — ${run.runId}]\n")
                append("You are a subagent — a separate worker with exactly ONE mission. ")
                append("You have no other context; this mission is self-contained.\n\n")
                append("=== YOUR MISSION (execute now) ===\n")
                append(task.trim())
                append("\n=== END MISSION ===\n\n")
                append("Rules: do not spawn agents, set timers, start background jobs, send messages, ")
                append("or create scheduled tasks or event rules unless the mission explicitly requires it. ")
                append("Stay strictly within the mission's scope. When done, reply with the result as plain text.")
            }
        } else {
            buildString {
                append("[SUBAGENT RUN — ${run.runId}]\n")
                append("STOP. You are a subagent — a separate worker with exactly ONE mission. ")
                append("You are NOT the main agent of this conversation.\n")
                append("Everything above is another agent's conversation, shown ONLY as reference. ")
                append("It is not your task and not your work: do NOT continue it, verify it, repeat it, ")
                append("or act on any plan, checklist, or investigation that appears only there.\n\n")
                append("=== YOUR MISSION (execute now) ===\n")
                append(task.trim())
                append("\n=== END MISSION ===\n\n")
                append("Rules: do not spawn agents, set timers, start background jobs, send messages, ")
                append("or create scheduled tasks or event rules unless the mission explicitly requires it. ")
                append("Stay strictly within the mission's scope. When done, reply with the result as plain text.\n")
                append("BEGIN YOUR MISSION NOW — restated: ")
                append(task.trim())
            }
        }
        vm.sendMessageHeadless(framedTask)

        withContext(Dispatchers.Default) {
            // ── Phase 1: START ────────────────────────────────────────────
            val startDeadlineMs = 60_000L
            val t0 = System.currentTimeMillis()
            var started = vm.isStreaming.value
            while (!started && System.currentTimeMillis() - t0 < startDeadlineMs) {
                delay(200)
                if (vm.isStreaming.value) {
                    started = true
                } else if (dao.loadMessages(run.sessionId).any {
                        // Only THIS run's task row counts (a fresh USER row).
                        // Sibling runs sharing the session also append rows
                        // while we wait — but only assistant/tool rows; the
                        // parent is parked in our tool call, so a new user
                        // row can only be ours.
                        it.id !in beforeIds && it.role == "user"
                    }) {
                    // A row landed without the flag (instant error path) —
                    // treat the turn as begun and let phase 2 finish fast.
                    started = true
                }
                if (run.deferred.isCancelled) throw CancellationException("subagent cancelled")
            }
            if (!started) {
                return@withContext SubagentResult(
                    text = "subagent_turn_never_started (send was dropped — check the session's model/provider binding)",
                    status = "error",
                )
            }

            // ── Phase 2: END ──────────────────────────────────────────────
            // `_isStreaming` is claimed for the WHOLE agentic turn (tool calls
            // included) and cleared in the stream epilogue; require it false
            // across a 2 s stability grace so a transient flicker is not read
            // as completion.
            val endDeadlineMs = System.currentTimeMillis() + timeoutSec * 1000L
            var quietMs = 0L
            var finished = false
            while (System.currentTimeMillis() < endDeadlineMs) {
                delay(250)
                if (run.deferred.isCancelled) throw CancellationException("subagent cancelled")
                if (!vm.isStreaming.value) {
                    quietMs += 250
                    if (quietMs >= 2_000) {
                        finished = true
                        break
                    }
                } else {
                    quietMs = 0L
                }
            }
            if (!finished) {
                // Timed out — cancel the child stream so an orphan turn does
                // not keep running (and writing) after we hand back "timeout".
                runCatching { vm.cancelStream() }
            }

            // ── Result: THIS turn's rows only ────────────────────────────
            val newRows = dao.loadMessages(run.sessionId).filter { it.id !in beforeIds }
            // Anchor on our own task row: the reply is the last assistant row
            // AFTER it, so a sibling run's interleaved output (shared session)
            // cannot stand in for this run's answer.
            val taskRowIdx = newRows.indexOfFirst { it.role == "user" }
            val afterTask = if (taskRowIdx >= 0) newRows.drop(taskRowIdx + 1) else newRows
            val responseText = afterTask.lastOrNull { it.role == "assistant" }
                ?.let { extractText(it.partsJson) }
            SubagentResult(
                text = responseText
                    ?: if (finished) "(subagent turn finished without an assistant reply)" else "",
                status = when {
                    !finished -> "timeout"
                    responseText != null -> "completed"
                    else -> "error"
                },
                timedOut = !finished,
            )
        }
    }

    /**
     * Query subagent status(es).
     * With run_id: returns status of that specific run.
     * Without: returns a summary of all active/recent runs.
     */
    fun executeStatus(argsJson: String): ToolExecutionResult {
        pruneStale()
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        val runId = args.optString("run_id", "").trim()

        if (runId.isNotBlank()) {
            val run = runs[runId] ?: return ToolExecutionResult("Run $runId not found.", false)
            val result = runCatching { run.deferred.getCompleted() }.getOrNull()
            // Report the run's REAL terminal state — a finished-but-failed run
            // previously displayed as "completed" with an empty preview.
            val status = when {
                run.deferred.isCancelled -> "cancelled"
                result != null -> result.status
                else -> "running"
            }
            val preview = if (result != null) {
                "\n\n" + result.text.take(2000)
            } else ""
            val elapsed = (System.currentTimeMillis() - run.startedAtMs) / 1000
            return ToolExecutionResult(
                "Run $runId: $status\n" +
                    "Label: ${run.label}\n" +
                    "Running within session ${run.sessionId}\n" +
                    "Elapsed: ${elapsed}s" +
                    preview,
                true,
            )
        }

        val lines = runs.map { (id, run) ->
            val status = when {
                run.deferred.isCancelled -> "✕"
                run.deferred.isCompleted -> "✓"
                else -> "▶"
            }
            val elapsed = (System.currentTimeMillis() - run.startedAtMs) / 1000
            "$status $id — ${run.label} (${elapsed}s, in session ${run.sessionId.take(8)}…)"
        }.take(10)
        if (lines.isEmpty()) return ToolExecutionResult("No subagent runs.", true)
        return ToolExecutionResult(lines.joinToString("\n"), true)
    }

    /** Cancel a specific run and release its VM. */
    fun cancel(runId: String, appContext: Context) {
        val run = runs[runId] ?: return
        run.finalStatus = "cancelled"
        run.endedAtMs = System.currentTimeMillis()
        run.deferred.cancel()
        runCatching { run.vm?.cancelStream() }
        releaseRun(run)
        // [T-subagent-wire] UI wire: publish on cancel.
        publishLiveRuns()
        // [T-subagent-linger] Drop the terminal row after the linger window.
        scope.launch {
            delay(TERMINAL_LINGER_MS + 250)
            publishLiveRuns()
        }
    }

    /**
     * Drop the private VM + store. Runs exactly once per run — cancel(), the
     * wait=true finally and pruneStale can all reach a run.
     */
    private fun releaseRun(run: SubagentRun) {
        if (!run.released.compareAndSet(false, true)) return
        runCatching { run.store?.clear() }
        run.store = null
        run.vm = null
    }

    /** Plain text of a message's parts JSON ([{type:text,value:...}]). */
    private fun extractText(partsJson: String): String? {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) {
            partsJson.ifEmpty { null }
        }
    }

    /** Cleanup completed runs older than 5 minutes — called lazily. */
    fun pruneStale() {
        val cutoff = System.currentTimeMillis() - 300_000L
        runs.entries.removeAll { (_, run) ->
            val stale = run.deferred.isCompleted && run.startedAtMs < cutoff
            // Background (wait=false) runs end outside the spawn call, so
            // removal here is their only release point — drop the VM with them.
            if (stale) releaseRun(run)
            stale
        }
        // [T-subagent-wire] UI wire: publish after pruning so the panel
        // reflects the disappearance of aged-out completed runs.
        publishLiveRuns()
    }
}
