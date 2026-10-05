package com.openminis.app.core.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global registry of all active runs. 
 * Replaces the fragile 'isGenerating' booleans inside the ChatViewModel.
 * This lives in the singleton Daemon layer.
 */
@Singleton
class RunRegistry @Inject constructor() {
    private val activeRuns = ConcurrentHashMap<RunId, RunState>()
    
    private val _runsFlow = MutableStateFlow<Map<RunId, RunState>>(emptyMap())
    val runsFlow: StateFlow<Map<RunId, RunState>> = _runsFlow.asStateFlow()

    fun registerRun(sessionId: String, parentRunId: RunId? = null): RunId {
        val newRunId = RunId()
        val state = RunState(runId = newRunId, parentRunId = parentRunId, sessionId = sessionId)
        activeRuns[newRunId] = state
        updateFlow()
        return newRunId
    }

    fun updateStatus(runId: RunId, status: RunStatus, errorMessage: String? = null) {
        activeRuns[runId]?.let { currentState ->
            activeRuns[runId] = currentState.copy(status = status, errorMessage = errorMessage)
            updateFlow()
        }
    }

    fun getActiveRunsForSession(sessionId: String): List<RunState> {
        return activeRuns.values.filter { it.sessionId == sessionId && (it.status == RunStatus.RUNNING || it.status == RunStatus.PENDING) }
    }

    fun getRun(runId: RunId): RunState? = activeRuns[runId]

    fun cancelRunAndChildren(runId: RunId) {
        // Cascade cancellation to children
        activeRuns.values.filter { it.parentRunId == runId }.forEach {
            cancelRunAndChildren(it.runId)
        }
        updateStatus(runId, RunStatus.CANCELLED)
    }

    fun clearCompleted() {
        val completedIds = activeRuns.filterValues { 
            it.status == RunStatus.COMPLETED || it.status == RunStatus.FAILED || it.status == RunStatus.CANCELLED 
        }.keys
        completedIds.forEach { activeRuns.remove(it) }
        updateFlow()
    }

    private fun updateFlow() {
        _runsFlow.update { activeRuns.toMap() }
    }
}
