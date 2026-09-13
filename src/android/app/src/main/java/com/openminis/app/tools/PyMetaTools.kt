package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ShellExecutor
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * [T-py-meta-tools] The meta-tool: CRUD + test-run over the PyMetaToolStore.
 *
 * Port of Zafiro's PyMetaToolsBuiltin (architecture, not code). Actions:
 *  - list: registered tools (name/description/params/enabled)
 *  - read: full code of one tool
 *  - write: create or replace (validates via introspection — nothing saves
 *    unless the code defines a callable main with basic-type annotations)
 *  - delete: remove a tool
 *  - test: run draft code or an existing tool with sample args, without saving
 *
 * write成功 = hot registration: the tool joins the agent tool surface on the
 * NEXT model request in the same conversation (the agentTools computed
 * property re-reads the store per turn). That closure is the feature: the
 * agent hits a task needing a helper, mints it, and calls it — all in one
 * session, no app update.
 */
object PyMetaTools {

    const val NAME = "py_meta_tools"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Meta-tool for creating Python tools: a tool used to build other tools. Each created " +
            "tool is a Python source defining main(**kwargs); parameters come from basic type annotations " +
            "on main (str/int/float/bool), the docstring becomes the description. A successfully written " +
            "tool is immediately callable by name in this conversation — use this when the task needs a " +
            "small helper you can reuse across calls (API wrappers, parsers, computations). Actions:\n" +
            "- list: show registered tools (name/description/params/enabled)\n" +
            "- read: return the full code of one tool\n" +
            "- write: create or replace a tool (validated: syntax, main, basic-type annotations; invalid " +
            "code is rejected with a fixable error and nothing is saved)\n" +
            "- delete: remove a tool\n" +
            "- test: run draft code or an existing tool with sample args without saving — test before write\n" +
            "Print from main to return a result; stdout comes back as the tool result.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "One of: list, read, write, delete, test.", enumValues = listOf("list", "read", "write", "delete", "test")),
            "name" to AgentToolParam("string", "Tool name (3-32 chars: lowercase letters/digits/underscores, starts with a letter). Required for read/write/delete/test."),
            "code" to AgentToolParam("string", "Python source defining main(...). Required for write; optional for test (test without name runs this code directly)."),
            "description" to AgentToolParam("string", "Optional. Overrides the docstring-derived description on write."),
            "timeout_seconds" to AgentToolParam("integer", "Optional. Per-call execution timeout for the tool (default 60, max 300)."),
            "enabled" to AgentToolParam("boolean", "Optional. Whether the tool is exposed to the agent surface (default true)."),
            "args" to AgentToolParam("string", "JSON object of sample arguments for test (e.g. {\"city\": \"Algiers\"})."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "name", "code", "description", "timeout_seconds", "enabled", "args"),
    )

    suspend fun execute(argsJson: String, context: Context?): ToolExecutionResult {
        val safeContext = context ?: return ToolExecutionResult("Internal error: no context", false)
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult("Error: arguments must be a JSON object with an 'action' field.", false)
        }
        val action = args.optString("action", "").trim()
        val name = args.optString("name", "").trim()

        return when (action) {
            "list" -> executeList()
            "read" -> executeRead(safeContext, name)
            "write" -> executeWrite(safeContext, args, name)
            "delete" -> executeDelete(safeContext, name)
            "test" -> executeTest(safeContext, args, name)
            else -> ToolExecutionResult(
                "Error: unknown action '$action'. Actions: list, read, write, delete, test.",
                false,
            )
        }
    }

    private fun executeList(): ToolExecutionResult {
        val tools = PyMetaToolStore.tools.value
        if (tools.isEmpty()) {
            return ToolExecutionResult(
                "No Python tools registered yet. Use action=write to mint one: a Python source defining " +
                    "main(**kwargs), parameters from basic type annotations (str/int/float/bool), the " +
                    "docstring becomes the description. Test it first with action=test.",
                true,
            )
        }
        val body = buildString {
            appendLine("Registered Python tools (${tools.size}):")
            tools.sortedBy { it.name }.forEach { t ->
                appendLine("- ${t.name}${if (t.isEnabled) "" else " (disabled)"}: ${t.description.take(200)}")
                val props = t.schema.optJSONObject("properties")
                val keys = props?.keys()?.asSequence()?.toList().orEmpty()
                if (keys.isNotEmpty()) appendLine("  params: ${keys.joinToString(", ")}")
                appendLine("  timeout: ${t.timeoutSeconds}s")
            }
        }
        return ToolExecutionResult(body, true)
    }

    private fun executeRead(context: Context, name: String): ToolExecutionResult {
        if (name.isBlank()) return ToolExecutionResult("Error: 'name' is required for read.", false)
        val code = PyMetaToolStore.readCode(context, name)
            ?: return ToolExecutionResult(
                "No tool named '$name'. Use action=list to see registered tools.",
                false,
            )
        return ToolExecutionResult(code, true)
    }

    private suspend fun executeWrite(context: Context, args: JSONObject, name: String): ToolExecutionResult {
        if (name.isBlank()) return ToolExecutionResult("Error: 'name' is required for write.", false)
        val code = args.optString("code", "")
        if (code.isBlank()) return ToolExecutionResult("Error: 'code' is required for write.", false)
        val description = args.optString("description", "").takeIf { it.isNotBlank() }
        val timeoutSeconds = if (args.has("timeout_seconds") && !args.isNull("timeout_seconds")) {
            args.optInt("timeout_seconds", PyMetaToolStore.DEFAULT_TIMEOUT_S)
        } else null
        val enabled = args.optBoolean("enabled", true)

        return when (val result = PyMetaToolStore.write(context, name, code, description, timeoutSeconds, enabled)) {
            is PyMetaToolStore.WriteResult.Success -> {
                val params = result.tool.schema.optJSONObject("properties")
                    ?.keys()?.asSequence()?.toList().orEmpty()
                ToolExecutionResult(
                    "Tool '${result.tool.name}' registered and immediately callable in this conversation " +
                        "as ${result.tool.name}. Params: ${if (params.isEmpty()) "(none)" else params.joinToString(", ")}. " +
                        "Call it by name like any other tool.",
                    true,
                )
            }
            is PyMetaToolStore.WriteResult.Invalid -> ToolExecutionResult(result.message, false)
            is PyMetaToolStore.WriteResult.Error -> ToolExecutionResult(result.message, false)
        }
    }

    private fun executeDelete(context: Context, name: String): ToolExecutionResult {
        if (name.isBlank()) return ToolExecutionResult("Error: 'name' is required for delete.", false)
        return if (PyMetaToolStore.delete(context, name)) {
            ToolExecutionResult("Tool '$name' deleted.", true)
        } else {
            ToolExecutionResult("No tool named '$name'. Use action=list to see registered tools.", false)
        }
    }

    /**
     * Test-run draft code or an existing tool WITHOUT saving. Existing tool:
     * runs from its saved file. Draft code: passed to the harness via the
     * b64 code override. This is the agent self-debug loop half — test
     * before write.
     */
    private suspend fun executeTest(context: Context, args: JSONObject, name: String): ToolExecutionResult {
        val code = args.optString("code", "").takeIf { it.isNotBlank() }
        val sampleArgs = args.optString("args", "{}").ifBlank { "{}" }
        if (name.isBlank() && code == null) {
            return ToolExecutionResult("Error: test needs a 'name' (existing tool) or 'code' (draft).", false)
        }
        if (name.isNotBlank() && code == null && !PyMetaToolStore.tools.value.any { it.name == name }) {
            return ToolExecutionResult("No tool named '$name' to test. Use action=list, or pass 'code' to test a draft.", false)
        }

        val argsB64 = android.util.Base64.encodeToString(
            sampleArgs.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val codeB64 = code?.let {
            android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }

        val command = buildString {
            append("python3 /usr/local/lib/minis-pytools/harness.py run ")
            append(if (name.isNotBlank()) name else "__draft__")
            append(" ").append(shellSafe(argsB64))
            if (codeB64 != null) append(" ").append(shellSafe(codeB64))
        }

        return try {
            val result = ShellExecutor.execute(
                context = context,
                command = command,
                timeout = 60_000L,
            )
            val status = if (result.exitCode == 0) true else false
            val output = result.output.trim().ifBlank { "(no output)" }
            if (status) {
                ToolExecutionResult(output.take(30_000), true)
            } else {
                ToolExecutionResult("Test FAILED (exit ${result.exitCode}):\n${output.take(10_000)}", false)
            }
        } catch (t: Throwable) {
            AppLogger.warning("PyMetaTools", "test execution failed: ${t.message}")
            ToolExecutionResult("Test execution error: ${t.message ?: t::class.java.simpleName}", false)
        }
    }

    /** Single-quote wrapper for a b64 payload (b64 alphabet is shell-safe). */
    private fun shellSafe(b64: String): String = "'$b64'"
}
