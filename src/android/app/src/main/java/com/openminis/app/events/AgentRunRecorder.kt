package com.openminis.app.events

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [T-android-run-recorder] Pillar A1 — the agent run event bus.
 *
 * Why this exists: before vc57 the agent had no machine-readable record of
 * what it did. Every tool call returned a flat string, and nothing measured
 * duration, exit codes, retry counts or partial output. The user could see
 * tool cards in the UI; the AGENT could see nothing, and neither side could
 * answer "how long did step 3 take, what did it cost, and what did it print
 * before it died".
 *
 * This object is deliberately dumb and dependency-free:
 *
 *  - one append-only JSONL file per run: `files/minis-global/runs/<session>/<runId>.jsonl`
 *    (crash-safe: a half-written last line is still readable, and the file can
 *    be tailed/filtered from inside the sandbox — the agent reads its own log)
 *  - a bounded in-memory [Snapshot] StateFlow for the UI HUD chip
 *  - a bounded stream tail per active call (first slice of Pillar A2: live
 *    output visibility for long commands, which used to be a black box)
 *
 * Contract: NEVER throws into the caller, never blocks on I/O longer than a
 * small append, and degrades to a no-op when the app context was never primed
 * (e.g. the :acra reporter process).
 *
 * Schema (one JSON object per line):
 *   {seq, ts, runId, sessionId, kind, tool?, callId?, status?, durationMs?,
 *    exitCode?, bytes?, label?, digest?, stream?, error?}
 *
 * `kind` enum v1 — run_start, run_end, tool_call_start, tool_call_end,
 * tool_stream, tool_note, error.
 */
object AgentRunRecorder {

    private const val TAG = "AgentRunRecorder"

    /** Steps kept in the in-memory snapshot (UI needs a tail, not a history). */
    private const val MAX_STEPS_IN_MEMORY = 200

    /** Rolling local stdin/stdout tail shown in the HUD for the active call. */
    private const val STREAM_TAIL_CHARS = 4096

    /** A run with no events for this long is closed lazily on the next event. */
    private const val IDLE_CLOSE_MS = 180_000L

    /** Retention: keep the newest N run files, then LRU by age. */
    private const val MAX_RUN_FILES = 60

    /** Minimum gap between persisted `tool_stream` lines (in-memory tail is not throttled). */
    private const val STREAM_PERSIST_INTERVAL_MS = 500L

    /** Bounded digest of tool arguments kept in the log (secrets are redacted by the caller). */
    private const val DIGEST_CHARS = 240

    private val lock = Any()

    @Volatile
    private var baseDir: File? = null

    @Volatile
    private var currentRunFile: File? = null

    @Volatile
    private var runId: String? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var runStartedAt = 0L

    @Volatile
    private var lastEventAt = 0L

    @Volatile
    private var seq = 0

    @Volatile
    private var lastStreamPersistAt = 0L

    /** Set while a tool call is in flight; read by the shell stream tap. */
    @Volatile
    var currentCallId: String? = null
        private set

    @Volatile
    var currentToolName: String? = null
        private set

    private val steps = ArrayList<Step>(64)
    private val streamTail = StringBuilder()

    /** Immutable view of the active run for the UI. */
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

