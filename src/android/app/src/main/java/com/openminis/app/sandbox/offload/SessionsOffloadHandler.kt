package com.openminis.app.sandbox.offload

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T188 — `minis-sessions-cli` offload handler. Lets the in-shell agent
 * query historical chat sessions and messages without round-tripping
 * back through the LLM. Three subcommands:
 *
 *   list       List recent sessions, optionally filtered by id, keyword,
 *              and date range. Default if no subcommand is given.
 *   search     Search message content across sessions; --keywords
 *              required.
 *   messages   Read a paginated transcript of one session; --id required.
 *
 * Mirrors iOS canonical CLI surface: argv shape, JSON envelope, exit
 * codes, error messages all line up. The on-device db (Room) is the
 * same shape as iOS (sqlite tables `sessions`/`messages` with matching
 * column names), so query semantics translate one-to-one.
 *
 * Output flags `--compact` / `-q` / `--quiet` are honored uniformly via
 * [OffloadOutput.formatBody], same as every other android-* / minis-*
 * tool.
 */
class SessionsOffloadHandler(
    private val repo: ChatRepository,
    // [T-android-sessions-export] Application context for resolving the
    // caller session's own host dirs (sessionScopedHostFile) — the same
    // pattern ModelUseOffloadHandler uses for --output writes.
    private val context: Context,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name ("minis-sessions-cli"); subcommand
        // and options follow. Drop argv[0] before parsing so positional[0]
        // is the subcommand name. `full` is declared boolean so
        // `--full <token>` never greedily consumes the next token as a value
        // (e.g. `--full` placed before the subcommand).
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("full"))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: "list"
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                "export" -> cmdExport(args, request)
                else -> {
                    val err = errorEnvelope(
                        sub,
                        "INVALID_ARGS",
                        "Unknown command '$sub'. Valid: list, search, messages, export. " +
                            "Use --help for details.",
                    )
                    NativeOffloadResult(
                        EXIT_INVALID_ARGS,
                        OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                    )
                }
            }
        } catch (e: Throwable) {
            // Last-ditch: any unexpected exception (sql parse error, OOM,
            // …) becomes an INTERNAL envelope rather than a crash so the
            // shell stays alive and the agent can read the failure.
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val err = errorEnvelope(sub, "INTERNAL", e.message ?: "unknown error")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val ids = parseIds(args)
        val kws = parseKeywords(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val metas = runBlocking { repo.querySessionsMeta(ids, kws, limit, startMs, endMs) }

        val sessions = JSONArray()
        for (m in metas) {
            val s = JSONObject()
                .put("session_id", m.id)
                .put("started_at", DATE_FMT.format(Date(m.startedAt)))
                .put("last_active", DATE_FMT.format(Date(m.lastActive)))
                .put("message_count", m.messageCount)
            // Optional fields — only emit when non-null so the JSON
            // matches iOS's `(optional)` shape rather than carrying
            // null literals through to the agent prompt.
            m.title?.let { if (it.isNotBlank()) s.put("title", it) }
            m.preview?.let { if (it.isNotBlank()) s.put("preview", it) }
            m.source?.let { if (it.isNotBlank()) s.put("source", it) }
            sessions.put(s)
        }
        val data = JSONObject()
            .put("count", sessions.length())
            .put("sessions", sessions)
        return emit("list", data, args)
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val kws = parseKeywords(args).orEmpty()
        if (kws.isEmpty()) {
            val err = errorEnvelope(
                "search",
                "INVALID_ARGS",
                "--keywords is required for search. " +
                    "Example: minis-sessions-cli search --keywords \"API error\"",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val ids = parseIds(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val matches = runBlocking { repo.searchMessages(ids, kws, limit, startMs, endMs) }

        val msgs = JSONArray()
        for (m in matches) {
            msgs.put(
                JSONObject()
                    .put("session_id", m.sessionId)
                    .put("message_id", m.messageId)
                    .put("role", m.role)
                    .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                    .put("snippet", m.snippet),
            )
        }
        val data = JSONObject()
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("search", data, args)
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope(
                    "messages",
                    "INVALID_ARGS",
                    "--id <session_id> is required. Use 'list' first to find session IDs.",
                )
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val limit = parseLimit(args)
        val offset = (args.getInt("offset") ?: 0).coerceAtLeast(0)
        // [T-android-sessions-cli-full] --full lifts the per-message text cap
        // from the default 600 to 50000 chars (iOS parity). Previously the flag
        // was silently ignored — every message stayed truncated at 600 no
        // matter what the caller passed, gutting transcript exports.
        val full = args.hasFlag("full")
        val maxChars = if (full) ChatRepository.MESSAGE_TEXT_MAX_FULL else ChatRepository.MESSAGE_TEXT_MAX
        // [T-android-sessions-cli-messages-daterange] GH#200 (iOS 8f3189a73).
        // HELP_TEXT documents --start / --end and `list` / `search` honour
        // them, but `messages` parsed neither — the CLI accepted the flags and
        // silently returned the whole session, which reads as the filter
        // working and matching everything.
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))

        val (page, total) = runBlocking {
            // Single coroutine block so the two reads see a consistent
            // snapshot — ordering matters less than ensuring we don't
            // surface a `total` that disagrees with the slice we
            // returned (e.g. another session interleaving inserts mid-
            // call). Room serializes via the suspending dispatcher so
            // these run sequentially in the same coroutine.
            // Both reads take the SAME date range on purpose: an unfiltered
            // count next to a filtered page would make `total` describe the
            // whole session while the slice covers only the matches, so
            // `hasMore` would lie. iOS 8f3189a73 calls this out explicitly.
            repo.loadMessagePage(sessionId, offset, limit, maxChars, startMs, endMs) to
                repo.messageCountInRange(sessionId, startMs, endMs)
        }

        val msgs = JSONArray()
        for (m in page) {
            val obj = JSONObject()
                .put("message_id", m.messageId)
                .put("role", m.role)
                .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                .put("text", m.text)
            // Only present when the stored text exceeded the cap, so normal
            // messages serialize byte-identically to before (iOS parity).
            if (m.truncated) obj.put("truncated", true)
            msgs.put(obj)
        }
        val data = JSONObject()
            .put("session_id", sessionId)
            .put("offset", offset)
            .put("limit", limit)
            .put("full", full)
            .put("max_chars", maxChars)
            .put("total", total)
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("messages", data, args)
    }

    /**
     * [T-android-sessions-export] `minis-sessions-cli export` — stream a
     * session's full structured trajectory (raw parts_json per message:
     * text parts, toolUse calls with model-supplied args, toolResult
     * outputs with success flags, reasoning) to a file under /var/minis/.
     *
     * Why a file and not stdout: the offload reply socket caps a single
     * result string at 1 MiB (NativeOffload.writeFrame), while one long
     * agent session's parts_json can run tens of MB. The handler writes
     * straight to the guest-visible filesystem (same pattern as
     * minis-model-use --output) and returns a small summary envelope.
     *
     * Output shape — JSONL, one JSON object per line:
     *   {"message_id","role","created_at"(UTC ISO),"sort_order","model_id",
     *    "parts":[...]}     // parts = parsed parts_json array, verbatim
     *
     * The `parts` array preserves the on-disk part shapes: {type:"text",
     * value}, {type:"toolUse", value:{toolUseId,name,input,...}},
     * {type:"toolResult", value:{toolUseId,name,output,success,...}},
     * {type:"mediaRef",...}, {type:"thinking",...}. That makes each
     * session export a lossless training/eval artifact: (state → action →
     * observation) tuples are recoverable by pairing toolUse.toolUseId
     * with toolResult.toolUseId, in sort_order sequence.
     *
     * Two output formats:
     *   --format jsonl  (default) one message per line, as above
     *   --format meta   session header + message metas only (id/role/
     *                  created_at/sort_order/model_id + per-message part
     *                  type counts) — cheap full-index scans
     *
     * Redaction is NOT done here — the export is lossless by design.
     * Scrubbing secrets is the consumer's job (a redaction pass belongs
     * to the dataset pipeline that ingests these files, stage 1, run
     * inside the sandbox before anything leaves the device).
     */
    private fun cmdExport(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: return exportError(
                "export",
                "INVALID_ARGS",
                "--id <session_id> is required. Use 'list' first to find session IDs.",
                args,
            )
        val format = (args.get("format") ?: "jsonl").lowercase()
        if (format != "jsonl" && format != "meta") {
            return exportError(
                "export",
                "INVALID_ARGS",
                "--format must be 'jsonl' or 'meta' (got '$format').",
                args,
            )
        }
        // Absolute guest paths only — mirrors minis-model-use's
        // invalid_output_path handling. A relative path would land in
        // the rootfs root where the next sandbox reset wipes it.
        val outPath = args.get("out")?.takeIf { it.isNotBlank() }
            ?: return exportError(
                "export",
                "INVALID_ARGS",
                "--out <absolute_path> is required (e.g. /var/minis/workspace/traj.jsonl).",
                args,
            )
        if (!outPath.startsWith("/var/minis/")) {
            return exportError(
                "export",
                "INVALID_ARGS",
                "--out must be an absolute path under /var/minis/ (workspace|shared|offloads|attachments). " +
                    "Got '$outPath'.",
                args,
            )
        }

        val total = runBlocking { repo.messageCount(sessionId) }
        if (total == 0) {
            return exportError(
                "export",
                "NOT_FOUND",
                "Session '$sessionId' has 0 messages (unknown id, or empty session).",
                args,
            )
        }

        // Resolve guest path → host path. Prefer the caller session's own
        // scoped dir (attachments/offloads/workspace/browser) so a stale
        // global mount can't redirect the write into another session's
        // tree; fall back to the global resolver (covers /var/minis/shared,
        // /var/minis/memory, /var/minis/projects which are global mounts).
        val hostFile = sessionScopedHostFile(outPath, request.sessionId)
            ?: PRootKernel.resolveHostPath(outPath)
            ?: return exportError(
                "export",
                "INTERNAL",
                "Cannot resolve --out '$outPath' to a host path.",
                args,
            )
        hostFile.parentFile?.mkdirs()

        val written = runBlocking {
            exportStreamToFile(repo, sessionId, format, hostFile)
        }

        // Ops summary for the agent — small enough for the reply socket.
        return emit(
            "export",
            JSONObject()
                .put("session_id", sessionId)
                .put("format", format)
                .put("path", outPath)
                .put("host_bytes", hostFile.length())
                .put("messages", written)
                .put("total", total),
            args,
        )
    }

    /** Resolve a /var/minis/<sub>/... guest path to the caller session's own
     *  host dir (bypasses the last-writer-wins global bind-mount map).
     *  Same logic as ModelUseOffloadHandler.sessionScopedHostFile. */
    private fun sessionScopedHostFile(linuxPath: String, sessionId: String?): File? {
        if (sessionId == null) return null
        val m = Regex("^/var/minis/(attachments|offloads|workspace|browser)(/.*)?$").find(linuxPath)
            ?: return null
        val sub = m.groupValues[1]
        val rest = m.groupValues[2].removePrefix("/")
        val base = File(context.filesDir, "minis-sessions/$sessionId/$sub")
        return if (rest.isEmpty()) base else File(base, rest)
    }

    /** Streaming core: page through the session in [EXPORT_BATCH] batches and
     *  write one JSON object per line. Batching keeps a long session from
     *  materializing all parts_json payloads in memory at once (the same
     *  reason ChatExporter paginates at 200). */
    private suspend fun exportStreamToFile(
        repo: ChatRepository,
        sessionId: String,
        format: String,
        hostFile: File,
    ): Int {
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        var written = 0
        OutputStreamWriter(FileOutputStream(hostFile), Charsets.UTF_8).use { raw ->
            BufferedWriter(raw, EXPORT_BUFFER).use { w ->
                var offset = 0
                while (true) {
                    val batch = repo.loadMessagePageRaw(sessionId, offset, EXPORT_BATCH)
                    if (batch.isEmpty()) break
                    for (e in batch) {
                        when (format) {
                            "meta" -> {
                                val partCounts = countPartTypes(e.partsJson)
                                val obj = JSONObject()
                                    .put("message_id", e.id)
                                    .put("role", e.role)
                                    .put("created_at", isoFmt.format(Date(e.createdAt)))
                                    .put("sort_order", e.sortOrder)
                                if (e.modelId != null) obj.put("model_id", e.modelId)
                                obj.put("part_types", partCounts)
                                w.write(obj.toString())
                                w.write("\n")
                            }
                            else -> {
                                // jsonl: parse parts_json once so the line is
                                // guaranteed valid JSON. Bad legacy rows get a
                                // text part with the raw payload instead of
                                // breaking the whole export.
                                val partsArr: JSONArray = try {
                                    JSONArray(e.partsJson)
                                } catch (bad: Exception) {
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("value", e.partsJson)
                                            .put("unparseable", true),
                                    )
                                }
                                val obj = JSONObject()
                                    .put("message_id", e.id)
                                    .put("role", e.role)
                                    .put("created_at", isoFmt.format(Date(e.createdAt)))
                                    .put("sort_order", e.sortOrder)
                                if (e.modelId != null) obj.put("model_id", e.modelId)
                                if (e.reasoningContent != null) obj.put("reasoning_content", e.reasoningContent)
                                obj.put("parts", partsArr)
                                w.write(obj.toString())
                                w.write("\n")
                            }
                        }
                        written++
                    }
                    offset += batch.size
                }
            }
        }
        return written
    }

    /** Count part types in a parts_json payload without materializing more
     *  than the type strings. meta format only. */
    private fun countPartTypes(partsJson: String): JSONObject {
        val counts = JSONObject()
        try {
            val arr = JSONArray(partsJson)
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optString("type") ?: "?"
                counts.put(t, counts.optInt(t) + 1)
            }
        } catch (_: Exception) {
            counts.put("unparseable", 1)
        }
        return counts
    }

    private fun exportError(action: String, code: String, msg: String, args: OffloadArgs): NativeOffloadResult {
        val err = errorEnvelope(action, code, msg)
        return NativeOffloadResult(
            if (code == "NOT_FOUND") 1 else EXIT_INVALID_ARGS,
            OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
        )
    }


    // ─── arg parsing helpers ──────────────────────────────────────────

    private fun parseIds(args: OffloadArgs): List<String>? {
        val raw = args.get("ids") ?: return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseKeywords(args: OffloadArgs): List<String>? {
        val raw = args.get("keywords") ?: return null
        // Split on any whitespace (the user may quote multi-word keywords
        // at the shell, in which case we get one big token containing a
        // space — collapse runs of whitespace into a single delimiter).
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.isLenient = false
            sdf.parse(raw)?.time
        }.getOrNull()
    }

    /**
     * Inclusive end-of-day: `--end 2025-03-31` includes everything up to
     * and including 2025-03-31 23:59:59.999. Adding (1 day - 1 ms) is
     * safer than (24h - 1ms) across DST transitions on devices that
     * don't run UTC. Mirrors iOS SessionsOffload.m L141-145.
     */
    private fun parseEndDate(raw: String?): Long? {
        val ms = parseDate(raw) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - 1
    }

    private fun parseLimit(args: OffloadArgs): Int {
        val raw = args.getInt("limit") ?: return DEFAULT_LIMIT
        return raw.coerceIn(1, MAX_LIMIT)
    }

    // ─── envelope helpers ─────────────────────────────────────────────

    private fun emit(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }

    private fun errorEnvelope(action: String, code: String, msg: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", JSONObject().put("error", code).put("message", msg))
    }

    companion object {
        private const val TAG = "SessionsOffload"
        private const val TOOL_NAME = "minis-sessions-cli"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100

        // [T-android-sessions-export] Streaming exports paginate at the
        // ChatExporter page size — keeps parts_json batches small in
        // memory while amortizing the DAO round-trip.
        private const val EXPORT_BATCH = 200
        private const val EXPORT_BUFFER = 256 * 1024

        // iOS NOFF_EXIT_INVALID_ARGS — the shell convention is exit 2
        // for invalid CLI args, distinct from exit 1 for runtime errors.
        private const val EXIT_INVALID_ARGS = 2

        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        private const val HELP_TEXT = """minis-sessions-cli - Query historical chat sessions and messages

USAGE:
  minis-sessions-cli <command> [options]

COMMANDS:
  list      List recent sessions (default: 50, max: 100)
  search    Search message content across sessions (requires --keywords)
  messages  Read messages from a specific session (requires --id)
  export    Write a session's full structured trajectory to a file
            (requires --id and --out; lossless toolUse/toolResult parts)

OPTIONS:
  --keywords <words>    Space-separated keywords (AND logic, required for search)
  --ids <id1,id2,...>   Filter by comma-separated session IDs (list/search)
  --id <session_id>     Session ID to read messages from (messages/export)
  --full                (messages only) Return full message text up to 50000 chars
  --offset <n>          Skip first n messages, 0-based (default: 0)
  --start <YYYY-MM-DD>  Filter results after this date (inclusive)
  --end <YYYY-MM-DD>    Filter results before this date (inclusive, end of day)
  --limit <n>           Max results (default: 50, max: 100)
  --format <jsonl|meta> (export only) jsonl = one message per line with full
                        parts array; meta = message metas + part-type counts
                        only (cheap full-index scans). Default: jsonl
  --out <path>          (export only) Absolute output path under /var/minis/
                        (workspace|shared|offloads|attachments). Required.
  --help, -h            Show this help message
  --compact             Minimize JSON output
  -q, --quiet           Output only data field

OUTPUT (list):
  Each session includes: session_id, title, preview (first user message,
  60 chars), source, started_at, last_active, message_count.

OUTPUT (search):
  Each message includes: session_id, message_id, role, created_at,
  snippet (up to 600 chars centered on keyword match).

OUTPUT (messages):
  Each message includes: message_id, role, created_at, text (up to 600 chars
  by default; up to 50000 with --full). Messages longer than the cap carry
  "truncated": true. Response also includes: session_id, offset, limit, full,
  max_chars, total (total message count).

OUTPUT (export):
  Returns a summary: session_id, format, path, host_bytes, messages (lines
  written), total. The file itself is JSONL — one JSON object per line:
  {"message_id","role","created_at" (UTC ISO),"sort_order","model_id",
  "parts":[...]} where parts preserves the on-disk shapes (text / toolUse
  with model-supplied input args / toolResult with output+success /
  mediaRef / thinking). Pair toolUse.toolUseId with toolResult.toolUseId
  and sort_order gives lossless (state -> action -> observation) tuples.
  NOTE: export is lossless and NOT redacted — scrub secrets in a pipeline
  stage before any data leaves the device.

WORKFLOW:
  1. Use 'list' or 'list --keywords <topic>' to find relevant sessions
  2. Use 'search --keywords <terms>' to find specific messages
  3. Use 'messages --id <session_id>' to read a conversation
  4. Use --offset to paginate through long conversations
  5. Use 'export --id <session_id> --out /var/minis/workspace/t.jsonl' to
     pull the full structured trajectory for dataset/eval work

EXAMPLES:
  minis-sessions-cli list
  minis-sessions-cli list --limit 10
  minis-sessions-cli list --keywords python flask
  minis-sessions-cli list --start 2025-01-01 --end 2025-03-31
  minis-sessions-cli search --keywords "API error" --limit 20
  minis-sessions-cli search --keywords deploy --ids abc123,def456
  minis-sessions-cli messages --id <session_id>
  minis-sessions-cli messages --id <session_id> --full
  minis-sessions-cli messages --id <session_id> --offset 20 --limit 10
  minis-sessions-cli export --id <session_id> --out /var/minis/workspace/s1.jsonl
  minis-sessions-cli export --id <session_id> --format meta --out /var/minis/shared/idx.jsonl
"""
    }
}
