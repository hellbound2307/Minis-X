package com.openminis.app.events

import android.content.Context
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-event-bus] Rules that wake the agent when something happens.
 *
 * The agent stops being purely reactive: rules map EVENTS (a notification
 * from a specific app, a manual emit, a future job/sensor source) to an
 * agent turn in a target session. Delivery is fire-and-forget via
 * [HeadlessChatRunner] (wait=false) — the agent wakes with a compact event
 * payload as the user turn and decides what (if anything) to do.
 *
 * Event payload format delivered to the session:
 *   [event:<type>] {"field":"value", ...}
 * plus rule-specific matched fields.
 *
 * ## Matching
 * A rule matches when every entry in [Rule.match] is satisfied by the event
 * payload:
 *   - "value"       → payload[field] == value
 *   - "~substring"  → payload[field].toString().contains(substring)
 *
 * ## Sources (v1)
 *   - "notification" — posted by MinisNotificationListenerService with
 *     payload {app, title, text}
 *   - "custom"       — anything the agent emits via the event_emit tool
 *   (time-based vigilance = scheduled tasks; job completion = job_poll —
 *   both already exist as separate primitives).
 *
 * ## Storage
 * minis-global/events/rules.json (host-side, inside the backup surface).
 *
 * ## Flood safety
 * A per-rule cooldown (default 60s) prevents notification storms from
 * spawning agent turns in a loop. Rules can set their own cooldownSeconds.
 */
object EventBus {

    private const val TAG = "EventBus"
    private const val DEFAULT_COOLDOWN_MS = 60_000L

    /** [T-android-event-tick] Event type produced by the in-process ticker. */
    const val TICK_TYPE = "tick"

    data class Rule(
        val id: String,
        val eventType: String,
        val match: Map<String, String>,
        val sessionId: String,
        val prompt: String,
        val cooldownSeconds: Long = 60,
        val enabled: Boolean = true,
        /**
         * [T-android-event-tick] Seconds between automatic fires, for
         * `eventType == "tick"` rules. 0 = not a tick rule.
         *
         * This is the time source the event bus never had: rules could only be
         * woken by a notification or an explicit event_emit, so any self-driven
         * loop (watcher, sweep, daemon check-in) had to be built as a scheduled
         * task or an external cron. A tick is the cheapest possible primitive —
         * no new storage, no new dispatch path, just a clock in front of emit().
         */
        val intervalSeconds: Long = 0,
    )

    data class Event(val type: String, val payload: Map<String, String>)

    private val rules = CopyOnWriteArrayList<Rule>()
    private val lastFired = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val loaded = AtomicBoolean(false)

    private fun file(context: Context): File = File(context.filesDir, "minis-global/events/rules.json")

