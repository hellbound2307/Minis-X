package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.events.AgentRunRecorder
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * [T-android-run-recorder] Pillar A5 — run HUD.
 *
 * One consolidated status line for the session's live work: run id, tool count,
 * the tool currently executing (with its own elapsed), the run's total elapsed,
 * and how many subagent runs are live underneath it.
 *
 * vc59 changes, both from reading a real screenshot of vc57:
 *  - the id was `takeLast(6)`, which straddled the timestamp/uuid boundary and
 *    rendered as `p_1d3e`; the recorder now exposes a proper [RunInfo.shortId].
 *  - when nothing was executing the chip showed count-only and looked stalled;
 *    it now falls back to the last completed tool and its duration.
 *  - it reads [AgentRunRecorder.activeRuns] rather than a single global
 *    snapshot, so a subagent's run no longer masquerades as the parent's.
 *
 * It also drives [AgentRunRecorder.tick] once per second, which closes runs
 * that have gone quiet — that is what guarantees a `run_end` in the JSONL when
 * the agent loop exits through one of its many paths (cancel, provider error,
 * model timeout) instead of a clean finally-block.
 */
@Composable
internal fun RunHudChip(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val allRuns by AgentRunRecorder.activeRuns.collectAsState()
    val sessionRuns = allRuns.filter { it.sessionId == sessionId }
    if (sessionRuns.isEmpty()) return

    // The top-level run for this session; a session with only children (a
    // spawn from a headless/scheduled turn) still shows something.
    val primary = sessionRuns.firstOrNull { it.parentRunId == null } ?: sessionRuns.first()
    val childCount = sessionRuns.count { it.parentRunId != null }

    LaunchedEffect(primary.runId) {
        while (true) {
            AgentRunRecorder.tick()
            delay(1000)
        }
    }

    val now = System.currentTimeMillis()
    val elapsedSec = (now - primary.startedAt) / 1000
    val activeSec = if (primary.activeSince > 0) (now - primary.activeSince) / 1000 else 0L

    val label = buildString {
        append("run ")
        append(primary.shortId)
        append(" · ")
        append(primary.toolCount)
        append(if (primary.toolCount == 1) " tool" else " tools")

        val tool = primary.activeTool
        if (tool != null) {
            append(" · ")
            append(tool)
            if (activeSec > 0) {
                append(" ")
                append(activeSec)
                append("s")
            }
        } else {
            // Idle between calls: show what just finished, so the chip never
            // reads as a bare counter while work is clearly in flight.
            primary.lastTool?.let { last ->
                append(" · last ")
                append(last)
                if (primary.lastToolMs > 0) {
                    append(" ")
                    append(primary.lastToolMs / 1000.0)
                    append("s")
                }
            }
        }

        if (childCount > 0) {
            append(" · +")
            append(childCount)
            append(" sub")
        }

        append(" · ")
        append(elapsedSec)
        append("s")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(ChatColors.toolCapsuleBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
        )
    }
}
