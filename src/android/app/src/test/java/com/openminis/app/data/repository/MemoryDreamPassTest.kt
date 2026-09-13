package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-dream-prune] JVM tests for the pure Dream-pass logic: manifest
 * building (bounded disclosure, skip-empty), plan application (exact-quote
 * matching, refusal reasons, path-traversal confinement, GLOBAL protection
 * at manifest level), snapshot + keep-one rollback.
 *
 * Filesystem-touching but Context-free → TemporaryFolder works under plain
 * JUnit (no Robolectric needed; MemoryDreamPass uses only java.io + Log,
 * and Log is shadowed by the JVM-safe android util stub in test sources).
 */
class MemoryDreamPassTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun memoryDir(): File = tmp.newFolder("memory")

    private fun write(dir: File, name: String, content: String) {
        File(dir, name).writeText(content)
    }

    @Test
    fun `manifest skips empty and stub-only files`() {
        val dir = memoryDir()
        write(dir, "2026-09-10.md", "<!-- 2026-09-10 01:00:00 -->\n\n")
        write(dir, "2026-09-11.md", "<!-- ts -->\n<!-- ts2 -->\n\n   \n")
        val manifest = MemoryDreamPass.buildManifest(dir, includeGlobal = false)
        assertTrue("stub-only files must not appear", manifest.none { it.name.startsWith("2026-09-1") })
    }

    @Test
    fun `manifest is newest-first and bounded to MAX_ENTRIES`() {
        val dir = memoryDir()
        for (i in 1..25) {
            write(dir, "2026-09-%02d.md".format(i), "<!-- ts -->\nreal durable fact number $i\n")
        }
        val manifest = MemoryDreamPass.buildManifest(dir, includeGlobal = false)
        assertEquals(MemoryDreamPass.MAX_ENTRIES, manifest.size)
        assertEquals("2026-09-25.md", manifest.first().name)
        // Oldest files beyond the budget are NOT silently dropped — the pass
        // simply reports what it took; next run covers them (newest-first
        // ordering guarantees eventual coverage).
        assertTrue(manifest.first().truncated.not())
    }

    @Test
    fun `manifest truncates long files and discloses it`() {
        val dir = memoryDir()
        val many = (1..350).joinToString("\n") { "line $it of a very long day" }
        write(dir, "2026-09-12.md", many)
        val m = MemoryDreamPass.buildManifest(dir, includeGlobal = false).single()
        assertTrue(m.truncated)
        assertEquals(MemoryDreamPass.MAX_REVIEW_LINES, m.reviewedLines)
        assertTrue(m.lines > m.reviewedLines)
    }

    @Test
    fun `manifest excludes GLOBAL when not included`() {
        val dir = memoryDir()
        write(dir, "GLOBAL.md", "global fact\nanother\n")
        write(dir, "2026-09-12.md", "daily fact\n")
        val manifest = MemoryDreamPass.buildManifest(dir, includeGlobal = false)
        assertTrue(manifest.none { it.name == "GLOBAL.md" })
        val withGlobal = MemoryDreamPass.buildManifest(dir, includeGlobal = true)
        assertTrue(withGlobal.any { it.name == "GLOBAL.md" })
    }

    @Test
    fun `applyPlan DELETE removes exact quote`() {
        val dir = memoryDir()
        val content = "keep this\n<!-- 2026-09-12 10:00:00 -->\nstale task residue line\n\nkeep this too\n"
        write(dir, "2026-09-12.md", content)
        val (applied, refused) = MemoryDreamPass.applyPlan(
            dir,
            listOf("PRUNE 2026-09-12.md :: stale task residue line => DELETE"),
        )
        assertEquals(1, applied)
        assertTrue(refused.isEmpty())
        val after = File(dir, "2026-09-12.md").readText()
        assertFalse(after.contains("stale task residue"))
        assertTrue(after.contains("keep this too"))
    }

    @Test
    fun `applyPlan REPLACE substitutes exact quote`() {
        val dir = memoryDir()
        write(dir, "2026-09-12.md", "a\nversion 1 of the fact\nb\n")
        val (applied, _) = MemoryDreamPass.applyPlan(
            dir,
            listOf("PRUNE 2026-09-12.md :: version 1 of the fact => REPLACE: version 2 of the fact"),
        )
        assertEquals(1, applied)
        assertEquals("a\nversion 2 of the fact\nb\n", File(dir, "2026-09-12.md").readText())
    }

    @Test
    fun `applyPlan refuses unknown quote and bad syntax`() {
        val dir = memoryDir()
        write(dir, "2026-09-12.md", "content here\n")
        val (applied, refused) = MemoryDreamPass.applyPlan(
            dir,
            listOf(
                "PRUNE 2026-09-12.md :: not present anywhere => DELETE",
                "PRUNE 2026-09-12.md no-arrow line",
                "GARBAGE line",
            ),
        )
        assertEquals(0, applied)
        assertEquals(3, refused.size)
        assertTrue(refused.any { it.contains("not found") })
        assertTrue(refused.any { it.contains("=>") })
        assertTrue(refused.any { it.contains("unrecognized") })
    }

    @Test
    fun `applyPlan confines to memory dir - no traversal no absolute no SOUL`() {
        val dir = memoryDir()
        write(dir, "2026-09-12.md", "in-file\n")
        val (applied, refused) = MemoryDreamPass.applyPlan(
            dir,
            listOf(
                "PRUNE ../escape.md :: x => DELETE",
                "PRUNE /etc/passwd => : x => DELETE",
                "PRUNE ../../somewhere/2026-09-12.md :: in-file => DELETE",
                "PRUNE SOUL.md :: x => DELETE",
            ),
        )
        assertEquals(0, applied)
        assertEquals(4, refused.size)
        assertTrue(refused.all { it.contains("not a memory file") })
    }

    @Test
    fun `applyPlan only first occurrence is edited`() {
        val dir = memoryDir()
        write(dir, "2026-09-12.md", "dup\nmid\ndup\n")
        val (applied, _) = MemoryDreamPass.applyPlan(
            dir, listOf("PRUNE 2026-09-12.md :: dup => REPLACE: unique"),
        )
        assertEquals(1, applied)
        val after = File(dir, "2026-09-12.md").readText()
        assertEquals("unique\nmid\ndup\n", after)
    }

    @Test
    fun `snapshot copies all md files and keep-one prunes older`() {
        val dir = memoryDir()
        write(dir, "2026-09-12.md", "content A\n")
        write(dir, "GLOBAL.md", "global content\n")
        val snap1 = MemoryDreamPass.snapshot(dir)
        assertNotNull(snap1)
        assertEquals(setOf("2026-09-12.md", "GLOBAL.md"),
            snap1!!.listFiles()!!.map { it.name }.toSet())
        assertEquals("content A\n", File(snap1, "2026-09-12.md").readText())

        // second snapshot, prune with keep=newest → only the newest survives
        Thread.sleep(1100) // distinct timestamp
        write(dir, "2026-09-12.md", "content B\n")
        val snap2 = MemoryDreamPass.snapshot(dir)
        assertNotNull(snap2)
        MemoryDreamPass.pruneOldSnapshots(dir, snap2)
        val dreamDirs = File(dir, "snapshots").listFiles()!!.filter { it.name.startsWith("dream-") }
        assertEquals(1, dreamDirs.size)
        assertEquals("content B\n", File(dreamDirs[0], "2026-09-12.md").readText())
    }

    @Test
    fun `keep-one with null keep removes all snapshots`() {
        val dir = memoryDir()
        write(dir, "2026-09-12.md", "x\n")
        val s1 = MemoryDreamPass.snapshot(dir)
        Thread.sleep(1100)
        val s2 = MemoryDreamPass.snapshot(dir)
        MemoryDreamPass.pruneOldSnapshots(dir, null)
        val remaining = File(dir, "snapshots").listFiles()!!.filter { it.name.startsWith("dream-") }
        assertTrue(remaining.isEmpty())
    }
}
