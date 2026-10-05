package com.openminis.app.core.daemon

import android.content.Context
import com.openminis.app.core.execution.RunId
import com.openminis.app.core.execution.RunRegistry
import com.openminis.app.core.execution.RunStatus
import com.openminis.app.core.execution.SilentToolOutput
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The God-Tier execution loop.
 * This runs completely headless, decoupled from the UI.
 * It handles context fetching, LLM streaming, and tool dispatch.
 */
@Singleton
class DaemonLLMRunner @Inject constructor(
    private val runRegistry: RunRegistry,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val context: Context
) {

    // Emits the current text chunk to the UI while streaming
    private val _streamDeltas = MutableStateFlow<Map<RunId, String>>(emptyMap())
    val streamDeltas: StateFlow<Map<RunId, String>> = _streamDeltas

    suspend fun executeRun(runId: RunId, sessionId: String, prompt: String) {
        try {
            // 1. Fetch historical context from SQLite
            val history = chatRepository.getMessagesForSession(sessionId)
            
            // 2. Fetch the active LLM provider
            val provider = providerRepository.getActiveProvider() ?: throw IllegalStateException("No active provider")
            
            // 3. Initiate the LLM stream
            // In a full implementation, this hooks into the Provider's streaming interface
            var accumulatedText = ""
            
            // Simulated generation loop (replace with actual provider API call)
            // provider.streamCompletion(history + prompt).collect { chunk ->
            //    if (!coroutineContext.isActive) throw CancellationException()
            //    
            //    accumulatedText += chunk
            //    _streamDeltas.value = _streamDeltas.value.toMutableMap().apply { put(runId, accumulatedText) }
            //    
            //    if (chunk.contains("<tool_call>")) {
            //        executeToolSilently(runId, sessionId, "extracted_tool", "extracted_args")
            //    }
            // }

            // 4. Mark completed
            runRegistry.updateStatus(runId, RunStatus.COMPLETED)
            
            // 5. Commit final assistant message to DB
            // chatRepository.insertMessage(sessionId, "assistant", accumulatedText)

        } catch (e: CancellationException) {
            // Run was killed via DaemonClient.stopRun()
            runRegistry.updateStatus(runId, RunStatus.CANCELLED)
        } catch (e: Exception) {
            runRegistry.updateStatus(runId, RunStatus.FAILED, e.message)
        } finally {
            // Clean up stream state
            _streamDeltas.value = _streamDeltas.value.toMutableMap().apply { remove(runId) }
        }
    }

    private suspend fun executeToolSilently(runId: RunId, sessionId: String, toolName: String, args: String) {
        // Here we would use PRootController to execute the shell command
        // val result = pRootController.execute(args)
        
        val rawPayload = "simulated_raw_output_from_$toolName"
        val payloadId = UUID.randomUUID().toString()
        
        val silentOutput = SilentToolOutput(
            id = payloadId,
            runId = runId.value,
            sessionId = sessionId,
            toolName = toolName,
            rawPayload = rawPayload,
            exitCode = 0,
            timestampMs = System.currentTimeMillis()
        )
        
        // Save to SQLite
        // silentStateWriter.insert(silentOutput)
        
        // Send a pristine reference back to the LLM context
        val contextRef = "<tool_result><payload_id>$payloadId</payload_id><summary>Execution complete. Output saved silently.</summary></tool_result>"
        
        // Re-inject contextRef into the LLM loop
    }
}
