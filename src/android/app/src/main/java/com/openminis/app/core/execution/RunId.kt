package com.openminis.app.core.execution

import java.util.UUID

/**
 * Represents a strictly bounded, deterministic execution run.
 * This is the ONLY identifier that has authority to mutate state, spawn subagents,
 * or emit tool results. 
 */
data class RunId(val value: String = UUID.randomUUID().toString()) {
    override fun toString(): String = value
}

enum class RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * State of a specific execution run.
 */
data class RunState(
    val runId: RunId,
    val parentRunId: RunId? = null,
    val status: RunStatus = RunStatus.PENDING,
    val sessionId: String,
    val errorMessage: String? = null
)
