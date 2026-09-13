package com.openminis.app.offload

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.logging.AppLogger

/**
 * [T-agent-perm-rules] Permission gating for CORE agent tools (shell_execute,
 * browser_use, spawn_agent, job_start, plugin_install, memory_write,
 * event_rule_set, py_meta_tools) — the audit P0 #5 item.
 *
 * The offload CLIs (android-*) have had tri-state gating via
 * [OffloadPermissionManager] since T330. The core agent tools that execute
 * through ChatViewModel.executeTool had NO gate — a shell_execute ran with
 * zero confirmation regardless of Settings state. This file extends the SAME
 * tri-state engine (BYPASS / ASK_ONCE / NOT_ALLOWED) to those tools:
 *
 *   BYPASS        — default for every core tool (house philosophy: the user
 *                   opted into an agent app; downgrade to ASK_ONCE /
 *                   NOT_ALLOWED yourself in Settings → Permissions).
 *   ASK_ONCE      — every call prompts. Foreground: the existing in-app
 *                   OffloadPermissionDialog (StateFlow-suspended coroutine).
 *                   Backgrounded: heads-up notification with Allow / Deny
 *                   actions (works on lock screen, no SYSTEM_ALERT_WINDOW
 *                   grant needed — the ToolOverlayController capsule is
 *                   status-only and stays that way).
 *   NOT_ALLOWED   — hard deny with the same fail-closed phrasing as the
 *                   offload gate (stops model retry loops).
 *
 * Both prompt paths funnel into the SAME OffloadPermissionManager respond
 * slot (first responder wins): whichever of the in-app dialog or the
 * notification action lands first resumes the suspended checkPermission;
 * the loser no-ops because the continuation is already consumed. The
 * notification is cancelled on any response.
 *
 * Subagent runs pass the PARENT session id (SubagentRunner binds the child
 * ChatViewModel to a private store but SessionActivityTracker still records
 * activity under the parent session), so "Allow in this session" grants
 * cover a parent + its children — consistent with how the offload gate
 * scopes ASK_ONCE.
 */
object AgentToolPermissions {

    private const val TAG = "AgentToolPerms"

    /** Notification channel for background confirm prompts. */
    private const val CHANNEL_ID = "agent_tool_confirm"
    // [T-agent-perm-rules-fix2] Read by PermissionNotificationReceiver to
    // cancel the notification on action — must be internal, not private.
    internal const val NOTIF_TAG = "agent_tool_confirm"

    /**
     * Application context + foreground probe injected by MinisApp.onCreate
     * (same pattern OffloadPermissionManager.init uses). Null until then —
     * the gate fails OPEN (BYPASS semantics) because every gateable tool
     * defaults to BYPASS anyway; a null context before app init can't
     * happen on a real dispatch path (the sandbox boots after onCreate).
     */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var foregroundProbe: () -> Boolean = { true }

    fun init(context: Context, isAppForeground: () -> Boolean) {
        appContext = context.applicationContext
        foregroundProbe = isAppForeground
    }

    /**
     * Core agent tools that can be gated. Names match the ChatViewModel
     * executeTool when-block keys. displayNames are user-facing.
     *
     * Everything defaults to BYPASS: installing an agent app IS the consent
     * for the agent to run its own tools. The registry only defines WHAT can
     * be constrained, not that it must be.
     */
    data class GateableTool(
        val toolName: String,
        val displayName: String,
        val description: String,
        val defaultLevel: OffloadPermissionManager.PermissionLevel = OffloadPermissionManager.PermissionLevel.BYPASS,
    )

