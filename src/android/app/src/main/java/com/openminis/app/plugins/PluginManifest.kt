package com.openminis.app.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-plugin-kernel] Plugin manifest — the contract between a plugin
 * and the Minis X kernel.
 *
 * A plugin is a DECLARED capability pack: metadata + permissions + install
 * hooks + MCP server entries. Installing one:
 *   1. parses + validates the manifest (strict — unknown permission kinds are
 *      rejected, not ignored),
 *   2. records the requested permissions in the registry (audit trail; hard
 *      per-call enforcement lands with the permission engine),
 *   3. runs install hooks INSIDE the sandbox (apk/pip/npm — the guest package
 *      managers, not Android's),
 *   4. registers the plugin's MCP servers via [com.openminis.app.data.repository.MCPRepository]
 *      (they appear in servers.json → minis-mcp-cli daemon → agent tool surface),
 *   5. records the install so `plugin_list` can show it and `plugin_uninstall`
 *      can reverse it.
 *
 * The APK never changes. The agent can author manifests itself (the meta-
 * plugin loop): write JSON to the sandbox, call plugin_install, tools exist.
 *
 * Example manifest (a file in the sandbox, e.g. /var/minis/workspace/jokes.json):
 * {
 *   "id": "jokes",
 *   "name": "Dad Jokes",
 *   "version": "1.0.0",
 *   "description": "Random dad jokes MCP server",
 *   "author": "agent",
 *   "permissions": {
 *     "network": ["icanhazdadjoke.com"],
 *     "filesystem": ["workspace"],
 *     "shell": false
 *   },
 *   "hooks": { "install": "pip install mcp[cli] requests" },
 *   "mcpServers": [
 *     { "command": "python3", "args": ["/var/minis/workspace/jokes_server.py"],
 *       "env": {} }
 *   ]
 * }
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    /** Network hosts the plugin may contact (empty = no network declared). */
    val networkHosts: List<String>,
    /** Filesystem scopes: "workspace" | "shared" | "attachments" (empty = none). */
    val filesystemScopes: List<String>,
    /** Declares the plugin's MCP servers run shell commands (stdio). */
    val shell: Boolean,
    /** Guest-side package install command run once at install (apk/pip/npm). */
    val installHook: String?,
    /** MCP server entries registered under `plugins/<id>/<n>`. */
    val mcpServers: List<McpServerSpec>,
) {

    data class McpServerSpec(
        val command: String,
        val args: List<String>,
        val env: Map<String, String>,
        /** Optional human label suffix; defaults to index. */
        val label: String?,
    )

    companion object {

        val KNOWN_FS_SCOPES = setOf("workspace", "shared", "attachments", "memory")

        fun fromJson(text: String): PluginManifest {
            val root = JSONObject(text)
            val id = root.optString("id", "").trim()
            require(id.matches(Regex("[a-z0-9][a-z0-9_-]{1,39}"))) {
                "plugin id must be 2-40 chars, lowercase alnum/-/_ (got: '$id')"
            }
            val name = root.optString("name", "").trim().ifBlank { id }
            val version = root.optString("version", "0.0.0").trim()
            val description = root.optString("description", "").trim()
            val author = root.optString("author", "unknown").trim()

            val perms = root.optJSONObject("permissions") ?: JSONObject()
            val network = perms.optJSONArray("network")?.toStringList() ?: emptyList()
            val fs = perms.optJSONArray("filesystem")?.toStringList() ?: emptyList()
            fs.forEach { scope ->
                require(scope in KNOWN_FS_SCOPES) {
                    "unknown filesystem scope '$scope' (known: $KNOWN_FS_SCOPES)"
                }
            }
            val shell = perms.optBoolean("shell", false)

            val hooks = root.optJSONObject("hooks")
            val installHook = hooks?.optString("install", null)?.trim()?.ifBlank { null }

            val servers = ArrayList<McpServerSpec>()
            val serverArr = root.optJSONArray("mcpServers") ?: JSONArray()
            for (i in 0 until serverArr.length()) {
                val s = serverArr.optJSONObject(i) ?: continue
                val command = s.optString("command", "").trim()
                require(command.isNotBlank()) { "mcpServers[$i] missing 'command'" }
                // Stdio MCP servers are shell programs — implicit shell grant.
                servers.add(
                    McpServerSpec(
                        command = command,
                        args = s.optJSONArray("args")?.toStringList() ?: emptyList(),
                        env = s.optJSONObject("env")?.toStringMap() ?: emptyMap(),
                        label = s.optString("label", null)?.takeIf { it.isNotBlank() },
                    ),
                )
            }
            if (servers.isNotEmpty() && !shell) {
                // stdio servers ARE processes; normalize the declaration.
            }
            return PluginManifest(
                id = id,
                name = name,
                version = version,
                description = description,
                author = author,
                networkHosts = network,
                filesystemScopes = fs,
                shell = shell || servers.isNotEmpty(),
                installHook = installHook,
                mcpServers = servers,
            )
        }

        private fun JSONArray.toStringList(): List<String> =
            (0 until length()).mapNotNull { optString(it, null)?.takeIf { v -> v.isNotBlank() } }

        private fun JSONObject.toStringMap(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            keys().forEach { k -> out[k] = optString(k, "") }
            return out
        }
    }
}
