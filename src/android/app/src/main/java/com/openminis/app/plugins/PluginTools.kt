package com.openminis.app.plugins

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult

/**
 * [T-android-plugin-kernel] Agent-facing plugin tools.
 *
 * The point of the kernel: the AGENT (or the user) installs a capability pack
 * at runtime from a manifest — no APK rebuild, no new Kotlin. The meta-plugin
 * loop is closed: the agent writes a manifest + server script into the
 * sandbox, calls plugin_install, the tools exist.
 *
 * Install pipeline is strict by design: invalid manifests are rejected with
 * the reason, permissions are recorded for audit, hooks run inside the
 * sandbox (guest package managers only), MCP entries are user-visible in
 * Settings → MCP Integrations and reversible with plugin_uninstall.
 */
object PluginTools {

    private const val PREFIX = "plugin_"

    /** Session id of the in-flight tool call, set by ChatViewModel dispatch. */
    val currentSessionId = ThreadLocal<String>()

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = "${PREFIX}install",
            description = "Install a plugin from a manifest JSON — adds new tools to " +
                "the agent at runtime WITHOUT an app update. The manifest declares: " +
                "metadata (id/name/version), permissions (network hosts, filesystem " +
                "scopes, shell), an optional install hook (run inside the sandbox, e.g. " +
                "'pip install mcp requests'), and one or more MCP server entries " +
                "(stdio command+args, or http url) that become agent-callable tools. " +
                "Typical flow: write the server script + manifest into /var/minis/workspace " +
                "with file_write, then pass the manifest path (or inline JSON) here. " +
                "Permissions are recorded and the install is reversible with plugin_uninstall. " +
                "Be conservative with permissions: declare only what the plugin needs.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary (e.g. 'Install dad-jokes plugin')."),
                "manifest" to AgentToolParam("string", "The manifest: either inline JSON text, or a sandbox path (/var/minis/**) to a .json file containing it."),
            ),
            required = listOf("tool_title", "manifest"),
            propertyOrdering = listOf("tool_title", "manifest"),
        ),
        AgentToolDefinition(
            name = "${PREFIX}list",
            description = "List installed plugins: id, version, permissions, MCP server entries, status.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = "${PREFIX}uninstall",
            description = "Uninstall a plugin by id — removes its MCP server entries and registry record.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
                "id" to AgentToolParam("string", "The plugin id to uninstall."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
        AgentToolDefinition(
            name = "${PREFIX}manifest_schema",
            description = "Return the plugin manifest JSON schema with a complete example. " +
                "Consult this BEFORE authoring a plugin so the manifest validates on the first try.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = "${PREFIX}doctor",
            description = "Health-check all installed plugins: payload presence, MCP entry " +
                "registration (auto-repairs missing entries), and guest dependency availability. " +
                "Run this after a sandbox reset or when a plugin's tools stopped working.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = "${PREFIX}reinstall",
            description = "Reinstall a plugin from its source manifest URL (https). Use after " +
                "a payload wipe or to update to the latest published version. Fetches the " +
                "manifest, validates it, and runs the normal install pipeline (deps, payload, " +
                "MCP registration).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
                "url" to AgentToolParam("string", "https URL of the manifest JSON."),
            ),
            required = listOf("tool_title", "url"),
            propertyOrdering = listOf("tool_title", "url"),
        ),
        AgentToolDefinition(
            name = "${PREFIX}enable",
            description = "Enable or disable a plugin (kill switch). Disabled plugins' MCP " +
                "entries are switched off — their tools vanish from the agent's next turn " +
                "without uninstalling. Use 'disable' the moment a plugin misbehaves.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary."),
                "id" to AgentToolParam("string", "The plugin id."),
                "enabled" to AgentToolParam("boolean", "true to enable, false to disable."),
            ),
            required = listOf("tool_title", "id", "enabled"),
            propertyOrdering = listOf("tool_title", "id", "enabled"),
        ),
    )

    fun execute(name: String, argsJson: String): ToolExecutionResult {
        val args = try { org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
        return when (name) {
            "${PREFIX}list" -> executeList()
            "${PREFIX}uninstall" -> {
                val id = args.optString("id", "").trim()
                if (id.isBlank()) ToolExecutionResult("Error: 'id' is required", false)
                else {
                    val r = PluginManager.uninstall(id)
                    ToolExecutionResult(r.message, r.success)
                }
            }
            "${PREFIX}manifest_schema" -> ToolExecutionResult(MANIFEST_SCHEMA_DOC, true)
            "${PREFIX}doctor" -> {
                // Suspend path: guest dep probes round-trip the shell.
                kotlinx.coroutines.runBlocking {
                    val r = PluginManager.doctor(
                        context = PluginManager.context(),
                        sessionId = currentSessionId.get(),
                    )
                    ToolExecutionResult("${r.summary}\n${r.lines.joinToString("\n")}", true)
                }
            }
            "${PREFIX}reinstall" -> {
                val url = args.optString("url", "").trim()
                if (!url.startsWith("https://")) {
                    ToolExecutionResult("Error: 'url' must be https", false)
                } else {
                    kotlinx.coroutines.runBlocking {
                        val manifestText = try {
                            fetchManifest(url)
                        } catch (e: Exception) {
                            return@runBlocking ToolExecutionResult("Fetch failed: ${e.message}", false)
                        }
                        val r = PluginManager.install(sessionId = currentSessionId.get(), manifestJson = manifestText)
                        ToolExecutionResult(r.message, r.success)
                    }
                }
            }
            "${PREFIX}enable" -> {
                val id = args.optString("id", "").trim()
                val enabled = args.optBoolean("enabled", true)
                if (id.isBlank()) ToolExecutionResult("Error: 'id' is required", false)
                else {
                    val r = PluginManager.setEnabled(id, enabled)
                    ToolExecutionResult(r.message, r.success)
                }
            }
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    }

    private fun fetchManifest(url: String): String {
        val request = okhttp3.Request.Builder().url(url).get().build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string().orEmpty().ifBlank { throw Exception("empty manifest") }
        }
    }

    private fun executeList(): ToolExecutionResult {
        val plugins = PluginManager.list()
        if (plugins.isEmpty()) {
            return ToolExecutionResult(
                "No plugins installed. Use plugin_manifest_schema to author one, then plugin_install. " +
                    "Remember: the MCP entries a plugin registers can also be managed by the user in " +
                    "Settings → MCP Integrations.",
                true,
            )
        }
        val body = plugins.joinToString("\n\n") { p ->
            val m = p.manifest
            buildString {
                append("${m.id} v${m.version} — ${m.name} [${p.status}]")
                append("\n  ${m.description.take(160)}")
                append("\n  by ${m.author} · installed ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(p.installedAtMs))}")
                append("\n  permissions: network=${if (m.networkHosts.isEmpty()) "none" else m.networkHosts.joinToString(",")} " +
                    "fs=${if (m.filesystemScopes.isEmpty()) "none" else m.filesystemScopes.joinToString(",")} shell=${m.shell}")
                if (p.mcpServerIds.isNotEmpty()) append("\n  mcp: ${p.mcpServerIds.joinToString(", ")}")
                if (p.statusDetail != null) append("\n  detail: ${p.statusDetail.take(200)}")
            }
        }
        return ToolExecutionResult(body, true)
    }

    /**
     * Suspend install path (executeTool is suspend, so the hook's shell
     * round-trip is awaited properly). Manifest is inline JSON or a sandbox
     * path resolving through the session host mapping (same semantics as
     * file_read).
     */
    suspend fun executeInstall(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = try { org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
        val raw = args.optString("manifest", "").trim()
        if (raw.isBlank()) {
            return ToolExecutionResult("Error: 'manifest' is required (inline JSON or sandbox path)", false)
        }
        val manifestText = try {
            resolveManifestText(raw, sessionId)
        } catch (e: Exception) {
            return ToolExecutionResult("Manifest not readable: ${e.message}", false)
        }
        val result = PluginManager.install(sessionId = sessionId, manifestJson = manifestText)
        return ToolExecutionResult(result.message, result.success)
    }

    /**
     * Manifest is either inline JSON or a sandbox path. Sandbox paths resolve
     * through the session host mapping (same semantics as file_read).
     */
    private fun resolveManifestText(raw: String, sessionId: String): String {
        if (raw.trimStart().startsWith("{")) return raw
        val sandboxPath = raw.removePrefix("minis://")
        val appContext = PluginManager.context()
        val hostFile = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
            sessionId, sandboxPath, appContext,
        ) ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(sandboxPath)
            ?: throw IllegalArgumentException("Manifest file not found: $raw")
        return hostFile.readText()
    }

    private const val MANIFEST_SCHEMA_DOC = """Plugin manifest schema (JSON):

{
  "id": "my-plugin",              // required: 2-40 chars [a-z0-9_-], unique
  "name": "My Plugin",            // display name (defaults to id)
  "version": "1.0.0",             // semver-ish
  "description": "What it adds",  // shown in plugin_list and Settings
  "author": "you",
  "permissions": {
    "network": ["api.example.com"],  // hosts the plugin may contact
    "filesystem": ["workspace"],     // scopes: workspace|shared|attachments|memory
    "shell": false                   // declared shell use (stdio MCP implies true)
  },
  "hooks": {
    "install": "pip install mcp requests"  // optional; runs INSIDE the sandbox shell
  },
  "payload": [                              // optional; files/dirs copied APP-SIDE
    "/var/minis/workspace/my_plugin"        // at install — survive sandbox resets;
  ],                                        // referenced as "$PAYLOAD" in args/env
  "source": "https://raw.githubusercontent.com/you/repo/main/plugin.json",  // optional;
                                            // enables plugin_reinstall + provenance
  "mcpServers": [                  // 1+ servers; these become agent tools
    {
      "command": "python3",
      "args": ["$PAYLOAD/server.py"],
      "env": {"PLUGIN_DATA": "$PAYLOAD"},
      "label": "main"
    }
  ]
}

Rules:
- id must match [a-z0-9][a-z0-9_-]{1,39}; filesystem scopes are a fixed enum.
- Unknown permission kinds are REJECTED (strict, not ignored).
- "$PAYLOAD" in args/env is rewritten to /var/minis/plugins/<id> (app-side,
  reset-proof mount).
- Plugin server processes run with a SCRUBBED environment: only PATH, HOME,
  TMPDIR, LANG, PYTHONUNBUFFERED, MINIS_PLUGIN_ID, MINIS_PLUGIN_VERSION —
  your session's API keys/tokens are NOT inherited. Fetch what you need
  from declared env entries.
- Stdio mcpServers run as guest processes via minis-mcp-cli (already in the rootfs).
- HTTP servers: {"url": "https://...", "headers": {...}} instead of command/args.
- Install hooks: guest package managers only (apk/pip/npm/python3). No sudo.
- Everything is reversible: plugin_uninstall removes MCP entries + payload +
  policy; plugin_enable false is the instant kill switch; plugin_doctor
  repairs missing MCP entries and reports wiped payloads."""
}
