# Minis X — Resident Platform Plan (v1)

**Author:** resident agent (in-sandbox) · **Owner:** Marwan
**Date:** 2026-09-17 · **Baseline:** `e1a1d6e` (vc56 / 1.19-x19, installed on device)
**Repo:** `hellbound2307/Minis-X` → `src/android/app/src/main/java/com/openminis/app/`
**Scope:** features that live **inside the APK** — so a fresh install on another phone is
functionally identical without manual re-bootstrap.

> This doc is the build order. It is written to be committed to the repo as `docs/PLAN.md`
> so the plan travels with the app, not with the chat.

---

## 0. The contract (what "identical on another phone" means)

Today, moving devices means: re-enter env vars, regenerate SSH keys, reinstall apk packages,
re-pull skills, re-add MCP servers, re-connect providers, re-arm watchers, re-read bootstrap
notes. That is ~2 days of manual work and it silently loses capabilities.

**Target contract — the Clone Test:**

> Install the APK on phone B → open `Settings → Restore → Scan code` → one QR/paste →
> wait for the provisioning job → **Doctor screen is green** → the agent knows its name,
> its owner profile, its skills, its plugins, its providers, its secrets, its memory,
> its scheduled tasks, and its sandbox toolchain. No typing of 12 secrets. No reading of notes.

Pass criteria are enumerated in §9. Everything in §3 (portability) exists to satisfy this,
and everything in §2/§4 exists so that the same clone is *verifiable* rather than assumed.

**Non-negotiable design rules:**

| # | Rule | Why |
|---|---|---|
| R1 | State lives in `files/minis-global/**` (or `/var/minis/**`), never only in `/tmp` or session dirs | `/tmp` is volatile; session dirs are unreachable and deleted by Clear |
| R2 | Any feature that changes behavior must be **declarative + diffable** (JSON/MD), not code | config-as-code → restorable, reviewable, agent-editable |
| R3 | Every long-running thing must emit a **structured event** | observability is a dependency of everything else |
| R4 | Every new capability must have a **doctor check** | "built" ≠ "working on phone B" |
| R5 | Secrets go to the **vault**, never to plaintext env files or memory logs | portability + hygiene |
| R6 | Nothing depends on a service Marwan can't pay for | constraint: no PayPal/Stripe; GitHub/rclone/TG only |
| R7 | The agent must be able to **see and repair** its own runtime | resident-agent principle |

---

## 1. Current state (verified this session, not from memory)

Verified by reading `/tmp/minis-x` @ `e1a1d6e`:

| Area | What exists now | File(s) |
|---|---|---|
| Agent loop / chat | session loop + tool dispatch, UI | `ui/chat/ChatViewModel.kt`, `ui/chat/ChatScreen.kt` |
| Tools | 20+ core tools, MCP, plugins, minted py tools | `tools/**`, `mcp/MCPRepository.kt`, `plugins/PluginManager.kt` |
| Subagents | spawn/status, isolated context (vc56), **live activity panel (vc53/54)** | `tools/subagent/SubagentRunner.kt` (674 L) |
| Event rules | notification + custom → wake agent, cooldown, rules.json | `events/EventBus.kt`, `notification/MinisNotificationListenerService.kt` |
| Sandbox | PRoot kernel, **rootfs bundled as an asset**, persistent shell, pty | `sandbox/RootfsManager.kt`, `PRootKernel.kt`, `ExecutionCoordinator.kt` |
| Backup | exporter/importer, categories, optional AES (passphrase→KDF), rclone + Telegram backends | `backup/BackupExporter.kt`, `backup/remote/RcloneBridge.kt` |
| Usage | token/cost records, stats screen | `usage/UsageRecord.kt`, `ui/.../UsageStatsScreen.kt` |
| Services | foreground agent service, session concurrency, badge/activity, dynamic island | `service/AgentForegroundService.kt`, `service/SessionConcurrencyManager.kt` |
| Config gate | `minis-config` writes require user confirmation | `config/ConfigConfirmNotifier.kt`, `MinisConfigPermissionStore.kt` |
| Memory | daily logs + GLOBAL.md (read-only to agent), memory tools | `tools/MemoryTools.kt`, `ui/.../MemoryGlobalPrefs.kt` |

**What is genuinely missing** (all verified by using the system, not by guessing):

