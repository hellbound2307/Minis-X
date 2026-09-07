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

        // 3. Register MCP servers (appear in servers.json → agent tool surface).
        val mcpIds = ArrayList<String>()
        manifest.mcpServers.forEachIndexed { i, spec ->
            val mcpId = "$MCP_ID_PREFIX${manifest.id}/$i"
            repo.add(
                MCPRepository.MCPServerConfig(
                    id = mcpId,
                    note = "plugin:${manifest.id} v${manifest.version} — ${manifest.name}" +
                        (spec.label?.let { " ($it)" } ?: ""),
                    enabled = true,
                    command = spec.command,
                    args = spec.args,
                    env = spec.env,
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

    /** Uninstall: remove MCP entries + registry record. */
    fun uninstall(id: String): InstallResult {
        val reg = registry ?: return InstallResult(false, "Plugin kernel not initialized")
        val repo = mcp ?: return InstallResult(false, "MCP repository not initialized")
        val plugin = get(id) ?: return InstallResult(false, "Plugin not found: $id")
        for (mcpId in plugin.mcpServerIds) {
            runCatching { repo.delete(mcpId) }
        }
        cache.removeAll { it.manifest.id == id }
        reg.saveAll(cache.toList())
        AppLogger.info(TAG, "plugin $id uninstalled (${plugin.mcpServerIds.size} MCP entries removed)")
        return InstallResult(true, "Plugin '$id' uninstalled.")
    }

    private fun upsert(
        reg: PluginRegistry,
        plugin: PluginRegistry.InstalledPlugin,
    ) {
        cache.removeAll { it.manifest.id == plugin.manifest.id }
        cache.add(plugin)
        reg.saveAll(cache.toList())
    }

    data class InstallResult(val success: Boolean, val message: String)
}
