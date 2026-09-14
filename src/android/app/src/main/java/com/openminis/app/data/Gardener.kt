package com.openminis.app.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-gardener] Audit P0 #6 — journal-to-wiki promotion pass (Jenny
 * gardener architecture; no code, AGPL).
 *
 * Marwan's daily logs already capture durable knowledge in capture format
 * (## headers, dense paragraphs). The gardener converts entries that
 * EARN a page into a cross-linked wiki under shared/wiki/: one pass per
 * trigger, bounded, snapshot-free (memory files are only READ here —
 * promotion writes to the wiki, never mutates the journal), user always
 * wins (re-checks idle before every write; stands down mid-pass if the
 * agent starts running).
 *
 * Phone adaptation of Jenny's three clocks:
 *   - interval 30min  → replaced by session-end trigger (no standing
 *     timer on a phone; piggybacks the existing completionListener)
 *   - silence 30min   → app must be idle (no active sessions) at trigger
 *     time — same condition the completion path already knows
 *   - distance 6h    → LAST_GARDENED marker per project-ish target:
 *     each pass gardens the LEAST-RECENTLY-GARDENED eligible file; a
 *     file is re-eligible only after MIN_REGARDEN_HOURS
 *
 * The promotion decision itself is model-routed (a single non-streaming
 * call, same provider path as compaction — the Dream pass pattern); this
 * object handles the file mechanics: eligibility, page shape, frontmatter,
 * index rebuild, [[wikilinks]].
 *
 * Wiki location: shared/wiki/ (guest-visible, survives sandbox resets,
 * user-browsable from the app's Shared Folders screen).
 */
object Gardener {

    private const val WIKI_DIR = "wiki"
    private const val PAGES_DIR = "pages"
    private const val INDEX_FILE = "index.md"
    private const val MARKER_DIR = ".gardener"
    private const val ELIGIBLE_AGE_DAYS = 2L   // only garden files ≥2 days old (today's log is still live)
    private const val MIN_REGARDEN_HOURS = 6L  // Jenny's distance clock
    private const val MAX_LINES_PER_PASS = 200 // Jenny's read budget

    /** One candidate the model was asked about / promoted. */
    data class Candidate(
        val journalFile: File,
        val name: String,
        val lines: Int,
        val lastGardenedMs: Long,   // 0 = never
        val content: String,
    )

    fun wikiRoot(sharedDir: File): File = File(sharedDir, WIKI_DIR)
    fun pagesRoot(sharedDir: File): File = File(wikiRoot(sharedDir), PAGES_DIR)

    /** Marker file naming: <journal-name>.lastGardened, content = epoch ms. */
    private fun markerFor(sharedDir: File, journalName: String): File =
        File(File(sharedDir, MARKER_DIR), "$journalName.lastGardened")

    /**
     * Pick the least-recently-gardened eligible daily file. Eligibility:
     * ≥ ELIGIBLE_AGE_DAYS old (by filename date), has meaningful content,
     * last gardened ≥ MIN_REGARDEN_HOURS ago (or never). Returns null when
     * nothing is eligible (the common case — most triggers no-op).
     */
    fun pickCandidate(memoryDir: File, sharedDir: File, nowMs: Long = System.currentTimeMillis()): Candidate? {
        val files = memoryDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") && it.name != "GLOBAL.md" &&
                it.name != "SOUL.md" && it.name != "CURRENT_STATE.md" }
            ?: return null
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var best: Candidate? = null
        for (f in files) {
            val fileDate = try {
                dateFmt.parse(f.name.removeSuffix(".md"))
            } catch (_: Exception) { continue } // not a dated log — skip
            val ageDays = (nowMs - fileDate.time) / 86_400_000L
            if (ageDays < ELIGIBLE_AGE_DAYS) continue
            val content = try { f.readText() } catch (_: Exception) { continue }
            val meaningful = content.lines().count { it.isNotBlank() && !it.trimStart().startsWith("<!--") }
            if (meaningful < 5) continue
            val marker = markerFor(sharedDir, f.name)
            val lastGardened = if (marker.exists()) {
                try { marker.readText().trim().toLong() } catch (_: Exception) { 0L }
            } else 0L
            if (lastGardened > 0 && nowMs - lastGardened < MIN_REGARDEN_HOURS * 3_600_000L) continue
            if (best == null || lastGardened < best!!.lastGardenedMs) {
                best = Candidate(
                    journalFile = f,
                    name = f.name,
                    lines = content.lines().size,
                    lastGardenedMs = lastGardened,
                    content = content.lines().take(MAX_LINES_PER_PASS).joinToString("\n"),
                )
            }
        }
        return best
    }

    /** Stamp a journal as gardened now (after a completed pass, even a
     *  no-op — the file was CONSIDERED; re-eligibility is the distance
     *  clock's job, not the content's). */
    fun markGardened(sharedDir: File, journalName: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val m = markerFor(sharedDir, journalName)
            m.parentFile?.mkdirs()
            m.writeText(nowMs.toString())
        } catch (_: Exception) { }
    }

    /** Page shape: frontmatter (state + provenance) + body. The state
     *  machine is the model's call — we just persist what it decided. */
    fun writePage(sharedDir: File, pageName: String, state: String, source: String, body: String): File? {
        return try {
            val pages = pagesRoot(sharedDir)
            pages.mkdirs()
            val safe = pageName.replace(Regex("[^a-zA-Z0-9-_ ]"), "").trim().take(64)
                .replace(' ', '_')
            if (safe.isBlank()) return null
            val file = File(pages, "$safe.md")
            val frontmatter = buildString {
                append("---\n")
                append("state: $state\n")
                append("source: $source\n")
                append("gardened: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n")
                append("---\n\n")
            }
            file.writeText(frontmatter + body.trim() + "\n")
            rebuildIndex(sharedDir)
            file
        } catch (_: Exception) { return null }
    }

    /** Rebuild index.md from the pages dir: title + state + mtime, newest
     *  first. Wikilinks rendered as [[page-name]] like the body links. */
    fun rebuildIndex(sharedDir: File) {
        try {
            val pages = pagesRoot(sharedDir)
            val files = pages.listFiles()?.filter { it.name.endsWith(".md") }
                ?.sortedByDescending { it.lastModified() } ?: return
            val sb = StringBuilder("# Wiki Index\n\n")
            sb.append("Auto-rebuilt by the gardener. Pages carry frontmatter state: open / hypothesis / decided / done.\n\n")
            if (files.isEmpty()) {
                sb.append("_(no pages yet)_\n")
            } else {
                for (f in files) {
                    val head = try { f.readText().lines().take(8).joinToString("\n") } catch (_: Exception) { continue }
                    val state = Regex("""(?m)^state:\s*(\w+)""").find(head)?.groupValues?.get(1) ?: "open"
                    val title = Regex("""(?m)^#\s+(.+)$""").find(head)?.groupValues?.get(1) ?: f.name.removeSuffix(".md")
                    sb.append("- [[$title]] — $state (${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))})\n")
                }
            }
            File(wikiRoot(sharedDir), INDEX_FILE).writeText(sb.toString())
        } catch (_: Exception) { }
    }

    /** Prompt for the promotion decision — one candidate journal in,
     *  page proposals out. Pure string; model call routes through the
     *  Dream-pass provider path. */
    const val PROMPT = """You are the gardener. Below is one daily journal file. Decide which entries EARN a durable wiki page.
A page earns its place when it describes: a reusable technique, a stable fact, a lesson learned, an architecture decision — knowledge useful BEYOND this day.
Do NOT page: session state, release logs, transient status, TODOs, anything only meaningful in the moment of writing.
Output STRICTLY one block per page you propose (no prose outside blocks), or NOTHING if none qualify:
=== PAGE: <short-title-3-6-words>
state: open| hypothesis| decided| done
<wiki-style body: 5-25 lines, [[wikilink]] to other pages by title where relevant, no timestamps, present tense>
=== END
Maximum 3 pages per file. Be stingy — a wiki of 10 real pages beats 100 stubs."""
}
