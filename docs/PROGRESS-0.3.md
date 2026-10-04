# NeroEconomy 0.3 — progress report

The running record of the 0.3.0-alpha.1 build. It is updated after every item so work can stop at
any point and resume from the **Next** line without redoing anything. The specification is
`DESIGN-0.3.md`; the audit and its resolutions are `AUDIT-2026-10.md`.

## How to resume

1. Read **Next** below. Do that one item only.
2. Build the quick set (`:neoforge:26.2:ecjCheck :neoforge:26.3:ecjCheck test :fabric:26.2:build`).
3. Update the item's row and the **Next** line here, tick the build checklist, stage the files.
4. Run the full nine-cell matrix at the end of each stage, not after each item.

## Next

**E1 + E6 — commodity data and the arbitrage guard** (commodity files, `compat/<modid>.json`,
the loader, the recipe-walking guard in tests and `/eco admin reload-check`). Then E2 + E3
(Merchant Houses and reference prices), then E4 + E5 (NPC auctions, system jobs, empty-server
proof).

## Status

Verified means: compiled on all nine cells, `ecjCheck` clean, and covered by JUnit where the logic
is pure. **Nothing has been run in a game client yet** — every in-game flow is unverified until the
smoke test.

| Stage | Item | State | Verified by |
| --- | --- | --- | --- |
| A | Audit and survey (A1–A7) | Done | Documents in `docs/` |
| R | 62 audit suggestions | Done: 48 done, 12 superseded by a later stage, 2 rejected | Nine cells, 203 tests |
| B | B1 Reserve, supply accounting, invariants | Done | Nine cells, ledger fuzz |
| B | B2 Fees and buyer-only price modifiers | Done | Nine cells, fee property test |
| B | B3 Vault, claims migrated into it | Done | Nine cells, vault tests |
| B | B4 One combined store, migration, freeze on loss | Done | Nine cells, codec round trip |
| B | B5 Simulation clock and step pipeline | Done | Nine cells, clock tests |
| B | B6 Operation fuzzer (100,000 operations), crash-point test | Done | 374 tests |
| C | C2 Escrowed offers | Done | 450 tests |
| C | C3 Order book with matching, live page pushes | Done | 450 tests, book property test |
| C | C4 Auto-buy and auto-sell rules | Done | 450 tests |
| C | C1 Direct trade window, requests, crash-safe trade escrow | Done | Nine cells, 503 tests |
| C | C5 Auto-Trader block (vanilla container, never loads chunks) | Done | Nine cells, 533 tests, fuzzer covers direct trade and block-bound rules |
| D | D1 Listed companies as data (12 companies, index fund, 3 bond terms) | Done | Nine cells, 591 tests |
| D | D2 Price model (deterministic, limits, floor, circuit breaker, commodity link) | Done | Monte-Carlo calibration test, catch-up equivalence test |
| D | D3 Shares, index, bonds, dividends, splits, Reserve-funded gains, Invariant C | Done | Nine cells, 695 tests |
| D | D5 Practice mode (separate code path, never touches the ledger) | Done | 12,000-operation isolation test |
| D | D6 Exchange tests and fuzzer operations | Done | Invariants A, B and C after every fuzz step |
| D | D4 Invest screen | Not started (built with the hub in Stage F) | — |
| E | E1–E6 Autonomous economy | Not started | — |
| F | F1–F4 Economy Hub screens | Not started | — |
| G | G1–G4 Demos, handbook, gallery | Not started | — |
| H | H1–H7 Link schema 2, bridge docs, app | Not started | — |
| I | I1–I2 Public API, handover documents | Not started | — |
| J | J1–J2 Abuse proof and independent red-team | Not started | — |
| K | K1–K6 Release preparation | Not started | — |

## Decisions taken along the way

- **One combined store** (`neroeconomy:economy`) instead of a journal; the vault is a section of it.
- **Staged migration**: the 0.2 files are emptied only on the first start that reads the combined
  store back from disk, so a crash during the first save loses nothing.
- **Escrow refunds** of cancelled or expired buy orders go to the credit claim; offer and rule
  refunds go to the balance.
- **Goods bought by a resting order** always go to the vault, even when the owner is online.
- **A Reserve-paid discount never exceeds the fee collected on the same sale**, so two accounts
  trading back and forth cannot draw on the Reserve. A sibling's discount above the market fee
  therefore needs its own funding source (noted for its handover).
- **Creative-mode players and spectators can never be a source of goods.**
- **Auto-Trader removal**: goods a rule had listed return to the owner's vault; other slot contents
  drop like a chest's.
- **Exchange calibration** (before spread and fees, 240 seeds): steady shares +8.4 % a week with
  dividends and 13.7 % losing weeks; risky +29.7 % and 34.2 % losing weeks; index +21.4 %.
- **A gain the Reserve cannot pay in full is never silently reduced**: the sell is refused unless
  the player accepts the exact reduced amount quoted. Principal always returns in full.
- **Shares split 10-for-1** at 2,000 credits so prices stay readable.
- **Idle practice portfolios are dropped after 30 economy-days.**
- **Trade steps run even when `autonomousEconomy` is off**; only NPC and exchange steps are
  switched off by it.

## Known gaps to close before release

- Unvested starting credits can buy shares and come back unlocked on sale. Harmless at the
  defaults (hold 60 minutes, vest 30), but a shortcut if `shareHoldMinutes` is set below the vest
  time: to be closed in Stage J.

- Every screen, slot-click path, loader hook and migration path is unverified in a running game.
- The companion app's tests cannot be run from the build shell (no Flutter there).
- Two asks for Core are open: a registry-aware backup encode, and detection of a store that fails
  to parse on 26.x.