    data class Snapshot(
        val runId: String,
        val sessionId: String,
        val startedAt: Long,
        val lastEventAt: Long,
        val steps: List<Step>,
        val toolCount: Int,
        val activeTool: String?,
        val activeSince: Long,
        val streamTail: String,
        val status: String,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> get() = _snapshot.asStateFlow()

    /**
     * Hand the recorder its storage root. Called once from Application.onCreate
     * (mirrors AppLogger.primeContext). Without this the recorder is a no-op —
     * which is exactly what we want in the ACRA reporter process.
     */
    fun prime(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "minis-global/runs")
            if (!dir.exists()) dir.mkdirs()
            baseDir = dir
        }.onFailure { Log.w(TAG, "prime failed: ${it.message}") }
    }

    /** True when the recorder can actually persist. */
    val isReady: Boolean get() = baseDir != null

    /**
     * Ensure a run exists for [sessionId] and return its id. Closes a stale run
     * (idle past [IDLE_CLOSE_MS], or a different session) first, so a run maps
     * to one contiguous piece of work rather than to the app's lifetime.
     */
    fun ensureRun(sessionId: String, source: String = "chat"): String? {
        if (baseDir == null) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val active = runId
            val stale = active != null &&
                (this.sessionId != sessionId || now - lastEventAt > IDLE_CLOSE_MS)
            if (active != null && !stale) return active
            if (active != null) closeRunLocked("superseded", now)
            return beginRunLocked(sessionId, source, now)
        }
    }

    private fun beginRunLocked(sessionId: String, source: String, now: Long): String? {
        val root = baseDir ?: return null
        return runCatching {
            val id = "r_" + now.toString(36) + "_" + UUID.randomUUID().toString().take(4)
            val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val dir = File(root, safeSession).apply { mkdirs() }
            val file = File(dir, "$id.jsonl")
            runId = id
            this.sessionId = sessionId
            runStartedAt = now
            lastEventAt = now
            seq = 0
            steps.clear()
            streamTail.setLength(0)
            currentCallId = null
            currentToolName = null
            currentRunFile = file
            writeLineLocked(
                JSONObject()
                    .put("kind", "run_start")
                    .put("label", source),
                now,
            )
            publishLocked("running")
            id
        }.getOrElse {
            Log.w(TAG, "beginRun failed: ${it.message}")
            null
        }
    }

    /** Close the active run (idempotent). */
    fun closeRun(status: String = "ok") {
        if (runId == null) return
        synchronized(lock) { closeRunLocked(status, System.currentTimeMillis()) }
    }

    private fun closeRunLocked(status: String, now: Long) {
        runCatching {
            writeLineLocked(JSONObject().put("kind", "run_end").put("status", status), now)
            publishLocked(status)
        }
        currentRunFile = null
        runId = null
        sessionId = null
        currentCallId = null
        currentToolName = null
        _snapshot.value = null
        pruneOldRunsLocked()
    }

    /**
     * Log a discrete step. [digest] should already be redacted by the caller.
     */
    fun note(
        kind: String,
        tool: String? = null,
        label: String? = null,
        status: String? = null,
        digest: String? = null,
        fields: Map<String, Any?>? = null,
    ) {
        if (runId == null) return
        synchronized(lock) {
            runCatching {
                val obj = JSONObject().put("kind", kind)
                tool?.let { obj.put("tool", it) }
                label?.let { obj.put("label", it.take(200)) }
                status?.let { obj.put("status", it) }
                digest?.let { obj.put("digest", it.take(DIGEST_CHARS)) }
                fields?.forEach { (k, v) -> if (v != null) obj.put(k, v) }
                val now = System.currentTimeMillis()
                writeLineLocked(obj, now)
                addStepLocked(kind, tool, label, status ?: "ok", now, null, null)
                publishLocked("running")
            }
        }
    }

    /** Mark the start of a tool call. Returns the call id (also in [currentCallId]). */
    fun beginCall(tool: String, digest: String?): String? {
        if (runId == null) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val callId = "c_" + (++seq)
            currentCallId = callId
            currentToolName = tool
            streamTail.setLength(0)
            lastStreamPersistAt = 0L
            runCatching {
                val obj = JSONObject()
                    .put("kind", "tool_call_start")
                    .put("tool", tool)
                    .put("callId", callId)
                digest?.let { obj.put("digest", it.take(DIGEST_CHARS)) }
                writeLineLocked(obj, now)
                addStepLocked("tool_call_start", tool, digest, "running", now, null, null)
                publishLocked("running")
            }
            return callId
        }
    }

    /** Mark the end of a tool call. */
    fun endCall(
        tool: String,
        callId: String?,
        status: String,
        durationMs: Long,
        exitCode: Int? = null,
        bytes: Int? = null,
        error: String? = null,
    ) {
        synchronized(lock) {
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
                writeLineLocked(obj, now)
                addStepLocked("tool_call_end", tool, null, status, now, durationMs, exitCode)
                currentCallId = null
                currentToolName = null
                publishLocked("running")
            }
        }
    }

    /**
     * Pillar A2 — live output tap. Called for every line of shell output.
     * The in-memory tail is always updated (cheap); the JSONL record is
     * throttled so a chatty build doesn't write thousands of lines.
     */
    fun streamLine(line: String) {
        if (runId == null) return
        synchronized(lock) {
            if (streamTail.length > STREAM_TAIL_CHARS) {
                streamTail.delete(0, streamTail.length - STREAM_TAIL_CHARS)
            }
            streamTail.append(line).append('\n')
            val now = System.currentTimeMillis()
            if (now - lastStreamPersistAt >= STREAM_PERSIST_INTERVAL_MS) {
                lastStreamPersistAt = now
                runCatching {
                    writeLineLocked(
                        JSONObject()
                            .put("kind", "tool_stream")
                            .put("tool", currentToolName ?: "shell")
                            .put("stream", line.take(400)),
                        now,
                    )
                }
            }
            publishLocked("running")
        }
    }

    /** Current rolling output tail of the active call (for the HUD). */
    fun currentStreamTail(): String = synchronized(lock) { streamTail.toString() }

    /**
     * Called by the UI once per second while the HUD is visible. Closes a run
     * that has gone quiet, so the JSONL always ends with a `run_end` even when
     * the loop exits through one of its many paths — the alternative was a
     * coroutine per run, which this recorder deliberately avoids.
     */
    fun tick() {
        val rid = runId ?: return
        if (System.currentTimeMillis() - lastEventAt > IDLE_CLOSE_MS) {
            synchronized(lock) {
                if (runId == rid) closeRunLocked("idle", System.currentTimeMillis())
            }
        }
    }

    /** Raw JSONL of a run, by run id, for the agent/replay surface. */
    fun readRun(sessionId: String, runId: String): String? = runCatching {
        val root = baseDir ?: return null
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        File(File(root, safeSession), "$runId.jsonl").takeIf { it.exists() }?.readText()
    }.getOrNull()

    /** Newest-first run files (used by replay / pruning diagnostics). */
    fun recentRunFiles(limit: Int = 10): List<File> = runCatching {
        val root = baseDir ?: return emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- internals

    private fun writeLineLocked(obj: JSONObject, now: Long) {
        val rid = runId ?: return
        lastEventAt = now
        obj.put("seq", ++seq)
        obj.put("ts", now)
        obj.put("runId", rid)
        sessionId?.let { obj.put("sessionId", it) }
        currentRunFile?.appendText(obj.toString() + "\n")
    }

    private fun addStepLocked(
        kind: String,
        tool: String?,
        label: String?,
        status: String,
        now: Long,
        durationMs: Long?,
        exitCode: Int?,
    ) {
        steps.add(Step(seq, kind, tool, label?.take(80), status, now, durationMs, exitCode))
        while (steps.size > MAX_STEPS_IN_MEMORY) steps.removeAt(0)
    }

    private fun publishLocked(status: String) {
        val rid = runId ?: return
        val active = currentCallId
        _snapshot.value = Snapshot(
            runId = rid,
            sessionId = sessionId ?: "",
            startedAt = runStartedAt,
            lastEventAt = lastEventAt,
            steps = steps.takeLast(20),
            toolCount = steps.count { it.kind == "tool_call_start" },
            activeTool = if (active != null) currentToolName else null,
            activeSince = if (active != null) lastEventAt else 0L,
            streamTail = streamTail.toString(),
            status = status,
        )
    }

    /** Keep the newest [MAX_RUN_FILES] run files; drop the rest (oldest first). */
    private fun pruneOldRunsLocked() {
        runCatching {
            val root = baseDir ?: return
            val files = root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jsonl") }
                .sortedByDescending { it.lastModified() }
                .toList()
            files.drop(MAX_RUN_FILES).forEach { it.delete() }
            // Drop now-empty session dirs.
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
            }
        }
    }

    /** Test/debug hook: forget all in-memory state (does not touch disk). */
    fun resetForTest() {
        synchronized(lock) {
            runId = null
            sessionId = null
            currentRunFile = null
            currentCallId = null
            currentToolName = null
            steps.clear()
            streamTail.setLength(0)
            _snapshot.value = null
        }
    }
}