1. No structured run telemetry — I get a flat text result per tool call, no durations, no
   token/cost per step, no retry counts, no partial output.
2. Subagents stream **UI-side only**; the agent itself and the record have no per-step stream,
   no mid-run messaging, no completion event injected into the parent turn.
3. Long `shell_execute` is a black box for up to 15 minutes.
4. No sandbox capability manifest → a fresh device is a **different machine**.
5. Secrets live as env vars typed by hand; no vault, no portable unlock.
6. GLOBAL.md/SOUL.md have no agent-side propose flow.
7. Session/attachment storage is unreachable from the sandbox → the agent cannot GC its own mess.
8. Tool schemas are sent every request; the surface grows with plugins.
9. No agent-visible task queue.
10. Watchers/jobs die on app restart with no auto-rearm.

---

## 2. PILLAR A — Observability & live visuals

**Goal:** every step the app takes is (a) visible to Marwan in real time, (b) recorded in a
machine-readable run log, (c) inspectable by the agent. This is the foundation — Pillars B/C
are much cheaper to build once runs are observable.

### A1 — Agent Run Event Bus (the spine)

New: `events/AgentEventBus.kt` + `agent/RunRecorder.kt`.

Every run writes append-only **JSONL** to `files/minis-global/runs/<sessionId>/<runId>.jsonl`
and publishes an in-memory `StateFlow<RunSnapshot>` for the UI. Crash-safe, tail-able from the
sandbox, easy to export, easy to replay.

Event schema (one JSON object per line, `seq` monotonic):

```json
{"seq":14,"ts":1758112345678,"runId":"r_01J...","sessionId":"abc","parentRunId":null,
 "kind":"tool_call_end","turn":3,
 "tool":"shell_execute","callId":"c_9","argsDigest":"apk add py3-numpy",
 "status":"ok","durationMs":4211,"exitCode":0,
 "bytesStdout":8123,"bytesStderr":0,
 "tokensIn":12043,"tokensOut":311,"costUsd":0.0031,
 "truncated":false}
```

`kind` enum (v1): `run_start, turn_start, context_assembled, llm_request, llm_first_token,
llm_delta_stats, llm_end, tool_call_start, tool_stream, tool_call_end, tool_denied, retry,
compact, subagent_spawn, subagent_event, subagent_end, event_rule_fired, error, turn_end, run_end`.

Retention: keep last N runs per session (default 50) + 7 days; sized by bytes (cap 200 MB, LRU).

**DoD:** a run of 3 tool calls produces a valid JSONL; the agent can `file_read` it and answer
"how long did tool 2 take and what did it cost".

### A2 — Live tool output streaming (stdout tap)

`sandbox/ShellExecutor.kt` + `sandbox/offload/*` already capture output at the end. Add a
chunked sink: each read pushes a `tool_stream` event (bounded: 64 KB tail in memory, full log
to `runs/<runId>/<callId>.log`).

UI: tool card grows a live tail (monospace, auto-scroll, collapsible), with "expand full log".

**DoD:** `apk add build-base` shows progress lines in the chat while running; agent-side
`job_poll` and the event stream agree on the tail.

### A3 — Subagent observability v2 (spawn tree, lanes, messaging)

Extend `tools/subagent/SubagentRunner.kt`:

* every child run gets a `runId` with `parentRunId` → **spawn tree** (supports nesting depth 1+).
* children emit the full A1 event stream into their own JSONL.
* per-run counters: toolCalls, tokensIn/Out, elapsed, status ring (last 5 outcomes), current step.
* **mid-run messaging**: new tool `send_message(runId, text)` → parent→child queue drained at
  tool-round boundaries; child→parent heartbeat lines.
* **completion event**: on finish, inject a task-notification turn into the parent
  (`[subagent-done] run r_… status ok output=<path> summary=…`) — replaces blind `agent_status` polling.
* **budget caps**: `max_tokens`, `max_seconds`, `max_tool_calls` per subagent; hard-kill on breach.

UI: `ui/run/SubagentTreePanel.kt` — one lane per child, live step line, token counter,
elapsed timer, expand-to-full-stream, kill button per lane.

**DoD:** spawn 3 subagents from chat; all three lanes stream live; one receives a mid-run
steer; parent gets a completion line without polling; killing a lane stops its process group.

### A4 — Cost & token ledger, per step

