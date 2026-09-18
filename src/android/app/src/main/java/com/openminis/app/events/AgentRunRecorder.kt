package com.openminis.app.events

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-run-recorder] Pillar A1/A2 — the agent run event bus.
 *
 * One append-only JSONL file per run:
 *   `files/minis-global/runs/<session>/<runId>.jsonl`
 * (bound into every sandbox session at `/var/minis/runs`, so the AGENT can read
 * back its own telemetry — that is the whole point; a log only the UI can see
 * would not fix the blindness this exists to fix).
 *
 * ## vc59: handle-based, not global
 *
 * vc57 kept ONE active run in object state with `@Volatile currentCallId`. That
 * is wrong the moment two runs overlap — and they do: every subagent runs a
 * child ChatViewModel **in this same process**, so a child's tool calls were
 * being appended to the parent's run and `currentCallId` was a race between
 * them. The fix is to stop pretending there is a global "current run":
 *
 *  - [openRun] returns a [Handle]; the caller (a ChatViewModel) owns it and
 *    passes it explicitly to note/beginCall/endCall/streamLine.
 *  - Runs live in a registry keyed by runId, each with its own lock, so parent
 *    and child write concurrently without stepping on each other.
 *  - [activeRuns] exposes a UI-shaped view of every live run — parent and
 *    children — which is what the HUD chip and the subagent lanes read.
 *
 * Contract: never throws into the caller, never blocks longer than a small
 * append, and degrades to a no-op when the app context was never primed (the
 * :acra reporter process).
 *
 * Schema (one JSON object per line):
 *   {seq, ts, runId, sessionId, parentRunId?, kind, tool?, callId?, status?,
 *    durationMs?, exitCode?, bytes?, label?, digest?, stream?, error?}
 *
 * `kind` enum v1 — run_start, run_end, tool_call_start, tool_call_end,
 * tool_stream, tool_note, error.
 */
object AgentRunRecorder {

    private const val TAG = "AgentRunRecorder"

    /** Steps kept per run in memory (the UI needs a tail, not a history). */
    private const val MAX_STEPS_IN_MEMORY = 200

    /** Rolling stdout tail kept per run for the HUD. */
    private const val STREAM_TAIL_CHARS = 4096

    /** A run with no events for this long is closed by [tick]. */
    private const val IDLE_CLOSE_MS = 180_000L

    /** Retention: keep the newest N run files, drop the rest oldest-first. */
    private const val MAX_RUN_FILES = 60

    /** Minimum gap between persisted `tool_stream` lines (the in-memory tail is not throttled). */
    private const val STREAM_PERSIST_INTERVAL_MS = 500L

    /** Minimum gap between UI flow emissions from stream traffic. */
    private const val UI_PUBLISH_INTERVAL_MS = 250L

    /** Bounded digest of tool arguments (already redacted by the caller). */
    private const val DIGEST_CHARS = 240

    @Volatile
    private var baseDir: File? = null

    /** App context, kept so the runs root can be re-resolved per season. */
    @Volatile
    private var appContext: Context? = null

    /** Immutable step record. */
    data class Step(
        val seq: Int,
        val kind: String,
        val tool: String?,
        val label: String?,
        val status: String,
        val atMs: Long,
        val durationMs: Long?,
        val exitCode: Int?,
    )

    /** UI-shaped view of a live run. */
    data class RunInfo(
        val runId: String,
        val shortId: String,
        val sessionId: String,
        val parentRunId: String?,
        val startedAt: Long,
        val lastEventAt: Long,
        val toolCount: Int,
        val activeTool: String?,
        val activeSince: Long,
        val lastTool: String?,
        val lastToolMs: Long,
        val status: String,
        val depth: Int,
    )

    internal class RunState(
        val runId: String,
        val sessionId: String,
        val parentRunId: String?,
        val file: File,
        val startedAt: Long,
        val depth: Int,
    ) {
        val lock = Any()
        var seq = 0
        var lastEventAt = startedAt
        var lastUiPublishAt = 0L
        var lastStreamPersistAt = 0L
        var callSeq = 0
        var activeCallId: String? = null
        var activeTool: String? = null
        var activeSince = 0L
        var status = "running"
        var toolCount = 0
        var lastTool: String? = null
        var lastToolMs = 0L
        @Volatile var closed = false
        val steps = ArrayList<Step>(64)
        val streamTail = StringBuilder()
    }

    private val runs = ConcurrentHashMap<String, RunState>()

    private val _activeRuns = MutableStateFlow<List<RunInfo>>(emptyList())

    /** Every live run, parent and children, oldest first. */
    val activeRuns: StateFlow<List<RunInfo>> = _activeRuns.asStateFlow()

    /**
     * A handle to one run. Held by the ChatViewModel that owns the run —
     * passing it explicitly is what makes concurrent parent/child runs safe.
     */
    class Handle internal constructor(private val state: RunState) {
        val runId: String get() = state.runId
        val sessionId: String get() = state.sessionId
        val parentRunId: String? get() = state.parentRunId
        val isClosed: Boolean get() = state.closed

