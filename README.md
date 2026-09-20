# Minis X

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android-lightgrey.svg)](#building-from-source)

**A local-first AI agent runtime with a real Linux computer to work with.**

Minis X turns an Android device into an extensible agent runtime. The agent gets a
sandboxed Linux environment, real files, shell access, browser automation,
persistent memory, skills, plugins and background jobs.

**The runtime is local; inference is not necessarily.** Files, tools, the sandbox,
memory, plugins, secrets and telemetry all live on the device. Model inference goes
to the provider of your choice, through your own credentials — so "private" means
your data and execution stay here, not that the model runs here.

Minis X is a fork of [OpenMinis](https://github.com/OpenMinis/OpenMinis). The upstream
agent experience is intact; what this fork adds is the runtime, isolation, security,
observability and autonomy layers documented below.

This fork builds and ships **Android** (`com.openminis.app.x`, installs side by side
with upstream). The iOS sources are inherited from upstream and are not built here.

Latest Android build: `1.19-x32` (`versionCode 69`).

---

## Why Minis X

- **Real execution** — install packages, run programs, manipulate real files inside a
  sandbox on the device, not a simulated tool surface.
- **Isolated worlds** — Seasons give an agent its own memory, files, skills and
  identity, with no path to any other season's.
- **Observable execution** — every run produces inspectable telemetry, including
  subagent trees and a live output tap.
- **Extensible at runtime** — plugins and agent-written tools install without shipping
  another APK.
- **Secrets that survive** — a vault the agent can write to, whose values it can never
  read back.
- **Background autonomy** — scheduled tasks, event rules and watchers keep working
  after the UI is closed.

---

## Architecture

```
                    ┌──────────────┐
                    │    Model     │   ← remote, your credentials
                    └──────┬───────┘
  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┼ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  device boundary
                    ┌──────▼───────┐
                    │  Minis agent │   ← prompt, tools, context
                    └──────┬───────┘
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       Memory            Tools            Skills
          │                │                │
          └────────────────┼────────────────┘
                           ▼
                  ┌─────────────────┐
                  │  Linux sandbox  │
                  │ shell · files   │
                  │ browser · jobs  │
                  └─────────────────┘
                           │
                    ┌──────┴──────┐
                    ▼             ▼
                 Seasons       Telemetry
                 isolation     run history
```

Everything below the device boundary is local. The boundary is the whole point of the
secrets and isolation sections: what the agent *can reach* is decided here, not by the
model's goodwill.

---

## What Minis X adds over upstream OpenMinis

All of it is in-tree: no external service, no paid tier, nothing leaving the device.

### The APK becomes a platform

| | |
|---|---|
| **Plugin kernel** | New tools are installed at runtime from a manifest + MCP server — no app update. Strict validation of declared permissions, filesystem scopes and network hosts; install hooks run inside the sandbox; uninstall is fully reversible and audited. |
| **Marketplace** | Browse, inspect declared permissions, install by id. A **permission-diff gate** refuses an update that *expands* permissions, so a plugin cannot widen its own reach silently. |
| **Netguard** | An `LD_PRELOAD` `connect()` guard, armed per plugin spawn. The manifest's host allowlist is enforced at the syscall, not by convention; violations are logged as `NETGUARD:BLOCK`. |
| **Minted tools** | `py_meta_tools` lets the agent write a Python tool and call it **by name in the same conversation** — with a manager UI, per-tool timeout, enable toggle and code viewer. |

### Isolation

| | |
|---|---|
| **Seasons** | First-class isolated agent worlds. An isolated season gets its own empty `/var/minis` namespace — no memory, skills, shared files, projects, run logs or plugin payloads from any other season. The isolation level is fixed **at creation** and has no setter: a season that already ran with full access has pulled global memory into its own history, so a later flip would be theatre. |
| **Season-scoped identity** | Memory (including `GLOBAL.md`), skills, the file-mention index and the documents provider all resolve through the active season. Emptying the sandbox is meaningless if the system prompt still carries the operator profile. |
| **Fail-closed boundaries** | `minis-sessions-cli` and the vault refuse outright inside an isolated season, rather than returning an empty result — a silent empty answer reads as "there is no history", which the agent would then reason from. |
| **Subagent context isolation** | Children see only their mission by default, never the parent transcript. |

### Secrets

| | |
|---|---|
| **Vault** | An agent-writable encrypted store that survives a sandbox reset. Values are never readable back — no tool returns one, by construction rather than by convention. `vault_to_env` projects an entry into the environment the sandbox already injects, so a credential moves app-side → shell env **without ever crossing the transcript**. |

### Observability — the agent can see its own work

| | |
|---|---|
| **Run telemetry** | Every tool call is recorded to an append-only JSONL per run: start/end, duration, status, exit code, byte counts, and a **redacted** argument digest. One run per `ChatViewModel`, so concurrent parent and subagent runs never interleave. |
| **Run tree** | Subagent runs are linked to their spawner via `parentRunId`, and open **at spawn** — so even a child that only thinks and answers leaves a log. |
| **Live output tap** | Shell stdout is streamed into the run log (throttled) and the HUD, so a long build stops being a black box. |
| **Self-inspection** | `files/minis-global/runs` is bound into the sandbox at `/var/minis/runs`, so the agent can read back its own telemetry — tail a build, answer "how long did step 3 take", diff two runs. |

### Hardening (from a security audit of the fork)

| | |
|---|---|
| **Tool permissions** | Allow / Ask / Not-allowed per core agent tool, with background heads-up confirmation. |
| **SSRF filter** | `web_fetch` checks every resolved address, fail-closed. |
| **Context overflow** | Provider-specific overflow classifier with compact-and-retry recovery. |
| **Resource guards** | An address-space (`RLIMIT_AS`) guard on every shell command and detached job, plus leaves-first timeout kills — one greedy process can no longer wedge the sandbox. |
| **Memory hygiene** | A dream prune pass, plus a gardener that promotes journal entries to the wiki with a **user-always-wins** rule. |

### Autonomy

| | |
|---|---|
| **Scheduled tasks** | AlarmManager-backed (`setExactAndAllowWhileIdle`), re-registered on boot, able to append into an existing session rather than only starting a new one. |
| **Event bus** | Rules that wake the agent on a notification, an emitted event, or a **tick** — the interval source that lets a rule drive its own loop. |
| **Background survival** | An explicit keep-alive mode with a persistent notification, so watchers and jobs outlive the app being closed. |

### Build & developer tooling

- **CI unbroken and self-checking.** The upstream `android-actions/setup-android@v3`
  step now fails (it runs `sdkmanager tools`, a package that no longer exists in
  cmdline-tools 16.0) — replaced with a direct export of the runner's SDK and
  licence acceptance before `--install`.
- **`scripts/kotlin-lint.py`** — a pre-push gate for the traps that a compiler catches
  in a second but CI catches in 25 minutes: `/*` nested inside a block comment, and
  control flow inside an inline lambda. Zero false positives on this codebase.
- **`docs/PLAN.md`** — the platform roadmap (observability, portability, runtime
  upgrades, mobile survival) and the **clone-test contract**: install on a new device,
  restore from one code, and be functionally identical without re-entering eleven keys.

### Versioning

`vc` is the Android `versionCode`; `x` is the fork build counter. Upstream 1.13 →
fork `1.13-x1` … `1.16-x5` → Minis X `1.17-x1` … present. Read the `VERSION` constant
in the worker/app rather than trusting commit messages, which are not always accurate.

---

## Building from source

Minis X ships a Linux sandbox inside the app, so the native dependencies (iSH on
iOS, PRoot on Android, FFmpeg, LAME) and the Alpine rootfs are **built from
source** rather than committed as binaries.

**→ See [BUILDING.md](BUILDING.md) for the full first-build guide.**

The short version:

```sh
git clone --recurse-submodules https://github.com/hellbound2307/Minis-X.git
cd Minis-X

# iOS  — order matters: FFmpeg links against LAME
./deps/build_lame.sh && ./deps/build_ffmpeg.sh
./deps/build_ish.sh && ./deps/prepare_alpine_rootfs.sh
open src/ios/Minis.xcodeproj

# Android — needs NDK r28+
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

`BUILDING.md` covers the toolchain requirements per platform, the build-time
customization templates, and a troubleshooting section for the failure modes
you are most likely to hit.

---

## Repository layout

```
src/ios/          iOS app (Swift / SwiftUI) + share, widget and file-provider extensions
src/android/      Android app (Kotlin / Compose) + JNI native code
src/shared/       Assets shared by both platforms
deps/             Native dependency build scripts and vendored sources
docs/specs/       Architecture and interface specifications
scripts/          Rootfs preparation and developer tooling
```

---

## Acknowledgements

Minis X stands on a great deal of open-source work. Our thanks to the
maintainers of these projects — the full inventory, with versions and license
terms, is in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

**The sandbox** — the heart of the product:

- **[iSH](https://github.com/ish-app/ish)** (GPLv3) — Linux usermode emulation on
  iOS. We run [an ARM64 fork](https://github.com/OpenMinis/ish-arm64).
- **[PRoot](https://github.com/termux/proot)** (GPLv2) — user-space chroot for the
  Android sandbox, via [our fork](https://github.com/OpenMinis/proot);
  **[talloc](https://talloc.samba.org)** (LGPLv3+) underpins it.
- **[Alpine Linux](https://alpinelinux.org)** — the minirootfs the sandbox boots.

**Media & text** — [FFmpeg](https://ffmpeg.org) (LGPL-2.1+),
[LAME](https://lame.sourceforge.io) (LGPL), [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT),
[KaTeX](https://katex.org) (MIT).

**iOS** — [SwiftAnthropic](https://github.com/jamesrochabrun/SwiftAnthropic),
[SwiftMath](https://github.com/mgriebling/SwiftMath),
[RealTimeCutVADLibrary](https://github.com/helloooideeeeea/RealTimeCutVADLibrary) (all MIT),
[swift-cmark](https://github.com/swiftlang/swift-cmark) (BSD-2-Clause), and the
Apple / Swift Server Workgroup packages (Apache-2.0).

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack),
[OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/),
[kotlinx](https://github.com/Kotlin) serialization & coroutines,
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer),
[Reorderable](https://github.com/Calvin-LL/Reorderable), [ACRA](https://github.com/ACRA/acra)
(all Apache-2.0), and [Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT).

---

## License

Minis X is licensed under the **[GNU General Public License v3.0](LICENSE)**.

The app links GPL-licensed components — [iSH](https://github.com/OpenMinis/ish-arm64)
(GPLv3) and [PRoot](https://github.com/OpenMinis/proot) (GPLv2) — so the combined
work is distributed under GPLv3. Bundled third-party licenses are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Contact

- **Telegram**: [@mr1labs](https://t.me/mr1labs)
- **Email**: [marwannaili.23.07@gmail.com](mailto:marwannaili.23.07@gmail.com)
- **Portfolio**: [marwan-naili.me](https://marwan-naili.me)
- **Issues**: Bug reports, feature requests and discussion via
  [GitHub Issues](https://github.com/hellbound2307/Minis-X/issues)
