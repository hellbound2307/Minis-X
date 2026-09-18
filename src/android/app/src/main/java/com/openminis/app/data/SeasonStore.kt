package com.openminis.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [T-android-seasons] Pillar F — seasons: isolated agent worlds.
 *
 * A season is a namespace for everything the agent considers "its own world":
 * memory, skills, shared, projects, runs, meta-tools, plugin payloads. Switching
 * seasons changes what `/var/minis/…` means inside the sandbox, so an isolated
 * season's agent starts with an EMPTY world and cannot read the main one's.
 *
 * ## Isolation is chosen at creation and never flipped
 *
 * A season that has run with full access has already pulled global memory,
 * skills and secrets into its context. Flipping it to "isolated" afterwards is
 * theatre, not isolation — so [create] takes the mode and there is deliberately
 * no setter for it. Leaving an isolated season is a new season, not a toggle.
 *
 * ## What this does and does not isolate (be honest)
 *
 * ISOLATED by this file:
 *  - the `/var/minis/…` namespace (memory, skills, shared, projects, runs,
 *    meta-tools, plugin payloads) — separate host directories, separate binds.
 *
 * NOT isolated yet (deliberately staged, see PLAN.md):
 *  - the PRoot rootfs itself: both seasons share one Alpine install, so
 *    `/root`, `/tmp` and installed packages are common. A season can read
 *    leftovers another season left outside its namespace.
 *  - chats/messages (one Room DB) — S2.
 *  - secrets (one env-var store) — S2.
 *  - WebView cookies/localStorage (app-wide) — S3.
 *  - device-level tools (accessibility, notification access, Shizuku) which
 *    see the DEVICE, not the app, and are therefore cross-season by nature.
 *
 * The UI must say so rather than imply a guarantee the code does not make.
 *
 * ## The main season keeps the legacy layout
 *
 * `main` maps to the existing `files/minis-global` and is never migrated, so
 * introducing seasons cannot lose anyone's data. Only new seasons get
 * `files/minis-seasons/<id>/minis-global`.
 */
object SeasonStore {

    private const val TAG = "SeasonStore"

    /** The default season — legacy layout, full access. */
    const val MAIN_ID = "main"

    private const val INDEX_FILE = "minis-seasons/index.json"

    data class Season(
        val id: String,
        val name: String,
        val icon: String,
        /** Chosen at creation; immutable by design (see the class doc). */
        val isolated: Boolean,
        val createdAt: Long,
    )

    @Volatile
    private var contextRef: Context? = null

    private val lock = Any()

    private fun mainSeason() = Season(
        id = MAIN_ID,
        name = "Main",
        icon = "🏠",
        isolated = false,
        createdAt = 0L,
    )

    private val _seasons = MutableStateFlow<List<Season>>(listOf(mainSeason()))
    val seasons: StateFlow<List<Season>> = _seasons.asStateFlow()

    private val _current = MutableStateFlow(mainSeason())
    val current: StateFlow<Season> = _current.asStateFlow()

