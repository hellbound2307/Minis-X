package com.openminis.app.core.execution

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * SQLite table for storing raw tool outputs.
 * This prevents the chat transcript from being bloated with megabytes of JSON/XML.
 * The chat transcript only gets a reference (e.g., "tool output saved as payload_id 1234").
 */
@Entity(tableName = "silent_tool_outputs")
data class SilentToolOutput(
    @PrimaryKey val id: String, // UUID
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "raw_payload") val rawPayload: String,
    @ColumnInfo(name = "exit_code") val exitCode: Int,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long
)