Extend `usage/UsageRecord.kt` with `runId`, `toolName`, `subagentRunId`, `contextBucket`
(system / tools / memory / history / attachments / subagent).

New sheet "Where did the tokens go": per run → per turn → per tool/subagent breakdown,
cache hit rate, $ per run, $ per task-class over time.

**DoD:** after one heavy run, the sheet accounts for ≥98% of billed tokens with buckets.

### A5 — Sandbox HUD

`diagnostics/SystemResourceMonitor.kt` exists. Add live: RSS, **address-space guard vs
`MINIS_RLIMIT_AS_KB`**, rootfs + `/var/minis` disk, job list with PID/CPU, netguard blocks,
active keeplive/foreground service state.

Placement: collapsible chip in the chat header + full screen in Settings → Diagnostics.
The agent gets the same numbers through a tool so it can self-diagnose OOM before it happens.

**DoD:** HUD shows the same numbers as `free`/`df`/`ps` inside the sandbox (±5%).

### A6 — Rich foreground progress HUD

`service/AgentForegroundService.kt` + `DynamicIslandSupport.kt`: notification shows current
step ("tool 3/7 · shell_execute · 12s"), tokens, cost, elapsed; expandable with the last 3
steps; tap → run timeline. Survives app backgrounding.

**DoD:** close the app mid-run; notification keeps updating; tapping deep-links to the run.

### A7 — Event-rule activity log + editor UI

`EventBus` writes fires to `events/fired.jsonl` (rule id, payload, matched fields, cooldown
state, target session, dispatch outcome). New screen: rules list → editor (event type, match
pairs, prompt, target session, cooldown) + "fire test event" button + last-N fires log.

**DoD:** a WhatsApp-style test notification → rule fires → visible in the log with the exact
matched fields and the turn it produced.

### A8 — Context Assembly Inspector ("why did it say that")

New event `context_assembled` carrying the **token budget breakdown** of every request:
system prompt, SOUL/GLOBAL blocks, memory injections (which files, which lines), skills
injected, tool schemas (count + tokens), MCP/plugin schemas, history window, attachments,
subagent context. Plus *what got dropped* and why (compact, truncation, filter).

UI: per-turn "context" button → treemap/table. This is the one thing neither of us can see
today, and it is the usual cause of "why is the agent acting weird".

**DoD:** for any turn, the table sums to the request's reported input tokens (±3%).

### A9 — Run replay + export

`ui/run/RunReplayScreen.kt`: open any run JSONL → step through (with tool outputs, diffs,
subagent lanes). Export a run as `.jsonl` (raw) or self-contained `.html` (readable, shareable,
redacted by `EnvVarRedactor`).

**DoD:** export a run with a secret in it → the HTML export contains no secret value.

---

## 3. PILLAR B — Portability (the Clone Test)

### B1 — Sandbox Capability Manifest + `provision.sh`

The sandbox's real capability set (apk packages, pip packages, `/usr/local/bin` CLIs,
env-derived tools, mounts) is captured into:

```
files/minis-global/capabilities/
  capabilities.json      # declared: what should exist
  provision.sh           # generated, idempotent, re-runnable
  lock.json              # resolved versions/hashes at capture time
  artifacts/             # cached wheels/tarballs for offline rebuild
```

Capture runs on demand and after any successful `apk add`/`pip install` in the sandbox.
On a fresh device: `Settings → Sandbox → Rebuild from manifest` → runs `provision.sh` as a
**job** with a live progress lane (§A3). Offline path uses `artifacts/` when network fails.

**DoD:** wipe the sandbox toolchain on device A, restore from manifest, `Doctor` green, a
Python+curl+git script runs unchanged.

### B2 — Secrets Vault

```
files/minis-global/vault/vault.enc      # AES-GCM, key in Android Keystore (hardware-backed)
files/minis-global/vault/index.json     # names, scope, lastUsed, never values
```

* Env vars become **projections**: `EnvVarRepository` reads vault entries at session start
  and exports `$NAME` into the sandbox env (existing redaction stays).
* Agent can reference `$NAME` but never read the value; UI mask + reveal-with-biometric.
* Per-entry metadata: which tool/plugin/host uses it (audit), created, rotated.
* **Portable unlock**: vault key wrapped by (a) Keystore on-device, and (b) a passphrase-derived
  key (Argon2id) so phone B can open it with the restore code. No plaintext leaves the device.

