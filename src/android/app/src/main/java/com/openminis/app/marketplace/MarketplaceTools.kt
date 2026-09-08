package com.openminis.app.marketplace

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.plugins.PluginManager
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [T-android-marketplace] Curated plugin catalog — browse + safe install.
 *
 * The catalog is a versioned index.json in the Minis-X repo
 * (marketplace/index.json). Each entry points at a manifest URL (raw
 * GitHub) plus a baseUrl for payload files. Install flow:
 *
 *   1. fetch index (5-minute in-memory cache), find the entry
 *   2. fetch the manifest; fetch payload files next to it
 *   3. stage payload into /var/minis/workspace/marketplace/<id>/ (sandbox)
 *   4. rewrite manifest payload paths to the staged absolute paths and
 *      stamp `source` = manifestUrl (so plugin_reinstall works forever)
 *   5. PluginManager.install — the kernel validates strictly, runs the
 *      permission-diff update gate (an update can never silently grow
 *      permissions), runs hooks, registers MCP entries
 *
 * v1 is deliberately CURATED ONLY: index entries are hand-picked and
 * `verified` is an authoring-time claim, not a runtime check. Signature
 * verification lands with the key ceremony; network/fs enforcement is
 * already live via netguard + env scrub, which is what makes even this
 * curated-first model defensible.
 */
object MarketplaceTools {

