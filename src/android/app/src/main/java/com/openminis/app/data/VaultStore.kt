package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-android-vault] Agent-writable secret store that outlives the sandbox.
 *
 * ## Why this exists, precisely
 *
 * The premise "sandbox resets nuke secrets" is only half true, and the half
 * that is false matters:
 *
 *  - Env vars entered via Settings already survive a reset — they live in
 *    EncryptedSharedPreferences in app storage, not in the sandbox.
 *  - What does NOT survive: anything the agent had to put INSIDE the sandbox
 *    (SSH keys under /root/.ssh, rclone.conf, tokens written to files), plus
 *    anything the agent obtained itself and had no way to store at all.
 *
 * The second half is the real gap: there was no agent write path for secrets.
 * A token the agent fetched mid-task had to go into the transcript, into a
 * world-readable file, or nowhere. The recovery protocol for a reset was
 * "ask the operator to re-type eleven keys".
 *
 * So: encrypted at rest, app-side (survives resets), agent-writable, and
 * values are NEVER readable back — not by the agent, not through a tool
 * result. What the agent can do is [projectToEnv], which copies a vault entry
 * into the env-var layer that the sandbox already injects. The value moves
 * app-side → sandbox env without ever crossing the transcript.
 *
 * ## Season policy
 *
 * Refused entirely inside an isolated season. Vault entries are app-global
 * until secrets are season-partitioned (PLAN.md S2), so allowing an isolated
 * season to project them into its sandbox would defeat the isolation that
 * season was created for. Fails closed, like minis-sessions-cli.
 */
object VaultStore {

    private const val TAG = "VaultStore"
    private const val PREFS_NAME = "vault_values"
    private const val META_FILE = "vault.json"

    data class Entry(
        val key: String,
        val note: String,
        val createdAt: Long,
        val updatedAt: Long,
        /** Length only — the value itself never leaves the encrypted store. */
        val valueLength: Int,
    )

    @Volatile
    private var contextRef: Context? = null

    private val lock = Any()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val prefs: SharedPreferences?
        get() = contextRef?.let {
            runCatching { com.openminis.app.util.EncryptedPrefsFactory.safeCreate(it, PREFS_NAME) }
                .getOrNull()
        }

    private fun metaFile(): File? = contextRef?.let { File(it.filesDir, META_FILE) }

    fun prime(context: Context) {
        contextRef = context.applicationContext
        loadMeta()
    }

    /** True when the vault can be used at all (primed, and not an isolated season). */
    fun isUsable(): Boolean =
        contextRef != null && !SeasonStore.isCurrentIsolated()

    /** Reason to report when [isUsable] is false; null when usable. */
    fun refusalReason(): String? = when {
        contextRef == null -> "Vault is not initialised."
        SeasonStore.isCurrentIsolated() ->
            "The vault is not available in an isolated season: entries are app-global " +
                "until secrets are season-partitioned, so projecting one into this " +
                "sandbox would defeat the isolation. Use the main season."
        else -> null
    }

    fun list(): List<Entry> = _entries.value

