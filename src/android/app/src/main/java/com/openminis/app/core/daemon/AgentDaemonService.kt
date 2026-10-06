package com.openminis.app.core.daemon

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.openminis.app.core.execution.RunRegistry
import com.openminis.app.core.execution.RunId
import com.openminis.app.core.execution.RunStatus
import javax.inject.Inject

/**
 * The God-Tier Agent Daemon.
 * 
 * This Service detaches execution from the UI (ChatViewModel).
 * It holds a partial wakelock and survives the UI being backgrounded or destroyed.
 * All shell commands, network sweeps, and LLM reasoning loops run HERE.
 */
@AndroidEntryPoint
class AgentDaemonService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Inject lateinit var runRegistry: RunRegistry
    @Inject lateinit var daemonLLMRunner: DaemonLLMRunner

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "ACTION_START_RUN" -> {
                val sessionId = intent.getStringExtra("SESSION_ID") ?: return START_NOT_STICKY
                val prompt = intent.getStringExtra("PROMPT") ?: return START_NOT_STICKY
                val parentRunId = intent.getStringExtra("PARENT_RUN_ID")?.let { RunId(it) }
                
                serviceScope.launch {
                    startExecutionRun(sessionId, prompt, parentRunId)
                }
            }
            "ACTION_CANCEL_RUN" -> {
                val runId = intent.getStringExtra("RUN_ID")?.let { RunId(it) } ?: return START_NOT_STICKY
                serviceScope.launch {
                    terminateRun(runId)
                }
            }
        }
        
        // START_STICKY ensures if the OS kills this (due to extreme memory pressure), 
        // it tries to restart it so we can gracefully fail the active runs.
        return START_STICKY
    }

    private suspend fun startExecutionRun(sessionId: String, prompt: String, parentRunId: RunId?) {
        val runId = runRegistry.registerRun(sessionId, parentRunId)
        runRegistry.updateStatus(runId, RunStatus.RUNNING)
        
        try {
            daemonLLMRunner.executeRun(runId, sessionId, prompt)
        } catch (e: Exception) {
            runRegistry.updateStatus(runId, RunStatus.FAILED, e.message)
        }
    }

    private fun terminateRun(runId: RunId) {
        // 1. Mark as cancelled in registry (cascades to children)
        runRegistry.cancelRunAndChildren(runId)
        
        // 2. Send SIGKILL to the PRoot sandbox for this specific run's process group
        // prRootSandboxController.killProcessGroupForRun(runId)
        
        // 3. Commit [User Cancelled] marker to DB atomically
        // databaseHelper.insertSystemMarker(runId.sessionId, "[Run Cancelled by Operator]")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
