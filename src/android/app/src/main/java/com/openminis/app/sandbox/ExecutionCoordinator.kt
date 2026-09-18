package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        // ConcurrentHashMap.getOrPut is not atomic, use putIfAbsent pattern
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }

        return mutex.withLock {
            val startTime = System.currentTimeMillis()

            // Auto-boot PRoot if not already booted
            if (!PRootKernel.isBooted) {
                Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                PRootKernel.boot(appContext)
            }

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // Get or create shell — protected by globalLock to avoid duplicate creation
            val shell = getOrCreateShell(sessionId)

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (exitCode != 0 && exitCode != 124) {
                "$truncated\n(exit code: $exitCode)"
            } else {
                truncated
            }

            CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            shell
        }
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target — exposes cross-session
        // overwrite of the global bindMounts map (the suspected cause of
        // the "file disappears after first download" bug)
        val prevAttachments = PRootKernel.bindMounts["/var/minis/attachments"]

        // Session-specific directories
        val sessionBase = File(filesDir, "minis-sessions/$sessionId")
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        Log.w(TAG, "[diag] buildSessionBindMounts sessionId=$sessionId " +
            "attachments: prev=$prevAttachments new=${mounts["/var/minis/attachments"]}")

        // Global shared directories.
        // [T-global-bind-single-source] Iterates PRootKernel.globalMounts —
        // the single list shared with registerGlobalBindMounts. PersistentShell
        // builds PRoot's `-b` argv from THIS map, so a subdir present only in
        // the other map is invisible to the shell: /var/minis/<x> then resolves
        // to the empty rootfs placeholder and the feature silently half-works.
        // That trap shipped three times (mcp-servers vc36, meta-tools vc43,
        // runs vc58) before this list was made single-source.
        val globalBase = com.openminis.app.data.SeasonStore.activeGlobalBase(appContext)
        PRootKernel.globalMounts.forEach { globalMount ->
            val hostDir = File(globalBase, globalMount.hostSubPath).also { it.mkdirs() }
            mounts[globalMount.linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(globalMount.linuxPath, hostDir.absolutePath)
        }

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    /**
     * [T-android-seasons] Re-point the sandbox at the active season.
     *
     * PRoot's `-b` argv is fixed when a PersistentShell starts, so the mounts
     * cannot change under a live shell: every shell has to go, then the bind
     * map is rebuilt from the new season's namespace. The next `execute()` in
     * any session lazily creates a fresh shell against the new mounts — no
     * kernel restart, no rootfs copy.
     *
     * Note what this does NOT do: the shared rootfs (`/root`, `/tmp`, installed
     * packages) is common to every season. That is staged for S3 and stated in
     * the Seasons UI rather than implied away.
     */
    fun onSeasonChanged(context: Context) {
        runCatching {
            // Order matters. Kill the shells that hold the OLD season's argv
            // first, then publish the new mount set in a single atomic swap,
            // then sweep once more for any shell that started mid-swap.
            //
            // What this deliberately does NOT do any more: clearBindMounts()
            // followed by a rebuild. That left an empty mount table for the
            // duration of the rebuild, and a shell created in that window saw
            // rootfs placeholders instead of the real directories — reads
            // looked like data loss and writes landed somewhere the next rootfs
            // reset discards. It is the observed "shared/ went empty and came
            // back" incident.
            val first = shells.keys.toList()
            first.forEach { sessionDidTerminate(it) }

            PRootKernel.registerGlobalBindMounts(context.applicationContext)

            val second = shells.keys.toList()
            second.forEach { sessionDidTerminate(it) }

            Log.i(
                TAG,
                "[seasons] rebound sandbox atomically; killed ${first.size}+${second.size} shell(s)",
            )
        }.onFailure { Log.w(TAG, "[seasons] rebind failed: ${it.message}") }
    }

    fun sessionDidTerminate(sessionId: String) {
        val shell = shells.remove(sessionId)
        mutexes.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            val shell = shells.remove(sessionId)
            // T124a: snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(sessionId)
            shell?.stop()
            Log.i(TAG, "[$sessionId] Shell stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}
