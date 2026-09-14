package com.openminis.app.data

/**
 * [T-check-markers] Audit P0 #7 — turn-final outcome markers (Jenny
 * could_not_check architecture; zero-cost parsing, no dedicated tool).
 *
 * On periodic/verification turns the model's final text already describes
 * what happened — it just was never parsed for outcome semantics. A
 * dedicated outcome tool would cost ~757 chars (≈190 tokens) on EVERY
 * request (~3.8% of tool payload). Instead the model may END a turn with
 * one of four markers, and this parser extracts them from text that
 * exists already:
 *
 *   CHECK_OK        — the thing the turn was supposed to verify is verified
 *   CHECK_DELEGATED — handed off (subagent/timer/scheduled task); not dead
 *                     but not confirmed either
 *   CHECK_FAILED    — could NOT verify (target unreachable, credentials
 *                     rejected, state missing). This is the dead-check
 *                     signal the watchdog escalates on.
 *   CHECK_WARNED    — verified AND found something wrong the user should
 *                     know (build broken, service down, quota burning)
 *
 * Silent absence = UNKNOWN (a turn that doesn't carry a marker is simply
 * not a verification turn — periodic monitors should carry one).
 *
 * Pure + dependency-free → JVM-tested.
 */
object TurnOutcomeMarkers {

    enum class Outcome { CHECK_OK, CHECK_DELEGATED, CHECK_FAILED, CHECK_WARNED, UNKNOWN }

    /**
     * Parse the LAST occurrence of any marker in the final text (last wins:
     * a turn that first muses "check failed earlier" but ends OK is OK).
     * Markers may appear anywhere but must stand alone on their line or
     * end the text — no parenthetical false-positives inside sentences.
     */
    fun parse(finalText: String): Outcome {
        val text = finalText.trim()
        if (text.isEmpty()) return Outcome.UNKNOWN
        var result = Outcome.UNKNOWN
        for (raw in text.lines()) {
            val line = raw.trim().uppercase()
            for (m in Outcome.entries) {
                if (m == Outcome.UNKNOWN) continue
                // Standalone-line form: "CHECK_OK" or "CHECK_OK — note…"
                // (an em-dash suffix reads naturally to the user). Not
                // matched mid-sentence: requires line-start.
                if (line == m.name || line.startsWith(m.name)) {
                    result = m
                }
            }
        }
        return result
    }

    /** The instruction block appended to periodic/scheduled prompts so the
     *  model knows the contract. Lives here so the contract can't drift
     *  from the parser. */
    const val PROMPT_CONTRACT = "" +
        "Outcome contract: when your task was to CHECK something (monitor, verify, follow up), " +
        "end your reply with a final line containing exactly one marker: " +
        "CHECK_OK (verified fine), CHECK_DELEGATED (handed off — timer/subagent will follow), " +
        "CHECK_FAILED (could not verify — target unreachable/missing/credentials rejected), " +
        "or CHECK_WARNED (verified AND something is wrong — describe it). " +
        "Turns that are not verification turns need no marker."
}
