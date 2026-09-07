package com.openminis.app.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-android-plugin-kernel] Persistence for installed plugins.
 *
 * Registry file: `minis-global/plugins/registry.json` (host-side, inside the
 * same minis-global tree the backup covers, so plugin installs SURVIVE restore).
 * Each entry round-trips the validated manifest verbatim plus install state.
 */
class PluginRegistry(private val context: Context) {

    data class InstalledPlugin(
        val manifest: PluginManifest,
        val installedAtMs: Long,
        val status: String, // "installed" | "installing" | "broken"
        val statusDetail: String? = null,
        val mcpServerIds: List<String>,
    )

    private val dir: File get() = File(context.filesDir, "minis-global/plugins")
    private val file: File get() = File(dir, "registry.json")

    fun loadAll(): List<InstalledPlugin> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("plugins") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val manifestJson = o.optJSONObject("manifest") ?: return@mapNotNull null
                runCatching {
                    InstalledPlugin(
                        manifest = PluginManifest.fromJson(manifestJson.toString()),
                        installedAtMs = o.optLong("installedAtMs", 0L),
                        status = o.optString("status", "installed"),
                        statusDetail = o.optString("statusDetail", null),
                        mcpServerIds = o.optJSONArray("mcpServerIds")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optString(it, null) }
                        } ?: emptyList(),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAll(plugins: List<InstalledPlugin>) {
        dir.mkdirs()
        val arr = JSONArray()
        for (p in plugins) {
            arr.put(
                JSONObject()
                    .put("manifest", manifestToJson(p.manifest))
                    .put("installedAtMs", p.installedAtMs)
                    .put("status", p.status)
                    .put("statusDetail", p.statusDetail ?: JSONObject.NULL)
                    .put("mcpServerIds", JSONArray(p.mcpServerIds)),
            )
        }
        file.writeText(JSONObject().put("plugins", arr).toString(2))
    }

    private fun manifestToJson(m: PluginManifest): JSONObject {
        val perms = JSONObject()
            .put("network", JSONArray(m.networkHosts))
            .put("filesystem", JSONArray(m.filesystemScopes))
            .put("shell", m.shell)
        val servers = JSONArray()
        for (s in m.mcpServers) {
            val env = JSONObject()
            s.env.forEach { (k, v) -> env.put(k, v) }
            val o = JSONObject().put("command", s.command).put("args", JSONArray(s.args)).put("env", env)
            if (s.label != null) o.put("label", s.label)
            servers.put(o)
        }
        val root = JSONObject()
            .put("id", m.id)
            .put("name", m.name)
            .put("version", m.version)
            .put("description", m.description)
            .put("author", m.author)
            .put("permissions", perms)
        val hooks = JSONObject()
        if (m.installHook != null) hooks.put("install", m.installHook)
        root.put("hooks", hooks)
        root.put("mcpServers", servers)
        if (m.payload.isNotEmpty()) root.put("payload", JSONArray(m.payload))
        if (m.sourceUrl != null) root.put("source", m.sourceUrl)
        return root
    }
}