**DoD:** restore on phone B yields working `GITHUB_TOKEN`, `TG_*`, `*_API_KEY` without typing
any value; leak test: `grep -r` across `files/` finds no plaintext secret.

### B3 — Config-as-code (agent-proposable, user-approved)

Everything behavioral lives in readable files that are backed up and diffable:

```
files/minis-global/config/
  soul.md  global.md  permissions.json  event-rules.json
  providers.json  models.json  scheduled-tasks.json  browser.json  appearance.json
```

* Extend the existing confirm-gate (`ConfigConfirmNotifier`) to cover **GLOBAL.md and SOUL.md**:
  agent proposes a diff → notification → apply/reject → audit entry. *(This is the long-flagged
  missing primitive: GLOBAL.md currently has no agent-side write path.)*
* Optional git backend: commit each applied diff to a private repo via `gh_ops`/rclone →
  version history for the agent's own personality and rules.

**DoD:** agent proposes a GLOBAL.md edit; user approves from the notification; diff + audit
row visible in Settings → Logs → Config Changes; restore on phone B brings it back.

### B4 — One-tap Bootstrap (the actual clone)

Extend `backup/remote/*` with an **Operator Bundle** target (choose one):
private GitHub repo · rclone remote · Telegram channel (existing `tg_backup` plugin).

`Settings → Restore → Scan code` reads a QR containing
`{remote, path, kdf, salt, checksum}` → single action pulls:

1. vault (B2) → unlock
2. config-as-code (B3)
3. skills pack (D1), plugins + payloads, MCP server defs
4. `/var/minis/shared/**` + memory
5. `capabilities.json` → run `provision.sh` (B1)
6. re-arm scheduled tasks, event rules, keepalive
7. run Doctor (§B7) → report

**DoD:** Clone Test (§9) passes end-to-end on a second device with one user action.

### B5 — Persona layer (name, icon, voice, style)

`SoulStore` + `SoulIcon` already exist. Make persona a versioned config object (B3) with
history, preview, and per-device overrides for voice/TTS. Ships with defaults in the APK so a
brand-new install is already "Minis" and not a blank assistant.

**DoD:** restore carries name/icon/style/voice; a device with no bundle still starts with a
sane persona, not empty fields.

### B6 — Device profile

Written at first run: `files/minis-global/device-profile.json`
`{model, soc, cores, ramMb, abi, storageFreeMb, webgpu, androidSdk, screenDpi}`.

Consumed by rules for: model tier defaults (small-model routing), max subagent concurrency,
max rootfs artifact size, screenshot viewport, image quality. Result: phone B behaves
**equivalently** even if it's weaker — it degrades capability, not identity.

**DoD:** a low-RAM profile automatically caps subagent concurrency and picks the cheap model tier.

### B7 — Doctor (parity checklist)

Extend `plugin_doctor` into an app-level **Doctor screen** covering: rootfs version, capability
manifest drift, missing env vars, vault integrity, ssh key presence, provider reachability
(cheap HEAD/ping per provider), MCP connection state, plugin payloads, scheduled tasks armed,
event rules valid, disk headroom, sandbox CLI inventory, `/var/minis` mounts.

Each row: status + one-tap fix + "why it matters". Exposed to the agent as a tool so it can
self-diagnose and repair without asking.

**DoD:** intentionally break 3 things (delete a skill, unset an env var, disable a plugin) →
Doctor flags all 3 with correct fixes.

### B8 — Cross-device sync (phase 2, optional)

rclone-based two-way sync of `/var/minis/shared/**` + `config/**` with conflict policy:
**user-always-wins**, then newest-mtime, then agent-managed merge for append-only logs
(reuse the gardener rule from vc52). Not needed for the Clone Test; valuable for phone+tablet.

---

## 4. PILLAR C — Agent runtime upgrades (ported from the Claude Code gap analysis)

Ordered by leverage. Each is independently shippable.