        fun note(
            kind: String,
            tool: String? = null,
            label: String? = null,
            status: String? = null,
            digest: String? = null,
            fields: Map<String, Any?>? = null,
        ) = AgentRunRecorder.noteOn(state, kind, tool, label, status, digest, fields)

        fun beginCall(tool: String, digest: String?): String? =
            AgentRunRecorder.beginCallOn(state, tool, digest)

        fun endCall(
            tool: String,
            callId: String?,
            status: String,
            durationMs: Long,
            exitCode: Int? = null,
            bytes: Int? = null,
            error: String? = null,
        ) = AgentRunRecorder.endCallOn(state, tool, callId, status, durationMs, exitCode, bytes, error)

        fun streamLine(line: String) = AgentRunRecorder.streamLineOn(state, line)

        fun close(status: String = "ok") = AgentRunRecorder.closeRun(state.runId, status)
    }

    /**
     * Hand the recorder its storage root. Called once from Application.onCreate
     * (mirrors AppLogger.primeContext). Without this the recorder is a no-op —
     * which is what we want in the ACRA reporter process.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        refreshBaseDir()
    }

    /**
     * [T-android-seasons] Resolve the runs root for the ACTIVE season.
     *
     * Caching a single directory was fine while there was exactly one
     * namespace; with seasons it would silently write an isolated season's
     * telemetry into the main season's log — the precise leak seasons exist to
     * prevent. Re-resolved on every run start instead (cheap: one File + mkdirs).
     */
    private fun refreshBaseDir(): File? {
        val ctx = appContext ?: return baseDir
        return runCatching {
            val dir = File(com.openminis.app.data.SeasonStore.activeGlobalBase(ctx), "runs")
            if (!dir.exists()) dir.mkdirs()
            baseDir = dir
            dir
        }.getOrNull() ?: baseDir
    }

    /** True when the recorder can persist. */
    val isReady: Boolean get() = baseDir != null

    /**
     * Open a run. [parentRunId] links a subagent run to the run that spawned
     * it — that link is what turns a flat log into a tree.
     */
    fun openRun(
        sessionId: String,
        source: String = "chat",
        parentRunId: String? = null,
    ): Handle? {
        val root = refreshBaseDir() ?: return null
        return runCatching {
            val now = System.currentTimeMillis()
            val id = "r_" + now.toString(36) + "_" + UUID.randomUUID().toString().take(4)
            val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val dir = File(root, safeSession).apply { mkdirs() }
            val depth = if (parentRunId == null) 0 else (runs[parentRunId]?.depth ?: 0) + 1
            val state = RunState(
                runId = id,
                sessionId = sessionId,
                parentRunId = parentRunId,
                file = File(dir, "$id.jsonl"),
                startedAt = now,
                depth = depth,
            )
            runs[id] = state
            writeLine(state, JSONObject().put("kind", "run_start").put("label", source), now)
            publishUi()
            Handle(state)
        }.getOrElse {
            Log.w(TAG, "openRun failed: ${it.message}")
            null
        }
    }

    /** Close a run by id (idempotent). */
    fun closeRun(runId: String, status: String = "ok") {
        val state = runs[runId] ?: return
        synchronized(state.lock) {
            if (state.closed) return
            state.closed = true
            state.status = status
            runCatching {
                writeLine(state, JSONObject().put("kind", "run_end").put("status", status), System.currentTimeMillis())
            }
        }
        runs.remove(runId)
        publishUi()
        pruneOldRuns()
    }

    /**
     * Called by the UI once per second. Closes runs that have gone quiet, so
     * every JSONL ends with a `run_end` even when the agent loop exits through
     * one of its many paths (cancel, provider error, model timeout) instead of
     * a single clean finally-block. Avoids a coroutine per run.
     */
    fun tick() {
        val now = System.currentTimeMillis()
        runs.values.toList().forEach { state ->
            if (now - state.lastEventAt > IDLE_CLOSE_MS) closeRun(state.runId, "idle")
        }
    }

    /** Raw JSONL of one run. */
    fun readRun(sessionId: String, runId: String): String? = runCatching {
        val root = baseDir ?: return null
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        File(File(root, safeSession), "$runId.jsonl").takeIf { it.exists() }?.readText()
    }.getOrNull()