    /** Load the index. Called once from Application.onCreate. */
    fun prime(context: Context) {
        val app = context.applicationContext
        contextRef = app
        runCatching {
            val index = File(app.filesDir, INDEX_FILE)
            if (!index.exists()) return@runCatching
            val obj = JSONObject(index.readText())
            val arr = obj.optJSONArray("seasons") ?: JSONArray()
            val loaded = mutableListOf(mainSeason())
            // NOTE: no `continue`/`break` here. This whole body is inside an
            // inline lambda (runCatching), and "break/continue in inline
            // lambdas" only lands in Kotlin language version 2.2 — it compiled
            // to a hard error on this toolchain (CI, vc62 build 3). Nested
            // ifs cost nothing and do not depend on the language level.
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i)
                if (s != null) {
                    val id = s.optString("id")
                    if (id.isNotBlank() && id != MAIN_ID) {
                        loaded.add(
                            Season(
                                id = id,
                                name = s.optString("name").ifBlank { id },
                                icon = s.optString("icon").ifBlank { "🌙" },
                                isolated = s.optBoolean("isolated", true),
                                createdAt = s.optLong("createdAt", 0L),
                            )
                        )
                    }
                }
            }
            _seasons.value = loaded
            val currentId = obj.optString("current").ifBlank { MAIN_ID }
            _current.value = loaded.firstOrNull { it.id == currentId } ?: mainSeason()
        }.onFailure { Log.w(TAG, "prime failed: ${it.message}") }
    }

    fun currentId(): String = _current.value.id

    fun isCurrentIsolated(): Boolean = _current.value.isolated

    /**
     * Global (`/var/minis/…`) base directory for the ACTIVE season. Every
     * consumer of the global namespace must resolve it through here — the
     * alternative (each call site doing `File(filesDir, "minis-global")`) is
     * how the mount lists drifted apart in the first place.
     */
    fun activeGlobalBase(context: Context): File = globalBase(context, currentId())

    fun globalBase(context: Context, seasonId: String): File {
        val root = if (seasonId == MAIN_ID) {
            File(context.filesDir, "minis-global")
        } else {
            File(seasonRoot(context, seasonId), "minis-global")
        }
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun seasonRoot(context: Context, seasonId: String): File =
        File(File(context.filesDir, "minis-seasons"), seasonId)

    /**
     * Create a season and make it active. [isolated] is fixed here forever.
     * The namespace dirs are pre-created so the sandbox binds land on real
     * directories rather than rootfs placeholders.
     */
    fun create(
        context: Context,
        name: String,
        isolated: Boolean,
        icon: String = "🌙",
    ): Season {
        val app = context.applicationContext
        val season = Season(
            id = "s_" + System.currentTimeMillis().toString(36) + "_" +
                UUID.randomUUID().toString().take(4),
            name = name.ifBlank { "Season" },
            icon = icon.ifBlank { "🌙" },
            isolated = isolated,
            createdAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            val root = seasonRoot(app, season.id)
            // Pre-create every namespace dir the sandbox will bind.
            listOf(
                "minis-global/memory",
                "minis-global/skills",
                "minis-global/shared",
                "minis-global/mcp-servers",
                "minis-global/projects",
                "minis-global/plugins/payloads",
                "minis-global/meta-tools",
                "minis-global/runs",
            ).forEach { File(root, it).mkdirs() }
            _seasons.value = _seasons.value + season
            persistLocked(app)
        }
        switchTo(context, season.id)
        return season
    }

    /**
     * Make [id] active and re-point the sandbox at its namespace.
     *
     * Callers get the persistence + the index update; the sandbox rebind is
     * delegated to [com.openminis.app.sandbox.ExecutionCoordinator.onSeasonChanged]
     * because only it can tear down the live persistent shells (PRoot's `-b`
     * argv is fixed at shell start, so the mounts cannot change under a
     * running shell).
     */
    fun switchTo(context: Context, id: String) {
        val app = context.applicationContext
        val target = _seasons.value.firstOrNull { it.id == id } ?: return
        synchronized(lock) {
            _current.value = target
            persistLocked(app)
        }
        runCatching {
            com.openminis.app.sandbox.ExecutionCoordinator.onSeasonChanged(app)
        }.onFailure { Log.w(TAG, "sandbox rebind failed: ${it.message}") }
    }

    /** Delete a season's namespace. The main season can never be deleted. */
    fun delete(context: Context, id: String): Boolean {
        if (id == MAIN_ID) return false
        val app = context.applicationContext
        synchronized(lock) {
            _seasons.value = _seasons.value.filterNot { it.id == id }
            if (_current.value.id == id) _current.value = mainSeason()
            persistLocked(app)
        }
        runCatching { seasonRoot(app, id).deleteRecursively() }
        runCatching { com.openminis.app.sandbox.ExecutionCoordinator.onSeasonChanged(app) }
        return true
    }

    private fun persistLocked(context: Context) {
        runCatching {
            val arr = JSONArray()
            _seasons.value.filter { it.id != MAIN_ID }.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("icon", s.icon)
                        .put("isolated", s.isolated)
                        .put("createdAt", s.createdAt)
                )
            }
            val obj = JSONObject()
                .put("current", _current.value.id)
                .put("seasons", arr)
            val index = File(context.filesDir, INDEX_FILE)
            index.parentFile?.mkdirs()
            index.writeText(obj.toString(2))
        }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }
}
