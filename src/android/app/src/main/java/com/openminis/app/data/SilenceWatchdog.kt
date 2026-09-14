package com.openminis.app.data

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.logging.AppLogger

/**
 * [T-silence-watchdog] Audit P0 #7 — zero-token dead-check escalation
 * (Jenny architecture; no code, AGPL).
 *
 * The observed failure (19 consecutive dead checks, zero alerts): a
 * periodic verifier keeps running, keeps failing to reach its target,
 * and keeps reporting into a transcript nobody reads. The MISSING piece
 * was never the check — it was an escalation that does not depend on the
 * model choosing to alert.
 *
 * Minis X shape: this object is fed every scheduled-run completion's
 * parsed [TurnOutcomeMarkers.Outcome]. It counts consecutive
 * CHECK_FAILED outcomes (or completions where the final text carries NO
 * marker on a task whose label says it's a check — configured via
 * [feed]). At the threshold it posts ONE system notification, zero LLM
 * calls, no chat message, no transcript trace — then resets its streak so
 * it doesn't re-fire every run forever (a nagging watchdog gets disabled;
 * one alert per dead-streak is the contract).
 *
 * CHECK_DELEGATED breaks a FAILED streak but is NOT itself an alert
 * condition (a handoff is healthy). CHECK_OK breaks any streak.
 * CHECK_WARNED breaks the streak too — the model DID alert, in text; the
 * watchdog must not double-report it.
 */
object SilenceWatchdog {

    private const val TAG = "SilenceWatchdog"
    private const val CHANNEL_ID = "silence_watchdog"
    /** Notification tag — notify(tag, id, notif) form; id is per-task. */
    private const val TAG_ALERT = "silence_watchdog"

    /** Consecutive dead checks before the alert fires. Tuned so one flaky
     *  miss never alerts (n=1 noise), but a genuinely dead target surfaces
     *  within a few periodic fires — matching Jenny's measured incident. */
    const val THRESHOLD = 3

    /** taskLabel → consecutive-failed count. Persisted across process
     *  death? No — an app restart resets streaks; the next run re-arms.
     *  Acceptable: a dead check that survives restarts fires again in
     *  THRESHOLD runs anyway. Simplicity over persistence here. */
    private val failedStreaks = mutableMapOf<String, Int>()

    /**
     * Feed one scheduled-run outcome. Returns true when this feed FIRED
     * the alert (callers may log it).
     */
    fun feed(context: Context, taskLabel: String, outcome: TurnOutcomeMarkers.Outcome): Boolean {
        when (outcome) {
            TurnOutcomeMarkers.Outcome.CHECK_FAILED -> {
                val n = (failedStreaks[taskLabel] ?: 0) + 1
                failedStreaks[taskLabel] = n
                if (n >= THRESHOLD) {
                    failedStreaks[taskLabel] = 0 // one alert per dead-streak
                    postAlert(context, taskLabel, n)
                    return true
                }
            }
            TurnOutcomeMarkers.Outcome.CHECK_OK,
            TurnOutcomeMarkers.Outcome.CHECK_DELEGATED,
            TurnOutcomeMarkers.Outcome.CHECK_WARNED,
            -> failedStreaks.remove(taskLabel)
            TurnOutcomeMarkers.Outcome.UNKNOWN -> {
                // A check-task that ended with no marker at all counts as
                // half-dead: the run completed but declared nothing. Jenny's
                // incident was exactly this shape (booleans that lost their
                // subject). Counted same as FAILED for escalation purposes.
                val n = (failedStreaks[taskLabel] ?: 0) + 1
                failedStreaks[taskLabel] = n
                if (n >= THRESHOLD) {
                    failedStreaks[taskLabel] = 0
                    postAlert(context, taskLabel, n, silent = true)
                    return true
                }
            }
        }
        return false
    }

    /** Clear a task's streak (task deleted or disabled). */
    fun clear(taskLabel: String) {
        failedStreaks.remove(taskLabel)
    }

    private fun postAlert(context: Context, taskLabel: String, streak: Int, silent: Boolean = false) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                AppLogger.warning(TAG, "no POST_NOTIFICATIONS — dead-check alert only in logs")
                return
            }
            val manager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID, "Watchdog alerts",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Escalations when a scheduled check keeps failing silently"
                }
                manager.createNotificationChannel(channel)
            }
            val title = if (silent) "Check silent: $taskLabel" else "Check failing: $taskLabel"
            val body = if (silent) {
                "$streak scheduled runs completed without declaring an outcome. " +
                    "The check may be dead — open the chat and look at what the runs actually said."
            } else {
                "$streak consecutive CHECK_FAILED. The target may be down or the check is broken — " +
                    "open the chat to see the failure detail."
            }
            val openIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("minis://views/scheduled"),
            ).setPackage(context.packageName)
            val pi = android.app.PendingIntent.getActivity(
                context, taskLabel.hashCode(), openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            manager.notify(TAG_ALERT, taskLabel.hashCode(), notif)
            AppLogger.info(TAG, "watchdog alert posted: task=$taskLabel streak=$streak silent=$silent")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "watchdog alert failed: ${t.message}")
        }
    }
}