    fun set(key: String, value: String, note: String = ""): Boolean {
        val ctx = contextRef ?: return false
        val normalized = normalizeKey(key) ?: return false
        if (value.isEmpty()) return false
        val p = prefs ?: return false
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val existing = _entries.value.firstOrNull { it.key == normalized }
            val entry = Entry(
                key = normalized,
                note = note.trim().take(200),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                valueLength = value.length,
            )
            p.edit().putString(normalized, value).apply()
            _entries.value = _entries.value.filterNot { it.key == normalized } + entry
            saveMeta()
            Log.i(TAG, "vault set: $normalized (len=${value.length}) — value not logged")
            return true
        }
    }

    fun delete(key: String): Boolean {
        val normalized = normalizeKey(key) ?: return false
        synchronized(lock) {
            if (_entries.value.none { it.key == normalized }) return false
            prefs?.edit()?.remove(normalized)?.apply()
            _entries.value = _entries.value.filterNot { it.key == normalized }
            saveMeta()
            return true
        }
    }

    /**
     * Read a value for INTERNAL use only (env projection). Never surface this
     * to a tool result.
     */
    fun valueForProjection(key: String): String? {
        val normalized = normalizeKey(key) ?: return null
        return prefs?.getString(normalized, null)
    }

    /**
     * Masked description safe to return to the agent: enough to identify the
     * entry, not enough to use it. Shows the last 4 characters for long values
     * (the convention that lets a human match it against a console) and only a
     * length for short ones, where "last 4" would be most of the secret.
     */
    fun masked(entry: Entry): String {
        val tail = if (entry.valueLength >= 12) {
            val v = prefs?.getString(entry.key, null)
            if (v != null && v.length >= 4) "…" + v.takeLast(4) else ""
        } else {
            ""
        }
        return "$tail (${entry.valueLength} chars)"
    }

    /**
     * Copy a vault entry into the env-var layer, which the sandbox already
     * injects into every shell. This is the restore path after a reset: the
     * value goes app-side → sandbox env without ever entering the transcript.
     *
     * Returns a human-readable outcome; never includes the value.
     */
    fun projectToEnv(
        key: String,
        envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
    ): String {
        val repo = envVarRepository
            ?: return "Environment layer is not available in this process."
        val normalized = normalizeKey(key) ?: return "Invalid key: $key"
        val value = valueForProjection(normalized)
            ?: return "No vault entry named $normalized."
        if (!repo.isValidKey(normalized)) {
            return "Vault key '$normalized' is not a valid environment variable name."
        }
        if (repo.isDuplicateKey(normalized)) {
            val existing = repo.getValue(normalized)
            if (existing == value) return "Environment variable $normalized already matches the vault."
            // Replace: update() needs the entry id.
            val entry = repo.entries.value.firstOrNull { it.key == normalized }
            if (entry != null) {
                val ok = repo.update(entry.id, normalized, value)
                return if (ok) "Updated environment variable $normalized from the vault."
                else "Could not update environment variable $normalized."
            }
            return "Environment variable $normalized exists but could not be resolved for update."
        }
        val ok = repo.add(normalized, value, note = "vault:$normalized")
        return if (ok) {
            "Projected $normalized into the environment. New shells pick it up automatically."
        } else {
            "Failed to add environment variable $normalized."
        }
    }

    // ------------------------------------------------------------- internals

    private fun normalizeKey(key: String): String? {
        val k = key.trim().uppercase()
        if (k.isEmpty() || k.length > 64) return null
        if (!Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(k)) return null
        return k
    }

    private fun loadMeta() {
        runCatching {
            val f = metaFile() ?: return@runCatching
            if (!f.exists()) return@runCatching
            val arr = JSONObject(f.readText()).optJSONArray("entries") ?: JSONArray()
            val out = ArrayList<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key")
                if (key.isEmpty()) continue
                out.add(
                    Entry(
                        key = key,
                        note = o.optString("note"),
                        createdAt = o.optLong("createdAt", 0L),
                        updatedAt = o.optLong("updatedAt", 0L),
                        valueLength = o.optInt("valueLength", 0),
                    )
                )
            }
            _entries.value = out
        }.onFailure { Log.w(TAG, "meta load failed: ${it.message}") }
    }

    private fun saveMeta() {
        runCatching {
            val f = metaFile() ?: return@runCatching
            val arr = JSONArray()
            _entries.value.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("key", e.key)
                        .put("note", e.note)
                        .put("createdAt", e.createdAt)
                        .put("updatedAt", e.updatedAt)
                        .put("valueLength", e.valueLength)
                )
            }
            f.writeText(JSONObject().put("entries", arr).toString(2))
        }.onFailure { Log.w(TAG, "meta save failed: ${it.message}") }
    }
}
