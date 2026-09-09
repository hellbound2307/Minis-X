# Manic Polymarket Bug Bounty — Findings Report

**Submitted by:** Marwan (@MarwanNa71571) · marwannaili.23.07@gmail.com
**Date:** 2026-09-09 · **Scope:** Manic's new Polymarket integration (app.manic.trade/pm)
**Typeform reports:** 2 submitted via https://form.typeform.com/to/TzfbvaPZ on 2026-09-09 (~00:55 and ~01:20 UTC), contact email marwannaili.23.07@gmail.com
**Evidence screenshot:** https://drive.google.com/uc?export=view&id=1qfVF_OJoO1Avj_Xk_0H2yHqHiXStm-5T

---

## Finding 1 — Stale `lastPrice` feed on active markets (P2/P3, data integrity)

**Summary:** The `/charts/pm/events` API on `bo-server-api.manic.trade` returns a `lastPrice` field that is hours stale on active Polymarket-derivative markets — up to 38 points below the live price — while the same response's `chance` field is current. The frontend uses `chance` when present but falls back to `lastPrice` when `chance` is null (`null==e.chance ? V(e.lastPrice) : clampProb(e.chance)`), so any surface using the fallback shows materially wrong odds. `lastPrice` also feeds resolved-state detection helpers in the bundle.

**Same-timestamp evidence (Manic lastPrice vs Manic chance vs Polymarket CLOB midpoint):**

| Market | Manic lastPrice | Manic chance | Polymarket live |
|---|---|---|---|
| Who will post about $LAPTOP by September 30? | 0.12 | 0.50 | 0.500 |
| What price will Solana hit on September 8? | 0.03 | 0.34 | 0.340 |
| What price will Hyperliquid hit in 2026? | 0.61 | 0.735 | 0.735 |
| What price will Bitcoin hit on September 8? | 0.30 | 0.425 | 0.425–0.44 |

Reproduced on 9+ markets across two snapshots taken ~2 hours apart on 2026-09-08 (21:00–23:00 UTC).

**Suggested fix:** recompute `lastPrice` from the same live CLOB stream used for `chance`, or expose `lastPriceTime` so clients can render staleness; label the fallback as "last trade: \<time\>" instead of presenting it as the current price.

---

## Finding 2 — Missing 24h change on liquid outcomes (P3, display)

**Summary:** On high-liquidity event pages, several outcomes render their 24h change as a static dash. Verified on the "Presidential Election Winner 2028" event: Alexandria Ocasio-Cortez ($13.6M volume) shows no 24h change while JD Vance shows −0.6% and Marco Rubio −0.1% on the same page. Also affected: Jon Ossoff, Kamala Harris, Pete Buttigieg, Tucker Carlson. Users cannot compare momentum across candidates.

**Suggested fix:** backfill the 24h delta server-side from Polymarket price history for outcomes missing the field, or render a tooltip ("change unavailable") so the dash reads as intentional.

**Note:** the odds themselves are accurate — all visible candidates on that event were verified against Polymarket within ±0.5 points.

---

*Responsible testing: read-only public market-data surfaces and one unauthenticated session; no other users' data accessed; no service disruption; no funds deposited (display/data-integrity findings only).*