    val gateableTools: List<GateableTool> = listOf(
        GateableTool(
            "shell_execute", "Shell (run commands)",
            "Runs any Linux command inside the sandbox",
        ),
        GateableTool(
            "browser_use", "Browser (drive web pages)",
            "Opens and controls the built-in browser — navigation, clicks, typing, screenshots",
        ),
        GateableTool(
            "spawn_agent", "Subagents",
            "Spawns child agent runs inside this conversation",
        ),
        GateableTool(
            "job_start", "Background jobs",
            "Starts detached long-running processes (servers, watchers, downloads)",
        ),
        GateableTool(
            "plugin_install", "Install plugins",
            "Installs runtime plugins that add new agent tools",
        ),
        GateableTool(
            "memory_write", "Write memory",
            "Saves persistent notes across sessions",
        ),
        GateableTool(
            "event_rule_set", "Event rules",
            "Creates rules that wake the agent on notifications/events",
        ),
        GateableTool(
            "py_meta_tools", "Minted tools (write)",
            "Registers new agent-callable Python tools",
        ),
    )

    /** Registry names → display info (lookup for the settings screen). */
    private val byName: Map<String, GateableTool> =
        gateableTools.associateBy { it.toolName }

    fun info(toolName: String): GateableTool? = byName[toolName]

    /**
     * The default level for a gateable agent tool, or null when the name
     * isn't gateable. [OffloadPermissionManager.getLevel] consults this for
     * names absent from its offload registry so agent-tool levels stored
     * under the shared `level_<name>` prefs keys are actually read.
     */
    fun defaultLevelFor(toolName: String): OffloadPermissionManager.PermissionLevel? =
        byName[toolName]?.defaultLevel

    /** Read the stored level for a core tool (BYPASS when unknown). */
    fun getLevel(toolName: String): OffloadPermissionManager.PermissionLevel =
        OffloadPermissionManager.getLevel(toolName)

    fun setLevel(toolName: String, level: OffloadPermissionManager.PermissionLevel) =
        OffloadPermissionManager.setLevel(toolName, level)

    /**
     * User-facing one-line descriptions rendered under each row in
     * Settings → Permissions → Agent Tools.
     */
    fun description(toolName: String): String =
        byName[toolName]?.description ?: ""

    // ── Gate ──────────────────────────────────────────────────────────────────

    /**
     * The executeTool entry gate. Returns null when the call may proceed;
     * otherwise a failure message the dispatcher returns to the model.
     *
     * NOT_ALLOWED → fail-closed message (same phrasing family as the
     * offload gate: a hard policy boundary, not a transient failure).
     * ASK_ONCE → consults OffloadPermissionManager.checkPermission, which
     * suspends on the in-app dialog; when the app is BACKGROUNDED we post
     * the heads-up notification in parallel so either surface can answer.
     * Session grants/denials behave exactly like the offload CLI tools.
     */
    suspend fun gate(toolName: String, sessionId: String): String? {
        val level = OffloadPermissionManager.getLevel(toolName)
        return when (level) {
            OffloadPermissionManager.PermissionLevel.BYPASS -> null
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> {
                val display = byName[toolName]?.displayName ?: toolName
                "permission_denied: $display is set to Not Allowed in Settings → Permissions. " +
                    "This is a hard policy boundary, not a transient failure — do not retry. " +
                    "Ask the user to change the setting if the task needs this tool."
            }
            OffloadPermissionManager.PermissionLevel.ASK_ONCE -> {
                // prefs are initialized in MinisApp.onCreate before any
                // sandbox boot; a null appContext here means we're in a
                // unit-test or pre-init context — fail open (BYPASS).
                val app = appContext ?: return null
                val display = byName[toolName]?.displayName ?: toolName
                val backgrounded = !foregroundProbe()
                if (backgrounded) {
                    // Background: the in-app dialog can't be seen. Post the
                    // heads-up notification and let checkPermission's
                    // suspension resolve from EITHER surface. The notification
                    // actions call OffloadPermissionManager.respondToRequest —
                    // the exact slot the in-app dialog writes to.
                    postConfirmNotification(app, toolName, display, sessionId)
                }
                val allowed = OffloadPermissionManager.checkPermission(
                    toolName, display, sessionId,
                )
                // Whichever surface answered, the notification is now stale.
                if (backgrounded) cancelConfirmNotification(toolName)
                if (allowed) null else {
                    "$display was denied (session). The user declined this call — " +
                        "do not re-request immediately; proceed without it or ask " +
                        "the user in your reply what they prefer."
                }
            }
        }
    }

