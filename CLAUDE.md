# Project context for AI coding agents — NeroEconomy

> This file and `CLAUDE.md` are byte-identical; always update both together.

## Status and scope

NeroEconomy 0.1.0 is the first playable, feature-complete release candidate. It provides the
persistent credits provider for Neroland Core, privacy-aware ledger, bank and trading terminals,
owned offline shops, escrowed market offers, contracts, deliveries, bounties, auctions, credit chips,
public pricing APIs, and a Core Link module. Faction wallets remain deferred.

Mod id: `neroeconomy`. Package: `za.co.neroland.neroeconomy`. Java 25, Mojang mappings, Minecraft
26.1.2 and 26.2 on Fabric, Forge, and NeoForge.

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

## Build layout and verification

Stonecutter's real root script is `stonecutter.gradle`. Shared code/resources live under `common/`;
loader nodes are `:<loader>:<mc>`. Resources are hand-authored; there is no datagen.

Before handoff run:

`./gradlew :neoforge:26.1.2:build :neoforge:26.2:build :forge:26.1.2:build :forge:26.2:build :fabric:26.1.2:build :fabric:26.2:build :neoforge:26.2:ecjCheck test`

Update the player/contributor documentation in `wiki/` with every feature change. Runtime smoke
testing is owned by the developer and follows `SMOKE-TEST.md`.