    private fun ensureLoaded(context: Context) {
        if (loaded.getAndSet(true)) return
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val match = LinkedHashMap<String, String>()
                o.optJSONObject("match")?.let { mo ->
                    mo.keys().forEach { k -> match[k] = mo.optString(k, "") }
                }
                rules.add(
                    Rule(
                        id = o.optString("id", ""),
                        eventType = o.optString("eventType", ""),
                        match = match,
                        sessionId = o.optString("sessionId", ""),
                        prompt = o.optString("prompt", ""),
                        cooldownSeconds = o.optLong("cooldownSeconds", 60),
                        enabled = o.optBoolean("enabled", true),
                        intervalSeconds = o.optLong("intervalSeconds", 0),
                    ),
                )
            }
        }.onFailure { AppLogger.error(TAG, "rules load failed: ${it.message}") }
    }

    private fun save(context: Context) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        for (r in rules) {
            val match = JSONObject()
            r.match.forEach { (k, v) -> match.put(k, v) }
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("eventType", r.eventType)
                    .put("match", match)
                    .put("sessionId", r.sessionId)
                    .put("prompt", r.prompt)
                    .put("cooldownSeconds", r.cooldownSeconds)
                    .put("enabled", r.enabled)
                    .put("intervalSeconds", r.intervalSeconds),
            )
        }
        f.writeText(JSONObject().put("rules", arr).toString(2))
    }

    fun setRule(
        context: Context,
        eventType: String,
        match: Map<String, String>,
        sessionId: String,
        prompt: String,
        cooldownSeconds: Long,
        intervalSeconds: Long = 0,
    ): Rule {
        ensureLoaded(context)
        val rule = Rule(
            id = java.util.UUID.randomUUID().toString().take(8),
            eventType = eventType.trim(),
            match = match,
            sessionId = sessionId,
            prompt = prompt,
            cooldownSeconds = cooldownSeconds.coerceAtLeast(0),
            intervalSeconds = intervalSeconds.coerceAtLeast(0),
        )
        rules.add(rule)
        save(context)
        AppLogger.info(TAG, "rule ${rule.id} set: type=${rule.eventType} match=$match session=$sessionId")
        return rule
    }

    fun deleteRule(context: Context, id: String): Boolean {
        ensureLoaded(context)
        val removed = rules.removeAll { it.id == id }
        if (removed) save(context)
        return removed
    }

    fun list(context: Context): List<Rule> {
        ensureLoaded(context)
        return rules.toList()
    }

    /**
     * Emit an event. Every enabled rule of this type whose match satisfies
     * the payload AND whose cooldown has elapsed gets an agent turn. Returns
     * a human-readable dispatch summary (safe to return from any caller).
     */
    fun emit(context: Context, event: Event): String {
        ensureLoaded(context)
        val candidates = rules.filter {
            it.enabled && it.eventType == event.type && matches(it.match, event.payload)
        }
        if (candidates.isEmpty()) return "No matching rules for event '${event.type}'."

        val app = context.applicationContext as? com.openminis.app.MinisApp
        if (app == null || !app.subsystemsReady()) {
            return "Event matched ${candidates.size} rule(s) but the app subsystems are not ready."
        }

        val now = System.currentTimeMillis()
        val dispatched = ArrayList<String>()
        for (rule in candidates) {
            val last = lastFired[rule.id] ?: 0L
            val cooldown = if (rule.cooldownSeconds > 0) rule.cooldownSeconds * 1000 else DEFAULT_COOLDOWN_MS
            if (now - last < cooldown) {
                dispatched.add("${rule.id}: skipped (cooldown ${((cooldown - (now - last)) / 1000)}s left)")
                continue
            }
            lastFired[rule.id] = now
            val payloadJson = JSONObject(event.payload).toString()
            val turn = "[event:${event.type}] ${rule.prompt}\n$payloadJson"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                runCatching {
                    HeadlessChatRunner.prompt(
                        context = app,
                        sessionId = rule.sessionId,
                        text = turn,
                        attachments = emptyList(),
                        thinkingLevel = null,
                        wait = false,
                        timeoutMs = 60_000L,
                    )
                }.onFailure { AppLogger.error(TAG, "event dispatch failed for rule ${rule.id}: ${it.message}") }
            }
            dispatched.add("${rule.id}: dispatched → session ${rule.sessionId.take(8)}")
        }
        return if (dispatched.isEmpty()) "No rules dispatched." else dispatched.joinToString("\n")
    }

    /**
     * [T-android-event-tick] Fire every due tick rule.
     *
     * Driven by a process-scoped coroutine started in Application.onCreate, so
     * this only runs while the app is alive — same limitation as every other
     * in-process watcher here. A rule that MUST survive reboot or doze belongs
     * in the scheduled-task path (AlarmManager + boot receiver), which already
     * exists; tick is for loops that can tolerate an app restart.
     *
     * Due-ness is measured against [lastFired], the same map the cooldown uses,
     * so an interval and a cooldown cannot disagree about whether a rule just
     * ran. A cooldown longer than the interval wins, deliberately: it is the
     * flood guard, and it is the more conservative of the two.
     */
    fun tickNow(context: Context) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val due = rules.filter { r ->
            r.enabled && r.eventType == TICK_TYPE && r.intervalSeconds > 0 &&
                (now - (lastFired[r.id] ?: 0L)) >= r.intervalSeconds * 1000L
        }
        if (due.isEmpty()) return
        emit(
            context,
            Event(
                type = TICK_TYPE,
                payload = mapOf(
                    "intervalSeconds" to (due.first().intervalSeconds.toString()),
                    "firedAt" to now.toString(),
                    "dueRules" to due.size.toString(),
                ),
            ),
        )
    }

    private fun matches(match: Map<String, String>, payload: Map<String, String>): Boolean {
        for ((field, expected) in match) {
            val actual = payload[field] ?: return false
            val ok = if (expected.startsWith("~")) actual.contains(expected.removePrefix("~"), ignoreCase = true)
            else actual == expected
            if (!ok) return false
        }
        return true
    }
}

/** Called by [com.openminis.app.offload.MinisNotificationListenerService]. */
fun onNotificationPostedEvent(context: Context, appPackage: String, title: String, text: String) {
    EventBus.emit(
        context,
        EventBus.Event(
            type = "notification",
            payload = mapOf("app" to appPackage, "title" to title, "text" to text),
        ),
    )
}
