package com.openminis.app.data.repository

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-dream-prune] Audit P0 #3 — Dream-style memory review pass (architecture
 * port from Jenny's dream_cycle/dream_review; no code copied, AGPL).
 *
 * Minis X memory only ever GROWS: daily logs accumulate task residue
 * (transient session state that outlives its purpose) and near-duplicate
 * facts across days. The Dream pass is the prune counterpart to
 * memory_write: a bounded, snapshot-protected, model-routed review that
 * makes the SMALLEST honest edit to each file.
 *
 * Design decisions (from the audit + house constraints):
 * - Snapshot BEFORE any mutation, best-effort (a failed snapshot does not
 *   block the run — Jenny's documented tradeoff — but is DISCLOSED in the
 *   result). Snapshots live in memory/snapshots/dream-<ts>/ and the
 *   previous pass's snapshot is deleted on success (keep-one rollback).
 * - Bounded input: MAX_REVIEW_LINES per file per pass, 20 entries max per
 *   run. Overflow is DISCLOSED to the model in the manifest so it prunes
 *   the head (newest-first files) and leaves the rest for the next pass —
 *   never silently truncates a review.
 * - Pure planner/parser here; the LLM decision runs through the normal
 *   provider path in ChatViewModel (single execution surface — the vc43
 *   lesson: no second dispatch impl to drift).
 * - Surgical edits only: the model returns a list of (action, file, match,
 *   replacement) operations. DELETE_FILE is refused for GLOBAL.md.
 * - No background timer on Android: the pass is manually invoked via
 *   /dream. The 2h auto-cycle would burn battery/data on a phone and the
 *   silence-watchdog (audit #4) is the correct home for scheduled work.
 */
object MemoryDreamPass {

    private const val TAG = "MemoryDreamPass"

    /** Lines reviewed per file per pass. Bounded disclosure: overflow is
     *  reported in the manifest, never silently dropped. */
    const val MAX_REVIEW_LINES = 200

    /** Entries per run (Jenny's budget shape: small honest passes beat
     *  one giant rewrite that exceeds the budget mid-flight — the livelock
     *  dream_review.py documents). */
    const val MAX_ENTRIES = 20

    /** Keep-one rollback: previous pass snapshot is removed on success. */
    private const val SNAPSHOT_DIR = "snapshots"

    data class FileManifest(
        val name: String,
        val lines: Int,
        val reviewedLines: Int,
        val bytes: Long,
        /** true when lines > reviewedLines — overflow disclosed to the model. */
        val truncated: Boolean,
        val content: String,
    )

    data class DreamResult(
        val filesConsidered: Int,
        val filesPlanned: Int,
        val operationsApplied: Int,
        val operationsRefused: List<String>,
        val snapshotPath: String?,
        val snapshotFailed: Boolean,
        val detail: String,
    )

    /** Build the review manifest: newest files first, content bounded to
     *  MAX_REVIEW_LINES, overflow flagged. Files with no content beyond
     *  the timestamp stub are skipped (nothing to prune). */
    fun buildManifest(memoryDir: File, includeGlobal: Boolean): List<FileManifest> {
        val files = memoryDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") && it.name != "SOUL.md" }
            ?.sortedByDescending { it.name } // yyyy-MM-dd.md sorts newest first
            ?: return emptyList()
        val out = mutableListOf<FileManifest>()
        for (f in files) {
            if (!includeGlobal && f.name == "GLOBAL.md") continue
            val raw = try { f.readText() } catch (_: Exception) { continue }
            if (raw.isBlank()) continue
            val lines = raw.lines()
            // Skip files that carry nothing prunable: timestamp comments + blank lines only
            val meaningful = lines.count { it.isNotBlank() && !it.trimStart().startsWith("<!--") }
            if (meaningful <= 1) continue
            val reviewed = lines.take(MAX_REVIEW_LINES)
            out.add(
                FileManifest(
                    name = f.name,
                    lines = lines.size,
                    reviewedLines = reviewed.size,
                    bytes = f.length(),
                    truncated = lines.size > reviewed.size,
                    content = reviewed.joinToString("\n"),
                ),
            )
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    /** Best-effort snapshot of every .md file (GLOBAL included) into
     *  memory/snapshots/dream-<ts>/. Returns the snapshot dir or null on
     *  failure — the run proceeds, safety net just absent that pass. */
    fun snapshot(memoryDir: File): File? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val snapDir = File(File(memoryDir, SNAPSHOT_DIR), "dream-$ts")
            snapDir.mkdirs()
            memoryDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.forEach { src ->
                    val dst = File(snapDir, src.name)
                    src.copyTo(dst, overwrite = true)
                }
            snapDir
        } catch (e: Exception) {
            Log.w(TAG, "snapshot failed (run proceeds without safety net): ${e.message}")
            null
        }
    }

    /** On success: keep exactly one rollback — delete any older snapshot
     *  dirs (all dream-* under snapshots/ except the newest). Never
     *  deletes anything else. Best-effort. */
    fun pruneOldSnapshots(memoryDir: File, keep: File?) {
        try {
            val snapRoot = File(memoryDir, SNAPSHOT_DIR)
            snapRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("dream-") }
                ?.sortedByDescending { it.name }
                ?.drop(if (keep != null) 1 else 0)
                ?.forEach { stale -> stale.deleteRecursively() }
        } catch (_: Exception) { }
    }

    /**
     * [T-dream-prune-noop-cleanup] A no-op pass (model proposed zero
     * prunes) mutates nothing — the snapshot taken at the start of that
     * run is pure disk waste with nothing to roll back. Remove THIS run's
     * snapshot only; older ones stay (they may be the rollback for a
     * previous pass that DID apply prunes — keep-one semantics).
     */
    fun cleanupNoop(memoryDir: File, thisRunSnapshot: File?) {
        try {
            thisRunSnapshot?.deleteRecursively()
        } catch (_: Exception) { }
    }

    /**
     * Parse + apply the model's plan. Expected plan format (one per line):
     *   KEEP <file> — recorded as a no-op decision
     *   PRUNE <file> :: <exact-line-or-block> => (DELETE | REPLACE: <new text>)
     *
     * PRUNE operations match EXACT text within the file (no regex, no
     * offsets — the model quotes what it means; quoting is unambiguous
     * and immune to line-count drift). DELETE removes the quote; REPLACE
     * substitutes it. Whitespace-trimmed matching, first occurrence only.
     *
     * Returns refused operations with reasons — never throws; a bad line
     * is skipped, disclosed in the result.
     */
    fun applyPlan(memoryDir: File, planLines: List<String>): Pair<Int, List<String>> {
        var applied = 0
        val refused = mutableListOf<String>()
        for (rawLine in planLines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("KEEP")) continue
            if (!line.startsWith("PRUNE")) {
                refused.add("unrecognized: ${line.take(80)}")
                continue
            }
            val body = line.removePrefix("PRUNE").trim()
            val parts = body.split("::", limit = 2)
            if (parts.size < 2) { refused.add("missing :: separator: ${line.take(80)}"); continue }
            val name = parts[0].trim()
            val rest = parts[1]
            val arrow = rest.indexOf("=>")
            if (arrow < 0) { refused.add("missing => in: ${line.take(80)}"); continue }
            val quote = rest.substring(0, arrow).trim()
            val action = rest.substring(arrow + 2).trim()
            if (quote.isEmpty()) { refused.add("empty quote for $name"); continue }

            val file = File(memoryDir, name)
            // Confine to the memory dir: plain .md basename, no path traversal.
            val validName = !name.contains("..") && !name.contains("/") &&
                !name.contains("\\") && name.endsWith(".md") && name != "SOUL.md"
            if (!validName || !file.exists()) {
                refused.add("not a memory file: $name")
                continue
            }
            val replacement: String? = when {
                action == "DELETE" -> null
                action.startsWith("REPLACE:") -> action.removePrefix("REPLACE:").trim()
                else -> { refused.add("bad action for $name: ${action.take(40)}"); continue }
            }
            val content = try { file.readText() } catch (e: Exception) {
                refused.add("read failed $name: ${e.message}")
                continue
            }
            // Trim-normalized containment search (first occurrence)
            val idx = content.indexOf(quote)
            if (idx < 0) {
                refused.add("quote not found in $name: ${quote.take(50)}")
                continue
            }
            val newContent = when (replacement) {
                null -> content.removeRange(idx, idx + quote.length)
                else -> content.substring(0, idx) + replacement + content.substring(idx + quote.length)
            }
            // Tidy: collapse triple blank lines left by deletions
            val tidied = newContent.replace(Regex("\n{4,}"), "\n\n\n")
            try {
                file.writeText(tidied)
                applied++
                Log.i(TAG, "prune applied: $name op=${if (replacement == null) "DELETE" else "REPLACE"} q=${quote.take(40)}")
            } catch (e: Exception) {
                refused.add("write failed $name: ${e.message}")
            }
        }
        return applied to refused
    }

    /** The planner prompt for the model. Manifest is rendered into it by
     *  the caller (ChatViewModel) so this stays pure and testable. */
    const val PLANNER_INSTRUCTIONS = """You are running a memory review pass (Dream). For each file below, make the SMALLEST honest edit:
- PRUNE task residue: session state, transient plans, entry duplicates, resolved items that outlived their purpose.
- KEEP durable facts: user preferences, project conventions, lessons, environment facts.
- Never delete a whole file's history of a durable fact — compress near-duplicates into the newest file instead.
- Rewrite NOTHING wholesale. Quote the exact text you mean.

Output STRICTLY one line per operation (no prose, no markdown):
PRUNE <file> :: <exact text quoted from the file> => DELETE
PRUNE <file> :: <exact text quoted from the file> => REPLACE: <new text>
Or a single line "PRUNE <file> :: ... => KEEP" is wrong — to skip a file output nothing for it.

Rules:
- Quote text EXACTLY as it appears (copy-paste, no paraphrase). First occurrence will be edited.
- DELETE for stale entries; REPLACE for compressions/merges.
- Skip files marked truncated-head — only their shown lines are editable.
- Do not edit GLOBAL.md unless explicitly included in the manifest.
- Budget: at most ${'$'}MAX_EDITS edits total. Prefer deleting stale over rewriting."""
}
