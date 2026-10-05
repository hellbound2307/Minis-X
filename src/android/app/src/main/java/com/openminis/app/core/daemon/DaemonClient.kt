package com.openminis.app.core.daemon

import android.content.Context
import android.content.Intent
import com.openminis.app.core.execution.RunId

object DaemonClient {
    fun startRun(context: Context, sessionId: String, prompt: String, parentRunId: String? = null) {
        val intent = Intent(context, AgentDaemonService::class.java).apply {
            action = "ACTION_START_RUN"
            putExtra("SESSION_ID", sessionId)
            putExtra("PROMPT", prompt)
            parentRunId?.let { putExtra("PARENT_RUN_ID", it) }
        }
        context.startService(intent)
    }

    fun stopRun(context: Context, runId: String) {
        val intent = Intent(context, AgentDaemonService::class.java).apply {
            action = "ACTION_CANCEL_RUN"
            putExtra("RUN_ID", runId)
        }
        context.startService(intent)
    }
}