    /** Newest-first run files. */
    fun recentRunFiles(limit: Int = 10): List<File> = runCatching {
        val root = baseDir ?: return emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------- run events

    private fun noteOn(
        state: RunState,
        kind: String,
        tool: String?,
        label: String?,
        status: String?,
        digest: String?,
        fields: Map<String, Any?>?,
    ) {
        if (state.closed) return
        synchronized(state.lock) {
            runCatching {
                val obj = JSONObject().put("kind", kind)
                tool?.let { obj.put("tool", it) }
                label?.let { obj.put("label", it.take(200)) }
                status?.let { obj.put("status", it) }
                digest?.let { obj.put("digest", it.take(DIGEST_CHARS)) }
                fields?.forEach { (k, v) -> if (v != null) obj.put(k, v) }
                val now = System.currentTimeMillis()
                writeLine(state, obj, now)
                addStep(state, kind, tool, label, status ?: "ok", now, null, null)
                publishUi()
            }
        }
    }

    private fun beginCallOn(state: RunState, tool: String, digest: String?): String? {
        if (state.closed) return null
        synchronized(state.lock) {
            val now = System.currentTimeMillis()
            val callId = "c_" + (++state.callSeq)
            state.activeCallId = callId
            state.activeTool = tool
            state.activeSince = now
            state.toolCount += 1
            state.streamTail.setLength(0)
            state.lastStreamPersistAt = 0L
            runCatching {
                val obj = JSONObject()
                    .put("kind", "tool_call_start")
                    .put("tool", tool)
                    .put("callId", callId)
                digest?.let { obj.put("digest", it.take(DIGEST_CHARS)) }
                writeLine(state, obj, now)
                addStep(state, "tool_call_start", tool, digest, "running", now, null, null)
                publishUi()
            }
            return callId
        }
    }

    private fun endCallOn(
        state: RunState,
        tool: String,
        callId: String?,
        status: String,
        durationMs: Long,
        exitCode: Int?,
        bytes: Int?,
        error: String?,
    ) {
        if (state.closed) return
        synchronized(state.lock) {
            runCatching {
                val now = System.currentTimeMillis()
                val obj = JSONObject()
                    .put("kind", "tool_call_end")
                    .put("tool", tool)
                    .put("status", status)
                    .put("durationMs", durationMs)
                callId?.let { obj.put("callId", it) }
                exitCode?.let { obj.put("exitCode", it) }
                bytes?.let { obj.put("bytes", it) }
                error?.let { obj.put("error", it.take(DIGEST_CHARS)) }
                writeLine(state, obj, now)
                addStep(state, "tool_call_end", tool, null, status, now, durationMs, exitCode)
                state.activeCallId = null
                state.activeTool = null
                state.activeSince = 0L
                state.lastTool = tool
                state.lastToolMs = durationMs
                publishUi()
            }
        }
    }

    /**
     * Pillar A2 — live output tap. The in-memory tail always updates (cheap);
     * the JSONL record and the UI flow are throttled so a chatty build neither
     * writes thousands of lines nor re-renders the chip per line.
     */
    private fun streamLineOn(state: RunState, line: String) {
        if (state.closed) return
        synchronized(state.lock) {
            if (state.streamTail.length > STREAM_TAIL_CHARS) {
                state.streamTail.delete(0, state.streamTail.length - STREAM_TAIL_CHARS)
            }
            state.streamTail.append(line).append('\n')
            val now = System.currentTimeMillis()
            if (now - state.lastStreamPersistAt >= STREAM_PERSIST_INTERVAL_MS) {
                state.lastStreamPersistAt = now
                runCatching {
                    writeLine(
                        state,
                        JSONObject()
                            .put("kind", "tool_stream")
                            .put("tool", state.activeTool ?: "shell")
                            .put("stream", line.take(400)),
                        now,
                    )
                }
            }
            if (now - state.lastUiPublishAt >= UI_PUBLISH_INTERVAL_MS) publishUi()
        }
    }

    /** Test/debug hook: forget all in-memory state (does not touch disk). */
    fun resetForTest() {
        runs.clear()
        _activeRuns.value = emptyList()
    }

    // ---------------------------------------------------------------- internals

    private fun writeLine(state: RunState, obj: JSONObject, now: Long) {
        state.lastEventAt = now
        obj.put("seq", ++state.seq)
        obj.put("ts", now)
        obj.put("runId", state.runId)
        obj.put("sessionId", state.sessionId)
        state.parentRunId?.let { obj.put("parentRunId", it) }
        state.file.appendText(obj.toString() + "\n")
    }

    private fun addStep(
        state: RunState,
        kind: String,
        tool: String?,
        label: String?,
        status: String,
        now: Long,
        durationMs: Long?,
        exitCode: Int?,
    ) {
        state.steps.add(Step(state.seq, kind, tool, label?.take(80), status, now, durationMs, exitCode))
        while (state.steps.size > MAX_STEPS_IN_MEMORY) state.steps.removeAt(0)
    }

    private fun publishUi() {
        val snaps = runs.values.map { s ->
            RunInfo(
                runId = s.runId,
                shortId = s.runId.substringAfterLast('_'),
                sessionId = s.sessionId,
                parentRunId = s.parentRunId,
                startedAt = s.startedAt,
                lastEventAt = s.lastEventAt,
                toolCount = s.toolCount,
                activeTool = s.activeTool,
                activeSince = s.activeSince,
                lastTool = s.lastTool,
                lastToolMs = s.lastToolMs,
                status = s.status,
                depth = s.depth,
            )
        }.sortedBy { it.startedAt }
        runs.values.forEach { it.lastUiPublishAt = System.currentTimeMillis() }
        _activeRuns.value = snaps
    }

    /** Keep the newest [MAX_RUN_FILES] run files; drop the rest (oldest first). */
    private fun pruneOldRuns() {
        runCatching {
            val root = baseDir ?: return
            val files = root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jsonl") }
                .sortedByDescending { it.lastModified() }
                .toList()
            files.drop(MAX_RUN_FILES).forEach { it.delete() }
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
            }
        }
    }
}