    private const val TAG = "Marketplace"
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/hellbound2307/Minis-X/main/marketplace/index.json"
    private const val CACHE_MS = 5 * 60_000L
    private const val MAX_ENTRY_CHARS = 24_000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cachedIndex: Pair<Long, String>? = null

    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val verified: Boolean,
        val tags: List<String>,
        val manifestUrl: String,
        val baseUrl: String,
    )

    private fun fetchIndex(force: Boolean = false): String {
        val cached = cachedIndex
        if (!force && cached != null && System.currentTimeMillis() - cached.first < CACHE_MS) {
            return cached.second
        }
        val req = Request.Builder().url(INDEX_URL).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("catalog fetch failed: HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            cachedIndex = System.currentTimeMillis() to body
            return body
        }
    }

    private fun parseEntries(indexText: String): List<Entry> {
        val root = JSONObject(indexText)
        val arr = root.optJSONArray("plugins") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                version = o.optString("version", ""),
                description = o.optString("description", ""),
                author = o.optString("author", ""),
                verified = o.optBoolean("verified", false),
                tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null) } } ?: emptyList(),
                manifestUrl = o.optString("manifestUrl", ""),
                baseUrl = o.optString("baseUrl", ""),
            )
        }.filter { it.id.isNotBlank() && it.manifestUrl.isNotBlank() }
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }

    // MARK: - public API (UI + tools)

    /** Fetch (cached) + parse the catalog. Public for the Marketplace UI screen. */
    suspend fun catalogEntries(force: Boolean = false): List<Entry> =
        withContext(Dispatchers.IO) { parseEntries(fetchIndex(force)) }

    fun executeBrowse(argsJson: String): ToolExecutionResult {
        val args = try { org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
        val query = args.optString("query", "").trim().lowercase()
        return try {
            val entries = parseEntries(fetchIndex())
            val filtered = if (query.isBlank()) entries else entries.filter {
                it.id.contains(query) || it.name.lowercase().contains(query) ||
                    it.description.lowercase().contains(query) || it.tags.any { t -> t.lowercase().contains(query) }
            }
            if (filtered.isEmpty()) {
                ToolExecutionResult("No catalog plugins match '$query'.", true)
            } else {
                val body = filtered.joinToString("\n\n") { e ->
                    "${if (e.verified) "✓" else "·"} ${e.id} v${e.version} — ${e.name}\n" +
                        "  ${e.description}\n" +
                        "  by ${e.author} | tags: ${e.tags.joinToString(", ")} | install: marketplace_install {\"id\":\"${e.id}\"}"
                }
                ToolExecutionResult("Minis X catalog (${filtered.size}):\n\n$body".take(MAX_ENTRY_CHARS), true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Catalog browse failed: ${e.message}", false)
        }
    }

    // MARK: - tool: marketplace_install

    suspend fun executeInstall(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = try { org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
        val id = args.optString("id", "").trim()
        if (id.isBlank()) return ToolExecutionResult("Error: 'id' is required", false)
        if (sessionId.isBlank()) return ToolExecutionResult("No active session.", false)

        return try {
            val entry = parseEntries(fetchIndex()).firstOrNull { it.id == id }
                ?: return ToolExecutionResult("'$id' not in the catalog. Use marketplace_browse to list entries.", false)

            // 1. Manifest.
            val manifestText = httpGet(entry.manifestUrl)
            val manifest = org.json.JSONObject(manifestText)

            // 2. Stage payload files next to the manifest into the session
            //    workspace, then rewrite payload paths to the staged abs paths.
            val payload = manifest.optJSONArray("payload")
            if (payload != null && payload.length() > 0) {
                val stagedDir = "/var/minis/workspace/marketplace/$id"
                val mk = ExecutionCoordinator.execute(sessionId, "mkdir -p $stagedDir", 10_000L)
                if (mk.exitCode != 0) {
                    return ToolExecutionResult("Could not stage payload dir: ${mk.output.take(200)}", false)
                }
                val newPayload = JSONArray()
                for (i in 0 until payload.length()) {
                    val rel = payload.optString(i, "").trimStart('/')
                    if (rel.isBlank()) continue
                    val fileUrl = entry.baseUrl.trimEnd('/') + "/" + rel
                    val content = httpGet(fileUrl)
                    // Stage via the sandbox shell: base64 round-trip keeps
                    // arbitrary text (code) byte-exact without quoting pain.
                    val b64 = android.util.Base64.encodeToString(
                        content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
                    )
                    val write = "echo $b64 | base64 -d > $stagedDir/$rel"
                    val w = ExecutionCoordinator.execute(sessionId, write, 15_000L)
                    if (w.exitCode != 0) {
                        return ToolExecutionResult("Payload stage failed for $rel: ${w.output.take(200)}", false)
                    }
                    newPayload.put("$stagedDir/$rel")
                }
                manifest.put("payload", newPayload)
            }

            // 3. Provenance + install through the kernel (permission-diff gate
            //    applies: an update that expands permissions is refused).
            manifest.put("source", entry.manifestUrl)
            val result = PluginManager.install(
                sessionId = sessionId,
                manifestJson = manifest.toString(),
            )
            AppLogger.info(TAG, "marketplace install '${entry.id}': ${result.message.take(120)}")
            ToolExecutionResult(result.message, result.success)
        } catch (e: Exception) {
            ToolExecutionResult("marketplace_install failed: ${e.message}", false)
        }
    }

    // MARK: - tool: marketplace_entry

    fun executeEntry(argsJson: String): ToolExecutionResult {
        val args = try { org.json.JSONObject(argsJson) } catch (_: Exception) { org.json.JSONObject() }
        val id = args.optString("id", "").trim()
        if (id.isBlank()) return ToolExecutionResult("Error: 'id' is required", false)
        return try {
            val entry = parseEntries(fetchIndex()).firstOrNull { it.id == id }
                ?: return ToolExecutionResult("'$id' not in the catalog.", false)
            val manifestText = runCatching { httpGet(entry.manifestUrl) }.getOrDefault("(manifest fetch failed)")
            val perms = runCatching {
                org.json.JSONObject(manifestText).optJSONObject("permissions")?.toString(2) ?: "unknown"
            }.getOrDefault("unknown")
            ToolExecutionResult(
                "${entry.name} v${entry.version} by ${entry.author}\n" +
                    entry.description + "\n" +
                    "verified: ${entry.verified} | tags: ${entry.tags.joinToString(", ")}\n" +
                    "permissions:\n$perms",
                true,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Entry lookup failed: ${e.message}", false)
        }
    }

    fun definitions(): List<com.openminis.app.data.model.AgentToolDefinition> = listOf(
        com.openminis.app.data.model.AgentToolDefinition(
            name = "marketplace_browse",
            description = "Browse the Minis X plugin catalog — curated, permission-declared " +
                "plugins installable at runtime. Optional query filters by id/name/tag.",
            parameters = mapOf(
                "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary."),
                "query" to com.openminis.app.data.model.AgentToolParam("string", "Optional search filter (id, name, description or tag)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "query"),
        ),
        com.openminis.app.data.model.AgentToolDefinition(
            name = "marketplace_install",
            description = "Install a plugin from the catalog by id. Stages the payload, runs " +
                "the strict kernel pipeline (validation, hooks in the sandbox, MCP " +
                "registration, netguard allowlist). Permission-diff gate: an update that " +
                "EXPANDS permissions is refused — uninstall first if you truly trust it.",
            parameters = mapOf(
                "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary."),
                "id" to com.openminis.app.data.model.AgentToolParam("string", "Catalog plugin id (from marketplace_browse)."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
        com.openminis.app.data.model.AgentToolDefinition(
            name = "marketplace_entry",
            description = "Show a catalog entry's full details including its declared permissions.",
            parameters = mapOf(
                "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary."),
                "id" to com.openminis.app.data.model.AgentToolParam("string", "Catalog plugin id."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
    )
}
