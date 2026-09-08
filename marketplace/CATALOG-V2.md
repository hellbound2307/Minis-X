# Minis-X Plugin Catalog v2 — must-have list (2026-09-08)

Replaces the old tier list. Built from: (a) kernel rules we shipped (netguard static allowlist, scrubbed env, permission-diff gate), (b) our real operating loops (sword-loop CI, TG backup, Mobile VRP recon, PANOPTES/ARGUS on CF), (c) installed plugins today: `weather`, `unitconv`, `hashcrypt`, `mediasynth`.

---

## 0. Design filter — what becomes a PLUGIN vs a SKILL (the old list got this wrong)

Netguard is a **static manifest allowlist**. So:

- **Plugin = fixed host list (or fully offline).** Predictable egress, survives netguard, installable from catalog, shows in every session's tool surface.
- **Skill/script = arbitrary-target work.** Recon probing, cert monitors on owned domains, RSS from changing feeds — netguard would block every new target, and expanding the manifest later hits the permission-diff gate (uninstall-first). These stay in the shell where we have full net. **Old list's http_status, rss, ssl_cert_monitor were plugin-shaped wrong.**
- **Consolidate identical permission sets.** ci_watch + repo_guard + release_cut all hit api.github.com → ONE plugin `gh_ops`, 3x fewer MCP servers to babysit.
- **Extend existing plugins instead of new ones** where scope overlaps: JWT decode → `hashcrypt` v1.1 (offline, no new surface). No new qr/weather/hash plugin ever — mediasynth/weather/hashcrypt own those.
- **Env-scrub reality:** any plugin needing a token MUST declare it in manifest `env` (`$$VAR` refs). GITHUB_TOKEN, CF_API_TOKEN, EXA_API_KEY, TG_BOT_TOKEN. No ambient env, ever.

---

## 1. P0 — must-haves (ship first, all zero-dep, fixed hosts)

| id | why | tools | netguard | key |
|---|---|---|---|---|
| **websearch** | THE missing primitive. `web_search` was never exposed to my tool surface (known OpenMinis gap, deferred). Every session reaches for it. | `search` (Exa REST), `fallback` (Tavily), auto-pick by env presence | `api.exa.ai`, `api.tavily.com` | EXA_API_KEY and/or TAVILY_API_KEY |
| **tg_backup** | Workspace was WIPED 3x. Telegram backup skill is the source of truth but dies with skill loss — pluginize it so the restore rail survives resets. | `send_text`, `send_file` (→ returns file_id = the restore key), `get_file` (by file_id), `get_me` | `api.telegram.org` | TG_BOT_TOKEN |
| **gh_ops** | Sword-loop core: I find bug → commit → CI → verify. Today we poll runs and cut releases manually. | `ci_runs` (workflow runs + failure log tails), `ci_redispatch`, `pr_issue_triage` (open PRs/issues + mergeable), `release_cut` (tag → release → upload APK asset), `commit_file` (git-less commit path we use) | `api.github.com`, `uploads.github.com`, `objects.githubusercontent.com` | GITHUB_TOKEN |

Ship order P0: `websearch` → `tg_backup` → `gh_ops`. ~2 evenings total; each gets wire-test on-device before `verified:true`.

## 2. P1 — methodology encoded as plugins (this is the durable edge)

| id | why | tools | netguard | key |
|---|---|---|---|---|
| **apk_recon** | The VRP recon pipeline that got wiped 3x, immortalized: census.py + security_cuts.py + AXML parse. Every future APK audit starts at tool-call speed, no script re-derivation. | `census` (exported acts/services/receivers/providers + deeplinks from APK), `axml` (binary XML → XML), `risk_cut` (perm/allowlist flags), `hash_check` | offline, fs: workspace | none (hook: `pip install pyaxmlparser` — pure python, musl-safe) |
| **dnsx** | Domain/DNS management + first 5 min of any recon, keyless. | `resolve` (A/AAAA/MX/TXT/NS/CNAME/CAA via DoH), `crtsh_enum` (subdomains from CT logs), `rdap_whois` (expiry/nameservers/registrar) | `cloudflare-dns.com`, `crt.sh`, `rdap.org` | none |
| **cf_ops** | PANOPTES + ARGUS + future license server = 2 CF accounts, daily deploys. flarectl skill covers DNS only; this covers the workers loop. | `list_workers` + deploy status, `kv_get/put/list`, `d1_query` (read-only SELECT), `tail_trigger` info, `zones` | `api.cloudflare.com` | CF_API_TOKEN (+ CF_ACCOUNT_ID) |

