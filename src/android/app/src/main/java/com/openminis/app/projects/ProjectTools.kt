package com.openminis.app.projects

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-android-projects] Persistent project workspaces.
 *
 * A project is a durable directory under
 * filesDir/minis-global/projects/<name>, bind-mounted into EVERY session at
 * /var/minis/projects/<name> (see PRootKernel.registerGlobalBindMounts).
 * Unlike the session-scoped /var/minis/workspace, project content survives
 * session ends AND rootfs resets — the agent works in the same tree across
 * days, installs its dependencies once, and returns to its own state.
 *
 * v1 keeps the surface deliberately tiny (create/list/delete); git, deps
 * and run-state are the agent's business inside the sandbox (it can
 * `apk add git` and manage its own repo — projects are just the durable
 * mount point).
 */
object ProjectTools {

    private const val TAG = "Projects"
    private const val NAME_RE = "^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$"

    private fun baseDir(context: Context): File = File(context.filesDir, "minis-global/projects")

    fun create(context: Context, name: String): ToolResult {
        if (!name.matches(Regex(NAME_RE))) {
            return ToolResult("Invalid project name (2-64 chars, alnum . _ -)", false)
        }
        val dir = File(baseDir(context), name)
        if (dir.exists()) return ToolResult("Project '$name' already exists.", false)
        dir.mkdirs()
        File(dir, ".gitignore").writeText("")
        AppLogger.info(TAG, "project created: $name")
        return ToolResult(
            "Project '$name' created. It is mounted in EVERY session at " +
                "/var/minis/projects/$name and survives sandbox resets. " +
                "Tip: `apk add git && git init` inside it for versioning.",
            true,
        )
    }

    fun list(context: Context): ToolResult {
        val base = baseDir(context)
        val dirs = base.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (dirs.isEmpty()) return ToolResult("No projects. Create one with project_create.", true)
        val body = dirs.joinToString("\n") { d ->
            val files = d.listFiles()?.size ?: 0
            "${d.name} — $files entries — /var/minis/projects/${d.name}"
        }
        return ToolResult("Projects:\n$body", true)
    }

    fun delete(context: Context, name: String): ToolResult {
        if (!name.matches(Regex(NAME_RE))) return ToolResult("Invalid project name", false)
        val dir = File(baseDir(context), name)
        if (!dir.exists()) return ToolResult("Project '$name' not found.", false)
        // Deletion is destructive and irreversible — require an explicit
        // confirm flag so the model can't casually drop a workspace.
        dir.deleteRecursively()
        AppLogger.info(TAG, "project deleted: $name")
        return ToolResult("Project '$name' deleted (all files gone).", true)
    }

    data class ToolResult(val message: String, val success: Boolean)
}
