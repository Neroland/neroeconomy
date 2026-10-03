# Project context for AI coding agents — NeroEconomy

> This file and `CLAUDE.md` are byte-identical; always update both together.

## Status and scope

NeroEconomy is in alpha: the 0.1.0 feature set, hardened by an audit-remediation pass. The version
is `mod_version` in `gradle.properties` and the Core pin is `nerolandcore_version` in the same file;
point at those keys instead of repeating the numbers. It provides the persistent credits provider
for Neroland Core, privacy-aware ledger, bank and trading terminals, owned offline shops, escrowed
market offers, contracts, deliveries, bounties, auctions, credit chips, public pricing APIs, and a
Core Link module. Faction wallets remain deferred.

Mod id: `neroeconomy`. Package: `za.co.neroland.neroeconomy`. Java 25, Mojang mappings, Minecraft
26.1.2, 26.2 and 26.3 on Fabric, Forge, and NeoForge.

## Non-negotiable rules

- Never commit or push. Save and stage work for the owner.
- Keep `AGENTS.md` and `CLAUDE.md` byte-identical.
- Keep gameplay server-authoritative and packet intents idempotent. Escrow goods or credits before
  publishing obligations; settle atomically and refund on expiry.
- Store the minimum player data. Transaction records are pseudonymous and bounded; support logging
  opt-out, export, and Core-wide erasure. Telemetry contains no personal data and is opt-out.
- Never run commands against production databases or live server data.
- Keep machine-specific absolute paths out of tracked files.
- Core is the only hard dependency. Detect Nero siblings at runtime behind string-keyed interfaces.
- Keep loader imports out of `common/`; use the ServiceLoader platform trio.

## Rules the code relies on

- **Economy clock.** Every deadline, expiry and stored timestamp uses
  `NeroEconomyCommon.CURRENCY_PROVIDER.now()` (`economy/EconomyClock`), never
  `System.currentTimeMillis()`. Convert with `toWallMillis` before a time leaves the server. The wall
  clock is only for rate limiters and file modification times.
- **One request ledger.** Every value-moving intent (payload, menu button, Link action) carries a
  request id recorded through `MarketSavedData.rememberRequest`. Do not add a second idempotency store.
- **`IntentGuard` on every serverbound payload.** Declare payloads only through
  `NeroEconomyNetwork.intent(...)` and guard menu buttons with `IntentGuard.button`, so the rate
  limit, open-menu, alive, not-spectator and reach checks run before any store is touched.
- **Demo rows.** Gallery listings, buy offers and shops are flagged `demo`: no escrow, never bought
  or filled, hidden from search and Link, removed without a claim. New market surfaces must honour
  the flag.
- **Stores.** Load every store through Core's `SavedDataRecovery.get`, and call `backupNow` in every
  eraser after the purge.
- **New stored data.** Any new stored datum goes into `data/PlayerDataExport`, the store's eraser and
  `PRIVACY.md` in the same change.
- **Fixed text off-server.** Alerts and Link payloads carry catalogue sentences and base item names
  only: no player names, UUIDs, pseudonyms, locations or custom item names.
- **Public API.** `za.co.neroland.neroeconomy.api` is source-compatible: add, never rename or remove.

## Build layout and verification

- **Version-specific code in `common/`.** Non-active nodes run `common/` through Stonecutter (`stonecutterProcessCommon`), so shared code uses the same `//? if >=26.3 {` blocks as the loader `src/` trees. Keep the files in the vcsVersion state, and never put a `*/` inside a disabled block. Datapack files whose format differs by version go in `common/src/main/resources-<mc>/`, which is merged over `common/src/main/resources` for every node at or above `<mc>` (`mergeCommonResources`).

Stonecutter's real root script is `stonecutter.gradle`. Shared code/resources live under `common/`;
loader nodes are `:<loader>:<mc>`. Resources are hand-authored; there is no datagen.

Before handoff run:

`./gradlew :neoforge:26.1.2:build :neoforge:26.2:build :neoforge:26.3:build :forge:26.1.2:build :forge:26.2:build :forge:26.3:build :fabric:26.1.2:build :fabric:26.2:build :fabric:26.3:build :neoforge:26.2:ecjCheck :neoforge:26.3:ecjCheck test`

Update the player/contributor documentation in `wiki/` with every feature change; `wiki/` is public
and must not reference planning, audit or test documents. Runtime smoke testing is owned by the
developer and follows `SMOKE-TEST.md`.