## 3. P2 — lane accelerators (bounty + OSINT)

| id | why | tools | netguard | key |
|---|---|---|---|---|
| **bounty_watch** | Immunefi official keyless API is already proven (`immunefi.com/public-api/bounties.json`). Weekly re-pull = new-launch first-mover alerts; feeds the quiet-corner pipeline. | `catalog` (fetch + normalize), `new_since` (diff vs workspace state file), `rank` (reward/KYC/age filters) | `immunefi.com` | none |
| **breach_check** | ARGUS engine mirror, keyless: direct email/phone → exposure checks without standing up ARGUS. | `xposed_email`, `infostealer_log` (Hudson Rock), `combo_summary` | `xposedornot.com`, `hudsonrock.com` | none |
| **wayback** | OSINT + content resurrection (dead pages, deleted listings, old site versions). | `availability`, `snapshots`, `fetch_snapshot` | `web.archive.org` | none |
| **crypto_market** | Immunefi lane context: token prices/mcaps when triaging crypto programs. | `price`, `market`, `history` | `api.coingecko.com` | none (keyless endpoints) |

## 4. P3 — nice-to-have (only after P0-P2 prove out)

| id | notes | netguard |
|---|---|---|
| **translate** | libretranslate.de keyless; Arabic/French/EN for OSINT docs. If it's flaky, cut — DeepL needs key+payment = no. | `libretranslate.de` |
| **imgkit** | Pillow: resize/convert/crop/stitch/annotate → workspace/media. Offline. Fills the gap between mediasynth (gen) and ocr_read (text). | offline (hook: `apk add py3-pillow`) |
| **currency** | Only the DZD use case survived review (payment-rail math). 1 tool, tiny. | `open.er-api.com` |
| **hashcrypt v1.1** | EXTEND, don't add: `jwt_decode` (offline header/payload/expiry). No new plugin. | offline |

## 5. CUT LIST — from the old list, rejected (reasons)

- `recipes`, `anilist`, `football`, `emoji`, `word_of_day`, `music_brainz`, `stocks`, `hackernews`, `wiki`, `worldtime` — consumer fluff, not the owner's stack; shell handles date/time; wiki = web_fetch one-liner.
- `qr_generator` — duplicate of mediasynth `qr`.
- `open_meteo` — duplicate of installed `weather` (only revisit if wttr data quality annoys us).
- `http_status`, `ssl_cert_monitor`, `rss`, `screenshot_annotate`-on-URLs, `youtube_transcript` — arbitrary/fragile targets: netguard-static-allowlist mismatch (see §0) or brittle scraping. Stay as skills/scripts.

## 6. Acceptance gates (every plugin, before verified:true)

1. Manifest validates against kernel schema (id regex, fs enum, hosts EXACT).
2. Wire test from sandbox: initialize → tools/list → ≥1 tools/call, results as `{content:[{type:"text",text}]}`.
3. Netguard negative test: non-declared host → EPERM + NETGUARD:BLOCK in log.
4. Permission set FINAL at first publish (diff gate makes growth painful by design).
5. index.json entry + PR to `marketplace/plugins/<id>/` with raw manifest URL in `source`.

## 7. Total scope

P0: 3 plugins · P1: 3 · P2: 4 · P3: 4 (incl. 2 extensions). Every P0/P1 item maps to a real operational pain we hit in logs — nothing speculative.
