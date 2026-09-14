package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-check-markers] + [T-gardener] JVM contracts for audit P0 #6/#7:
 * outcome-marker parsing (zero-cost text contract) and gardener file
 * mechanics (eligibility clocks, page shape, marker stamps, index).
 */
class GardenerAndMarkersTest {

    // ── TurnOutcomeMarkers ──────────────────────────────────────────────────

    @Test
    fun `markers parse standalone lines`() {
        assertEquals(TurnOutcomeMarkers.Outcome.CHECK_OK, TurnOutcomeMarkers.parse("All good.\nCHECK_OK"))
        assertEquals(TurnOutcomeMarkers.Outcome.CHECK_FAILED, TurnOutcomeMarkers.parse("Couldn't reach it.\nCHECK_FAILED"))
        assertEquals(TurnOutcomeMarkers.Outcome.CHECK_WARNED, TurnOutcomeMarkers.parse("checked — build broken\nCHECK_WARNED"))
        assertEquals(TurnOutcomeMarkers.Outcome.CHECK_DELEGATED, TurnOutcomeMarkers.parse("handing off to a timer\nCHECK_DELEGATED"))
    }

    @Test
    fun `last marker wins`() {
        assertEquals(
            TurnOutcomeMarkers.Outcome.CHECK_OK,
            TurnOutcomeMarkers.parse("CHECK_FAILED earlier attempt\nretry went fine\nCHECK_OK"),
        )
    }

    @Test
    fun `no marker is unknown`() {
        assertEquals(TurnOutcomeMarkers.Outcome.UNKNOWN, TurnOutcomeMarkers.parse("Here is your weather report."))
        assertEquals(TurnOutcomeMarkers.Outcome.UNKNOWN, TurnOutcomeMarkers.parse(""))
        assertEquals(TurnOutcomeMarkers.Outcome.UNKNOWN, TurnOutcomeMarkers.parse("   "))
    }

    @Test
    fun `case insensitive marker`() {
        assertEquals(TurnOutcomeMarkers.Outcome.CHECK_OK, TurnOutcomeMarkers.parse("done\ncheck_ok"))
    }

    // ── Gardener eligibility ─────────────────────────────────────────────────

    @get:Rule
    val tmp = TemporaryFolder()

    private fun memoryDir(): File = tmp.newFolder("memory")
    private fun sharedDir(): File = tmp.newFolder("shared")

    @Test
    fun `candidate skips today and GLOBAL and SOUL and CURRENT_STATE`() {
        val mem = memoryDir(); val shared = sharedDir()
        File(mem, "2026-09-14.md").writeText("a\nb\nc\nd\ne\n")
        File(mem, "GLOBAL.md").writeText("x\ny\nz\nw\nv\n")
        File(mem, "SOUL.md").writeText("x\ny\nz\nw\nv\n")
        File(mem, "CURRENT_STATE.md").writeText("x\ny\nz\nw\nv\n")
        val c = Gardener.pickCandidate(mem, shared, nowMs = System.currentTimeMillis())
        assertNull("nothing eligible (only today's log exists)", c)
    }

    @Test
    fun `candidate picks oldest-eligible first`() {
        val mem = memoryDir(); val shared = sharedDir()
        File(mem, "2026-09-01.md").writeText("one\n".repeat(6))
        File(mem, "2026-09-02.md").writeText("two\n".repeat(6))
        // nowMs far in the future so both are ≥2 days old
        val now = System.currentTimeMillis() + 30L * 86_400_000L
        val c = Gardener.pickCandidate(mem, shared, nowMs = now)
        assertNotNull(c)
        assertEquals("2026-09-01.md", c!!.name)
    }

    @Test
    fun `regarden distance clock blocks recent gardening`() {
        val mem = memoryDir(); val shared = sharedDir()
        File(mem, "2026-09-01.md").writeText("one\n".repeat(6))
        val now = System.currentTimeMillis() + 30L * 86_400_000L
        Gardener.markGardened(shared, "2026-09-01.md", nowMs = now - 1L * 3_600_000L) // 1h ago
        assertNull("gardened 1h ago, 6h clock blocks", Gardener.pickCandidate(mem, shared, nowMs = now))
    }

    @Test
    fun `distance clock expires after 6h`() {
        val mem = memoryDir(); val shared = sharedDir()
        File(mem, "2026-09-01.md").writeText("one\n".repeat(6))
        val now = System.currentTimeMillis() + 30L * 86_400_000L
        Gardener.markGardened(shared, "2026-09-01.md", nowMs = now - 7L * 3_600_000L) // 7h ago
        val c = Gardener.pickCandidate(mem, shared, nowMs = now)
        assertNotNull(c)
    }

    @Test
    fun `stub-only files are not candidates`() {
        val mem = memoryDir(); val shared = sharedDir()
        File(mem, "2026-09-01.md").writeText("<!-- 2026-09-01 01:00:00 -->\n\n\n")
        val now = System.currentTimeMillis() + 30L * 86_400_000L
        assertNull(Gardener.pickCandidate(mem, shared, nowMs = now))
    }

    // ── Gardener page shape + index ─────────────────────────────────────────

    @Test
    fun `writePage creates frontmatter and rebuilds index`() {
        val shared = sharedDir()
        val page = Gardener.writePage(
            shared, "Recycled SIM Lifecycle", "decided", "2026-09-01.md",
            "The DZ number lifecycle recycles after ~3 months.",
        )
        assertNotNull(page)
        val text = page!!.readText()
        assertTrue(text.startsWith("---\n"))
        assertTrue(text.contains("state: decided"))
        assertTrue(text.contains("source: 2026-09-01.md"))
        assertTrue(text.contains("recycles after ~3 months"))
        val index = File(Gardener.wikiRoot(shared), "index.md").readText()
        assertTrue(index.contains("Recycled SIM Lifecycle"))
    }

    @Test
    fun `writePage sanitizes hostile names`() {
        val shared = sharedDir()
        val page = Gardener.writePage(shared, "../../etc/passwd", "open", "2026-09-01.md", "body")
        // sanitized to a safe name — the file must NOT land outside pages/
        if (page != null) {
            assertTrue(page.parentFile!!.name == "pages")
            assertTrue(!page.absolutePath.contains(".."))
        }
    }

    @Test
    fun `index lists newest first with state`() {
        val shared = sharedDir()
        Gardener.writePage(shared, "Page One", "open", "2026-09-01.md", "b1")
        Thread.sleep(50)
        Gardener.writePage(shared, "Page Two", "done", "2026-09-02.md", "b2")
        val index = File(Gardener.wikiRoot(shared), "index.md").readText()
        val one = index.indexOf("Page One")
        val two = index.indexOf("Page Two")
        assertTrue("newest first", two < one)
        assertTrue(index.contains("done"))
    }
}
