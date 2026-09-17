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
 * [T-android-run-recorder] Pillar A5 — minimal run HUD.
 *
 * One consolidated status line for the active agent run: short run id, tool
 * count, the tool currently executing (with its own elapsed time) and the run's
 * total elapsed time. Auto-hides when there is no running run for this session.
 *
 * It also drives [AgentRunRecorder.tick] once per second, which is what closes
 * the on-disk JSONL with a `run_end` when the agent loop exits through one of
 * its many paths (cancel, provider error, model timeout) instead of a single
 * clean finally-block. The HUD only exists while the chat is on screen, so the
 * sweep is bounded by the user actually looking at the run.
 */
@Composable
internal fun RunHudChip(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val snapshot by AgentRunRecorder.snapshot.collectAsState()

    // App-global recorder, per-session display: another session's run must not
    // leak into this chat.
    val run = snapshot
    if (run == null || run.sessionId != sessionId || run.status != "running") return

    LaunchedEffect(run.runId) {
        while (true) {
            AgentRunRecorder.tick()
            delay(1000)
        }
    }

    val now = System.currentTimeMillis()
    val elapsedSec = (now - run.startedAt) / 1000
    val activeSec = if (run.activeSince > 0) (now - run.activeSince) / 1000 else 0L

    val label = buildString {
        append("run ")
        append(run.runId.takeLast(6))
        append(" · ")
        append(run.toolCount)
        append(if (run.toolCount == 1) " tool" else " tools")
        run.activeTool?.let { tool ->
            append(" · ")
            append(tool)
            if (activeSec > 0) {
                append(" ")
                append(activeSec)
                append("s")
            }
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
