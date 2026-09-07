package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [T-android-plugin-kernel] The plugin manager — install / list / uninstall.
 *
 * Install pipeline (the kernel heart):
 *   manifest JSON → validate → [record "installing"] → run install hook
 *   inside the sandbox (guest package managers) → register MCP servers via
 *   [MCPRepository] (which writes servers.json; the minis-mcp-cli daemon in
 *   the guest picks them up) → [record "installed"].
 *
 * Why MCP is the delivery vehicle: the app already has an MCP client surface
 * (servers.json ↔ minis-mcp-cli daemon ↔ agent prompt disclosure). A plugin's
 * `mcpServers` entries land in the same registry the user manages by hand, so
 * plugin tools are agent-callable with ZERO new runtime code per plugin. The
 * APK is infrastructure; the plugin is data.
 *
 * Uninstall reverses exactly what was recorded: MCP entries removed, hook
 * files left in place (sandbox files are cheap; a future "purge" flag can
 * `rm -rf` the plugin's workspace dir).
 */
object PluginManager {

    private const val TAG = "PluginManager"
    private const val MCP_ID_PREFIX = "plugins/"
    private const val HOOK_TIMEOUT_MS = 300_000L

    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var registry: PluginRegistry? = null

    @Volatile
    private var mcp: MCPRepository? = null

    private val cache = CopyOnWriteArrayList<PluginRegistry.InstalledPlugin>()

    /** Called from MinisApp.onCreate (after MCPRepository init). */
    fun init(context: Context, mcpRepository: MCPRepository) {
        appContext = context.applicationContext
        registry = PluginRegistry(context)
        mcp = mcpRepository
        cache.clear()
        cache.addAll(registry!!.loadAll())
        AppLogger.info(TAG, "plugin registry loaded: ${cache.size} plugin(s)")
    }

    private fun ctx(): Context = appContext
        ?: throw IllegalStateException("PluginManager not initialized (called before MinisApp.onCreate)")

    /** App context for tool callers (PluginTools) after init. */
    fun context(): Context = ctx()

    fun list(): List<PluginRegistry.InstalledPlugin> = cache.toList()

    fun get(id: String): PluginRegistry.InstalledPlugin? = cache.firstOrNull { it.manifest.id == id }

    /**
     * Install a plugin from a manifest JSON string (typically authored by the
     * agent in the sandbox, or imported from a URL/catalog by the UI).
     *
     * @param manifestJson raw manifest text
     * @param sessionId session whose shell runs the install hook
     */
    suspend fun install(
        sessionId: String,
        manifestJson: String,
    ): InstallResult = mutex.withLock {
        val reg = registry ?: return@withLock InstallResult(false, "Plugin kernel not initialized")
        val repo = mcp ?: return@withLock InstallResult(false, "MCP repository not initialized")
        val context = ctx()

        // 1. Validate — strict; any throw here is the user's safety net.
        val manifest = try {
            PluginManifest.fromJson(manifestJson)
        } catch (e: Exception) {
            return@withLock InstallResult(false, "Invalid manifest: ${e.message}")
        }
        if (get(manifest.id)?.status == "installed") {
            return@withLock InstallResult(
                false,
                "Plugin '${manifest.id}' is already installed (v${get(manifest.id)!!.manifest.version}). " +
                    "Uninstall it first to reinstall/upgrade.",
            )
        }

        val installed = PluginRegistry.InstalledPlugin(
            manifest = manifest,
            installedAtMs = System.currentTimeMillis(),
            status = "installing",
            mcpServerIds = manifest.mcpServers.mapIndexed { i, _ ->
                "$MCP_ID_PREFIX${manifest.id}/$i"
            },
        )
        upsert(reg, installed)

        // 2. Install hook inside the sandbox (guest package managers).
        if (manifest.installHook != null) {
            val hookResult = ExecutionCoordinator.execute(
                sessionId = sessionId,
                command = manifest.installHook,
                timeout = HOOK_TIMEOUT_MS,
            )
            if (hookResult.exitCode != 0) {
                val detail = "install hook failed (exit ${hookResult.exitCode}): " +
                    hookResult.output.take(400)
                upsert(reg, installed.copy(status = "broken", statusDetail = detail))
                AppLogger.error(TAG, "plugin ${manifest.id} hook failed: $detail")
                return@withLock InstallResult(false, detail)
            }
        }

        // 2b. [T-plugin-payload-persistence] Copy declared payload files into
        // APP-SIDE storage (filesDir/minis-global/plugins/payloads/<id>) and
        // rewrite the "$PAYLOAD" placeholder in args/env to the in-guest
        // mount (/var/minis/plugins/<id>). The payload survives rootfs
        // resets — GLM round-3 finding: workspace wipes orphaned plugins
        // whose server scripts lived only in the sandbox.
        val payloadErrors = copyPayload(context, sessionId, manifest)
        if (payloadErrors.isNotEmpty()) {
            val detail = "payload copy failed: ${payloadErrors.joinToString("; ")}"
            upsert(reg, installed.copy(status = "broken", statusDetail = detail))
            return@withLock InstallResult(false, detail)
        }

        // 2c. Write the policy file next to servers.json (the daemon reads
        // the same dir): per-plugin declared permissions for audit + future
        // runtime enforcement hooks.
        writePolicyFile(context, manifest)

        // 3. Register MCP servers (appear in servers.json → agent tool surface).
        // env gains MINIS_PLUGIN_ID + scrubbed PATH so the daemon spawns the
        // plugin with a minimal, auditable environment (no inherited secrets).
        val guestPayloadDir = "$GUEST_PAYLOAD_BASE/${manifest.id}"
        val mcpIds = ArrayList<String>()
        manifest.mcpServers.forEachIndexed { i, spec ->
            val mcpId = "$MCP_ID_PREFIX${manifest.id}/$i"
            val rewrittenArgs = spec.args.map { it.replace(PAYLOAD_PLACEHOLDER, guestPayloadDir) }
            val rewrittenEnv = spec.env.mapValues { (_, v) -> v.replace(PAYLOAD_PLACEHOLDER, guestPayloadDir) } +
                mapOf(
                    "MINIS_PLUGIN_ID" to manifest.id,
                    "MINIS_PLUGIN_VERSION" to manifest.version,
                    "PATH" to PLUGIN_SAFE_PATH,
                )
            repo.add(
                MCPRepository.MCPServerConfig(
                    id = mcpId,
                    note = "plugin:${manifest.id} v${manifest.version} — ${manifest.name}" +
                        (spec.label?.let { " ($it)" } ?: "") +
                        " [net:" + (manifest.networkHosts.joinToString("|").ifEmpty { "none" }) +
                        " fs:" + (manifest.filesystemScopes.joinToString("|").ifEmpty { "none" }) + "]",
                    enabled = true,
                    command = spec.command,
                    args = rewrittenArgs,
                    env = rewrittenEnv,
                ),
            )
            mcpIds.add(mcpId)
        }

        // 4. Mark installed.
        upsert(reg, installed.copy(status = "installed", mcpServerIds = mcpIds))
        AppLogger.info(
            TAG,
            "plugin ${manifest.id} v${manifest.version} installed: ${mcpIds.size} MCP server(s), " +
                "perms: net=${manifest.networkHosts} fs=${manifest.filesystemScopes} shell=${manifest.shell}",
        )
        InstallResult(
            true,
            "Plugin '${manifest.id}' v${manifest.version} installed. " +
                "${mcpIds.size} MCP server(s) registered (${mcpIds.joinToString(", ")}). " +
                (if (manifest.installHook != null) "Install hook ran OK. " else "") +
                "Tools are live for NEW agent turns (existing streaming turns keep their old tool list).",
        )
    }

    /** Uninstall: remove MCP entries + registry record + payload + policy. */
    fun uninstall(id: String): InstallResult {
        val reg = registry ?: return InstallResult(false, "Plugin kernel not initialized")
        val repo = mcp ?: return InstallResult(false, "MCP repository not initialized")
        val context = ctx()
        val plugin = get(id) ?: return InstallResult(false, "Plugin not found: $id")
        for (mcpId in plugin.mcpServerIds) {
            runCatching { repo.delete(mcpId) }
        }
        cache.removeAll { it.manifest.id == id }
        reg.saveAll(cache.toList())
        runCatching { hostPayloadDir(context, id).deleteRecursively() }
        removePolicyFile(context, id)
        AppLogger.info(TAG, "plugin $id uninstalled (${plugin.mcpServerIds.size} MCP entries removed)")
        return InstallResult(true, "Plugin '$id' uninstalled (MCP entries + payload + policy removed).")
    }

    /**
     * Enable/disable every MCP entry of a plugin — the kill switch. Effect:
     * the plugin's tools vanish from the agent's next turn (daemon skips
     * disabled servers) without uninstalling anything.
     */
    fun setEnabled(id: String, enabled: Boolean): InstallResult {
        val repo = mcp ?: return InstallResult(false, "MCP repository not initialized")
        val plugin = get(id) ?: return InstallResult(false, "Plugin not found: $id")
        var n = 0
        for (mcpId in plugin.mcpServerIds) {
            runCatching { repo.setEnabled(mcpId, enabled) }
            n++
        }
        AppLogger.info(TAG, "plugin $id setEnabled=$enabled ($n entries)")
        return InstallResult(true, "Plugin '$id' ${if (enabled) "enabled" else "DISABLED"} ($n MCP entries).")
    }

    /**
     * [T-plugin-doctor] Health check + repair. For each installed plugin:
     *  - MCP entries still registered? (re-add if missing)
     *  - payload files present? (plugin is broken after a wipe; report with
     *    reinstall hint when sourceUrl exists)
     *  - guest deps resolvable? (command -v for each distinct server command)
     */
    suspend fun doctor(context: Context, sessionId: String): DoctorReport {
        val plugins = list()
        if (plugins.isEmpty()) return DoctorReport(0, emptyList(), "No plugins installed.")
        val lines = ArrayList<String>()
        var healthy = 0
        for (p in plugins) {
            val m = p.manifest
            val issues = ArrayList<String>()

            // Payload presence.
            val dir = hostPayloadDir(context, m.id)
            if (m.payload.isNotEmpty() && (!dir.exists() || dir.list().isNullOrEmpty())) {
                issues.add("payload missing (rootfs/workspace reset?)")
            }

            // MCP entries present?
            val missingMcp = p.mcpServerIds.filter { repo?.get(it) == null }
            if (missingMcp.isNotEmpty()) {
                issues.add("MCP entries missing: ${missingMcp.joinToString(",")}")
                // Re-register from the stored manifest.
                mcp?.let { repo ->
                    val guestPayloadDir = "$GUEST_PAYLOAD_BASE/${m.id}"
                    m.mcpServers.forEachIndexed { i, spec ->
                        val mcpId = "$MCP_ID_PREFIX${m.id}/$i"
                        repo.add(
                            MCPRepository.MCPServerConfig(
                                id = mcpId,
                                note = "plugin:${m.id} v${m.version} — ${m.name} (repaired)",
                                enabled = true,
                                command = spec.command,
                                args = spec.args.map { it.replace(PAYLOAD_PLACEHOLDER, guestPayloadDir) },
                                env = spec.env.mapValues { (_, v) -> v.replace(PAYLOAD_PLACEHOLDER, guestPayloadDir) } +
                                    mapOf("MINIS_PLUGIN_ID" to m.id, "MINIS_PLUGIN_VERSION" to m.version),
                            ),
                        )
                    }
                    issues.add("→ MCP entries re-registered")
                }
            }

            // Guest dep check (command -v for each distinct command).
            val commands = m.mcpServers.map { it.command }.distinct()
            if (commands.isNotEmpty()) {
                val check = "for c in ${commands.joinToString(" ")}; do command -v \"\$c\" >/dev/null 2>&1 || echo \"MISSING:\$c\"; done"
                val r = ExecutionCoordinator.execute(sessionId = sessionId, command = check, timeout = 15_000L)
                val missing = r.output.lines().filter { it.startsWith("MISSING:") }.map { it.removePrefix("MISSING:") }
                if (missing.isNotEmpty()) issues.add("guest commands missing: ${missing.joinToString(",")} (reinstall deps)")
            }

            if (issues.isEmpty()) {
                healthy++
                lines.add("✓ ${m.id} v${m.version} — healthy")
            } else {
                lines.add("⚠ ${m.id} v${m.version} — ${issues.joinToString("; ")}" +
                    (m.sourceUrl?.let { " (reinstall: plugin_reinstall $it)" } ?: ""))
            }
        }
        return DoctorReport(plugins.size, lines, "$healthy/${plugins.size} plugin(s) healthy")
    }

    data class DoctorReport(val total: Int, val lines: List<String>, val summary: String)

    private fun upsert(
        reg: PluginRegistry,
        plugin: PluginRegistry.InstalledPlugin,
    ) {
        cache.removeAll { it.manifest.id == plugin.manifest.id }
        cache.add(plugin)
        reg.saveAll(cache.toList())
    }

    // -- Payload persistence ([T-plugin-payload-persistence]) --

    private const val PAYLOAD_PLACEHOLDER = "\$PAYLOAD"
    private const val GUEST_PAYLOAD_BASE = "/var/minis/plugins"
    private const val PLUGIN_SAFE_PATH =
        "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin:/opt/current/bin"

    private fun hostPayloadDir(context: Context, id: String): File =
        File(context.filesDir, "minis-global/plugins/payloads/$id")

    /**
     * Copy declared payload entries (sandbox paths) into app-side storage.
     * Paths resolve through the session host mapping like file_read. A
     * declared path that doesn't exist is an error — better to fail the
     * install than ship a plugin whose server script is missing.
     */
    private fun copyPayload(
        context: Context,
        sessionId: String,
        manifest: PluginManifest,
    ): List<String> {
        if (manifest.payload.isEmpty()) return emptyList()
        val dest = hostPayloadDir(context, manifest.id)
        dest.deleteRecursively()
        dest.mkdirs()
        val errors = ArrayList<String>()
        for (rel in manifest.payload) {
            try {
                val src = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
                    sessionId, rel, context,
                ) ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(rel)
                if (src == null || !src.exists()) {
                    errors.add("$rel (not found)")
                    continue
                }
                val target = File(dest, rel.trimStart('/').replace("..", "_"))
                if (src.isDirectory) src.copyRecursively(target, overwrite = true)
                else {
                    target.parentFile?.mkdirs()
                    src.copyTo(target, overwrite = true)
                }
            } catch (e: Exception) {
                errors.add("$rel (${e.message})")
            }
        }
        return errors
    }

    private fun writePolicyFile(context: Context, manifest: PluginManifest) {
        runCatching {
            val policyDir = File(context.filesDir, "minis-global/mcp-servers")
            policyDir.mkdirs()
            val file = File(policyDir, "plugin-policy.json")
            val root = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()
            val entry = JSONObject()
                .put("network", JSONArray(manifest.networkHosts))
                .put("filesystem", JSONArray(manifest.filesystemScopes))
                .put("shell", manifest.shell)
                .put("version", manifest.version)
                .put("updatedAt", System.currentTimeMillis())
            root.put(manifest.id, entry)
            file.writeText(root.toString(2))
        }.onFailure { AppLogger.warning(TAG, "policy write failed: ${it.message}") }
    }

    private fun removePolicyFile(context: Context, id: String) {
        runCatching {
            val file = File(context.filesDir, "minis-global/mcp-servers/plugin-policy.json")
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            root.remove(id)
            file.writeText(root.toString(2))
        }
    }

    /** App-side payload dir for [id] (used by doctor + reinstall). */
    fun payloadDir(context: Context, id: String): File = hostPayloadDir(context, id)

    data class InstallResult(val success: Boolean, val message: String)
}