### C1 — Deferred tool loading / ToolSearch
Names + one-line hints always in prompt; full schemas loaded on demand via a `tool_load`
meta-tool; dispatch returns a corrective hint when a schema wasn't sent. Keeps the surface from
growing linearly with plugins. *(gap-analysis Tier 1 #1)*

### C2 — Persistent task queue (TodoV2)
First-class task objects: `subject, activeForm, status, owner, blockedBy, metadata`. Tools:
`task_create/update/list/get`. Powers: long multi-step jobs, subagent work queues, "what's
still open" across sessions. UI pane in chat (auto-expand while a task is `in_progress`).
*(Tier 1 #3)*

### C3 — Memory: relevance injection + provenance
Manifest of memory files; a cheap side-model (or keyword+BM25 fallback) picks ≤5 relevant
files per query, prefetched at turn start, already-surfaced excluded. Add **provenance and
confidence per entry** (source, date, confidence) — OSINT discipline applied to the agent's own
memory; the gardener promotes only corroborated entries. *(Tier 1 #2 + operator convention)*

### C4 — Permission v2 + netguard surface
Per-tool **per-pattern** rules (`shell_execute: allow "git *"`, `deny "rm -rf *"`), rule
sources (user/session/project), denial-streak → prompt. UI for netguard: per-plugin allowed
hosts, blocked attempts (`NETGUARD:BLOCK`) log, one-tap revoke. *(Tier 1 #5)*

### C5 — Robustness ladder completion
Finish the vc48 ladder: output-token escalation (8k→64k same request), multi-turn recovery,
media-size strip-retry, prompt-too-long collapse→compact chain, 3-strike breaker, and the
no-stop-hook guard on unrecoverable errors. Each rung has its own breaker + telemetry. *(Tier 1 #6)*

### C6 — Subagent budgets & scheduling
Global semaphore (default 2–3 concurrent, device-profile aware), per-run budget caps (A3),
priority queue, and fair-share when background watchers and foreground chat compete.

### C7 — Deterministic replay harness
Record provider responses (redacted) per run → replay a run offline against the current prompt
build → regression diff ("this prompt change altered 3/20 replayed runs"). This is how prompt
changes stop being vibes. Pairs with A9.

### C8 — Tool/MCP schema versioning
Schema hash per tool; plugin/MCP declares compatible range; breaking changes surfaced in the
Doctor and blocked at install (extend the marketplace permission-diff gate concept to schemas).

---

## 5. PILLAR D — Skills, plugins, marketplace

### D1 — Bundled first-party skill pack
Ship a curated skill set **inside the APK assets** so a fresh device is capable offline:
skill-creator, browser-forms, pdf-converter, telegram-backup, web-content-extractor, exa/tavily/
web-search wrappers, android-ui-automation. Pinned versions; user skills still win.

### D2 — Offline marketplace + signed catalog
Cache the catalog + staged payloads in `files/minis-global/marketplace/`; verify signatures
offline; install without network when payload is cached.

### D3 — Skill capability declarations
Each `SKILL.md` may declare `requires: {bins, apk, pip, env, hosts}` → the Doctor checks it and
"Install requirements" runs them into the sandbox as a job. *(Fixes today's silent skill breakage.)*

### D4 — Skill usage telemetry
Invocation counts + success rate → prune dead skills, and let the agent answer "which of my
skills actually earn their keep".

---

## 6. PILLAR E — Mobile survival (this device is the target platform)

### E1 — Background survival as a first-class toggle
`keep_alive` exists as a tool. Promote it: visible switch, auto-arm when a watcher/job starts,
state shown in the HUD (A5) and Doctor (B7). Auto-rearm watchers on app start + boot
(`TelegramRemoteBootReceiver` is the existing precedent).

### E2 — Battery/thermal-aware scheduling
`ScheduledTaskManager` + watchers consult `PowerManager` thermal status, battery %, charging
state → back off / defer heavy jobs; never start a rootfs rebuild at 8% battery.

### E3 — Notification budget & digest
Per-rule cooldown exists; add a global budget (N/hour), priority lanes (blocker > digest >
verbose) and a digest mode for high-frequency watchers.

### E4 — Storage governor
Expose session/attachment storage to the sandbox (read-only mount, or a mediated API) + a
one-tap GC screen with sizes per session; policy for offloading old attachments to
`/var/minis/shared/`. Today the agent literally cannot clean up after itself — that's backwards.

### E5 — Cold-start path
Measure and shorten time-to-first-token after cold start (warm PRoot kernel, lazy MCP connect,
parallel provider auth check). Target: chat usable < 2 s after resume; HUD shows the breakdown.

---

## 7. Data model — where everything lives

| Path | Content | In backup? | Portable? |
|---|---|---|---|
| `files/minis-global/runs/**` | run JSONL + tool logs (A1/A2) | optional (size) | yes |
| `files/minis-global/vault/**` | encrypted secrets (B2) | **yes** | yes (passphrase) |
| `files/minis-global/config/**` | soul/global/permissions/rules/providers (B3) | **yes** | yes |
| `files/minis-global/capabilities/**` | manifest + provision + artifacts (B1) | yes (artifacts optional) | yes |
| `files/minis-global/device-profile.json` | hardware snapshot (B6) | no (device-local) | n/a |
| `files/minis-global/events/fired.jsonl` | event rule fires (A7) | no | n/a |
| `files/minis-global/marketplace/**` | catalog + payload cache (D2) | yes | yes |
| `files/minis-global/tasks/**` | task queue (C2) | yes | yes |
| `/var/minis/shared/**` | durable artifacts + project docs | yes | yes |
| `/var/minis/memory/**` | daily logs + GLOBAL.md | yes | yes |
| `/var/minis/skills/**` | skills | yes | yes |
| `/var/minis/workspace/**` | scratch | no | no |
| per-session dirs | attachments/workspace/offloads | via chat export | size-dependent |

Retention/size policy: runs capped at 200 MB LRU; tool logs capped 2 MB per call (truncated
flag in the event); vault/artifacts never truncated.

---

## 8. Phases (build order)

Each phase = one `vc##` build, one APK, one on-device verification report. Sequencing rule:
**observability first** (A1/A2) because every later phase is debugged through it; portability
second (B) because it is the thing he actually asked for; runtime upgrades third (C).

| Phase | Build | Contents | Depends on |
|---|---|---|---|
| **P0** | vc57 | A1 event bus + A2 stdout tap + run JSONL + A5 minimal HUD chip | — |
| **P1** | vc58 | A3 subagent tree/lanes/messaging + A6 rich HUD notification | P0 |
| **P2** | vc59 | A4 cost ledger + A8 context inspector | P0 |
| **P3** | vc60 | A7 event-rule log/editor + A9 replay/export (redacted) | P0 |
| **P4** | vc61 | B2 vault + env projection | — |
| **P5** | vc62 | B1 capability manifest + provision.sh + rebuild job | P0 (progress UI) |
| **P6** | vc63 | B3 config-as-code + GLOBAL/SOUL propose flow | P4 |
| **P7** | vc64 | B7 Doctor + D3 skill requirements | P5, P6 |
| **P8** | vc65 | B4 one-tap bootstrap + B6 device profile + B5 persona config | P4–P7 |
| **P9** | vc66 | **Clone Test executed on phone B** (acceptance) | P8 |
| **P10** | vc67 | C1 ToolSearch + C8 schema versioning | P0 |
| **P11** | vc68 | C2 task queue + C6 subagent budgets | P1 |
| **P12** | vc69 | C3 memory relevance + provenance | P2 |
| **P13** | vc70 | C4 permission patterns + netguard UI | — |
| **P14** | vc71 | C5 robustness ladder + C7 replay harness | P2, P3 |
| **P15** | vc72 | D1 bundled skills + D2 offline marketplace + D4 telemetry | — |
| **P16** | vc73 | E1–E5 mobile survival pack | P0, P7 |
| **P17** | vc74 | B8 cross-device sync (optional) | P8 |

Release mechanics stay as-is: CI build → APK artifact → install on device → verification report
in `shared/minisx-builds/` + `shared/minisx-version-history.md`. Each phase updates both.

---

## 9. Verification protocol

### 9.1 Per-phase (on-device, not CI-only)
1. Build green in CI; install the signed APK over the previous one (upgrade path, not clean).
2. **Doctor green** (once B7 exists; before that, a manual checklist).
3. Feature-specific DoD from §2–§6, executed live with screenshots/artifacts.
4. Regression pass: 1 chat run, 1 subagent run, 1 job, 1 scheduled task, 1 event rule,
   1 plugin tool, 1 MCP tool, backup+restore of config.
5. Write `shared/openminis-vc##-report.md` with raw evidence (log tails, event JSONL excerpts,
   screenshots) — no claim without an artifact.

### 9.2 The Clone Test (P9 acceptance — the whole point)
On a second Android device with **no prior state**:
1. Install APK, launch, skip everything except the restore screen.
2. Scan the QR / paste the code (ONE user action).
3. Wait for provisioning (progress visible in a lane, not a spinner).
4. **Doctor is green** with zero manual fixes.
5. Agent, in a fresh session, correctly reports: its name, owner profile essentials, its
   skills list, plugin list, MCP list, scheduled tasks, and running watchers.
6. Live proof: run a task that needs a secret (e.g. GitHub API call), a skill (pdf-converter),
   a plugin (websearch), and the sandbox toolchain (python3 + a pip package). All work.
7. Compare `runs/` telemetry shape between devices (same event kinds, same order).

Pass = all 7. Any manual step beyond #2 is a bug, logged and fixed before the phase closes.

### 9.3 Evidence artifacts
Every phase produces: build ID + commit, APK md5, install log, Doctor output (JSON),
run JSONL excerpt, screenshots, and a plain-language "what changed for Marwan".

---

## 10. Security model (touched by this plan)

* **Vault**: AES-GCM, Keystore-wrapped, Argon2id fallback for portability; values never render
  in UI or agent context; `EnvVarRedactor` applied to all exports/screenshots/log lines (A9).
* **Backup**: existing passphrase+KDF path reused for the Operator Bundle; checksum verified
  before apply; failed unlock never writes partial state.
* **Netguard**: plugin host allowlist stays; new UI to inspect + revoke; `NETGUARD:BLOCK` lines
  surface in the HUD (A5) and Doctor (B7).
* **Restore code**: contains no secret — only `{remote, path, salt, kdf}`. Replay-protection via
  rotation + optional expiry.
* **Audit**: every config change, permission change, vault access, and restore lands in
  Settings → Logs (existing audit surface, extended).
* **Hygiene finding (this session):** `/tmp/minis-x/.git/config` has a **PAT embedded in the
  remote URL** in cleartext. Recommend: rotate that token, switch the remote to a credential
  helper / the `gh_ops` plugin, and add a Doctor check for "token embedded in git remote".
  (Value deliberately not reproduced here.)

---

## 11. Risks & open questions

| Risk | Impact | Mitigation |
|---|---|---|
| Event stream volume (a chatty run = thousands of events) | storage/CPU on G35 | bounded buffers, JSONL append, LRU caps, sampling for `llm_delta_stats` |
| Vault portability weakens at-rest security | secret exposure | Keystore primary; passphrase only for export; biometric reveal; rotation |
| Provision.sh drift (whatever installed by hand isn't captured) | "works on my phone" | capture hook after every apk/pip in the sandbox + drift check in Doctor |
| Backup size explodes with artifacts + runs | restore time, storage | exclude `runs/`, cap `artifacts/`, content-addressed dedupe |
| Subagent messaging adds loop risk | runaway agents | budgets + semaphore + cooldown; kill switch per lane |
| Too many phases → nothing ships | the classic failure | each phase is independently useful; P0 alone already fixes the top complaint |
| iOS parity (`src/ios/**` exists) | fork divergence | out of scope for now; keep wire formats shared where they already are (BackupFormat precedent) |

**Open questions for Marwan:**
1. Bootstrap target: private GitHub repo, rclone remote, or Telegram channel? (TG already works
   and is free; GitHub gives version history.)
2. Do you want the second-device Clone Test on a borrowed phone, or a factory-reset profile on
   this device (risky — would wipe app data we care about)?
3. Persona defaults for a brand-new install: fully prefilled operator profile, or a neutral
   "new user" state that imports on restore?

---

## 12. Order of battle (my recommendation)

1. **P0 (vc57)** — event bus + stdout streaming + minimal HUD. Everything after it is cheaper,
   and it directly delivers "see every step in real time".
2. **P1 (vc58)** — subagent lanes + mid-run messaging + completion events. This is the other
   half of what you asked for, and it kills the poll-and-guess pattern.
3. **P4+P5 (vc61/vc62)** — vault + capability manifest. Highest-value portability, no UI-heavy work.
4. **P6–P8** — config-as-code, Doctor, one-tap bootstrap → **P9 Clone Test**.
5. Then runtime (C) and survival (E), informed by what the new telemetry shows is actually broken.

Skip-guard: if any phase slips two builds, cut its scope to the DoD line and ship — no phase is
allowed to block the next one's start.