    // ── Background confirm notification ──────────────────────────────────────

    /**
     * Heads-up notification with Allow / Deny actions. Tapping the body
     * opens the chat (same minis://session/<id> deep link the ask-user
     * notification uses). Allow → ALLOW_SESSION, Deny → DENY_SESSION.
     * "Allow in this session" matches the in-app dialog's primary grant so
     * a background-confirmed tool stops nagging for the session.
     */
    private fun postConfirmNotification(
        context: Context,
        toolName: String,
        displayName: String,
        sessionId: String,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // No notification permission: the in-app dialog remains the only
            // surface; the checkPermission suspension waits for the user to
            // return to the app (OffloadPermissionManager has no timeout —
            // it resumes when the dialog is answered, whenever that is).
            AppLogger.warning(TAG, "POST_NOTIFICATIONS not granted — background confirm only visible in-app")
            return
        }
        try {
            val manager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Agent confirmations",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Approve or deny agent tool use while the app is in the background"
                }
                manager.createNotificationChannel(channel)
            }

            val openIntent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("minis://session/$sessionId"),
            ).setPackage(context.packageName)
            val contentPi = PendingIntent.getActivity(
                context, toolName.hashCode(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val allowIntent = Intent(context, PermissionNotificationReceiver::class.java).apply {
                action = PermissionNotificationReceiver.ACTION_ALLOW
                putExtra(PermissionNotificationReceiver.EXTRA_TOOL, toolName)
            }
            val allowPi = PendingIntent.getBroadcast(
                context, toolName.hashCode(), allowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val denyIntent = Intent(context, PermissionNotificationReceiver::class.java).apply {
                action = PermissionNotificationReceiver.ACTION_DENY
                putExtra(PermissionNotificationReceiver.EXTRA_TOOL, toolName)
            }
            val denyPi = PendingIntent.getBroadcast(
                context, toolName.hashCode() + 1, denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Agent needs approval")
                .setContentText("Allow $displayName?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(contentPi)
                .addAction(0, "Allow session", allowPi)
                .addAction(0, "Deny", denyPi)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIF_TAG, toolName.hashCode(), notification)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "confirm notification failed: ${t.message}")
        }
    }

    private fun cancelConfirmNotification(toolName: String) {
        runCatching {
            val app = appContext ?: return
            NotificationManagerCompat.from(app).cancel(NOTIF_TAG, toolName.hashCode())
        }
    }
}

/**
 * Handles Allow / Deny actions on the background confirm notification.
 * Both funnel into OffloadPermissionManager.respondToRequest — the same
 * slot the in-app OffloadPermissionDialog writes — so first-responder-wins
 * holds whichever surface the user picks. A late second response no-ops:
 * respondToRequest nulls the continuation before resuming, and a null
 * continuation means the second resume is simply dropped.
 */
class PermissionNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALLOW = "com.openminis.app.PERMISSION_ALLOW"
        const val ACTION_DENY = "com.openminis.app.PERMISSION_DENY"
        const val EXTRA_TOOL = "permission_tool"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val toolName = intent.getStringExtra(EXTRA_TOOL) ?: return
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(AgentToolPermissions.NOTIF_TAG, toolName.hashCode())

        when (intent.action) {
            ACTION_ALLOW -> OffloadPermissionManager.respondToRequest(
                OffloadPermissionManager.Response.ALLOW_SESSION,
            )
            ACTION_DENY -> OffloadPermissionManager.respondToRequest(
                OffloadPermissionManager.Response.DENY_SESSION,
            )
        }
    }
}
