package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.events.AgentRunRecorder
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * [T-android-run-recorder] Pillar A5 — minimal HUD chip.
 *
 * Placed right above the subagent activity panel so a running agent shows
 * one consolidated status line: run id, tool count, current tool (if any),
 * elapsed seconds. The chip auto-hides when there's no active run for the
 * current session, and it calls [AgentRunRecorder.tick] each second so the
 * JSONL on disk is closed with a `run_end` even when the agent loop exits
 * through one of its many paths (cancel, error, model timeout, etc.) rather
 * than a single clean finally-block.
 */
@Composable
internal fun RunHudChip(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val snapshot by AgentRunRecorder.snapshot.collectAsState()

    // Session filter: the recorder is app-global, but the chat only shows
    // its own session's run. When the snapshot belongs to another session
    // (or there is none), render nothing.
    val relevant = snapshot?.takeIf { it.sessionId == sessionId && it.status == "running" }
    if (relevant == null) return

    // Keep the on-disk run closed if the UI goes quiet. The HUD is only
    // mounted while we're in the chat, so this is a safe no-op when the
    // user leaves the screen.
    LaunchedEffect(true) {
        while (true) {
            AgentRunRecorder.tick()
            delay(1000)
        }
    }

    val toolCount = relevant.toolCount
    val activeTool = relevant.activeTool
    val elapsedSec = (System.currentTimeMillis() - relevant.startedAt) / 1000
    val activeSinceSec = if (relevant.activeSince > 0)
        (System.currentTimeMillis() - relevant.activeSince) / 1000 else 0L

    val shortRunId = relevant.runId.takeLast(8)

    val label = buildString {
        append("Run $shortRunId · $toolCount tool")
        if (toolCount != 1) append("s")
        activeTool?.let { t ->
            append(" · $t")
            if (activeSinceSec > 0) append(" ${activeSinceSec}s")
        }
        append(" · ${elapsedSec}s")
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
        }
        androidx.compose.foundation.background(
            color = ChatColors.surfaceContainerHighest.copy(alpha = 0.9f),
            shape = RoundedCornerShape(12.dp),
        )
    }
}