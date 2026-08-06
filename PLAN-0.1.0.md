# NeroEconomy 0.1.0 completion plan

All implementation stages are complete; runtime verification remains owner-operated. The 0.1.0
feature set ships as `0.0.1-alpha.1` (`mod_version` in `gradle.properties`) until release; "0.1.0"
below names the scope, not the published version string.

- [x] Stage 0: audit Core currency ownership, recovery, networking, platform seams, init order,
  configuration, telemetry, manifests, and dupe surfaces. See `AUDIT.md`.
- [x] Stage 1: abstract-account ledger, bounded pseudonymous history, opt-out, export, and erasure.
- [x] Stage 2: deterministic Pricing API, modifiers, capped expiring shocks, execution re-pricing.
- [x] Stage 3: searchable server-synced Trading Terminal.
- [x] Stage 4: Market Board sell and buy offers with goods/credits escrow, claims, and expiry refunds.
- [x] Stage 5: escrowed supply contracts and location-verified delivery jobs plus Logistics seam.
- [x] Stage 6: string-keyed entity/player bounties with server-side kill verification.
- [x] Stage 7: timed auctions, bid escrow/refunds, anti-snipe extension, and orbit-gate degradation.
- [x] Stage 8: atomic bank redemption of the credit chip and weight-30 exotic meteor material.
- [x] Stage 9: hand-authored definitions plus deterministic stdlib-only generated PNG assets.
- [x] Stage 10: per-player Core Link snapshots, trade actions, and rate-limited alerts.
- [x] Stage 11: README, wiki, agent guidance, changelog, and smoke checklist.
- [ ] Stage 12 runtime: owner executes `SMOKE-TEST.md` in live clients.

## Automated verification

The handoff is valid only after the exact six-cell command in `AGENTS.md` succeeds, JSON parses,
Markdown and `git diff --check` are clean, and `AGENTS.md` equals `CLAUDE.md` byte-for-byte.
