# NeroEconomy

NeroEconomy 0.1.0 is a server-authoritative economy for the Neroland Minecraft ecosystem. It targets
Minecraft 26.1.2 and 26.2 on Fabric, Forge, and NeoForge and requires Neroland Core.

## Shipped features

- Persistent Core credits provider with abstract account identifiers and one-time starting balances.
- Bank Terminal payments and durable proceeds/item collection.
- Owned fixed-price Player Shops that continue selling while their chunks or owners are offline.
- Trading Terminal search with live server quotes and execution-time re-pricing.
- Market Board sell offers (goods escrow) and standing buy offers (credits escrow), expiry refunds,
  and idempotent fills.
- Supply contracts, location delivery jobs, entity/player bounties, and timed anti-snipe auctions.
- Credit Chip redemption and Nerospace exotic meteor-material integration.
- Public deterministic Pricing API, runtime NeroLogistics posting seam, and per-player Core Link module.
- Bounded pseudonymous transaction history, player logging opt-out, data export, and Core-wide erasure.

Faction wallets and faction permissions are deferred until NeroFactions exists. Cross-server/proxy
economies and supply/demand simulation are not part of 0.1.0.

## Commands

Both `/eco` and `/economy` expose the same tree:

- `balance [player]`, `pay <player|uuid> <amount>`, `history`, and
  `privacy logging <true|false>`
- `admin give|take|set <player|uuid> <amount>`
- `export <player|uuid>` and `erase <player|uuid>`
- `contract`, `delivery`, `bounty`, and `auction` subcommands for posting, listing, claiming,
  fulfilling, and bidding

## Configuration and API

Core writes `config/neroeconomy.properties`. Gameplay settings cover starting balance, bounded
history, admin mutations, shock caps, execution tolerance, listing duration, auction anti-snipe,
chip value, player bounties, and remote trade. `telemetryEnabled` is client-local; telemetry remains
inert until a non-placeholder DSN is supplied.

The stable `za.co.neroland.neroeconomy.api` package exposes abstract accounts, deterministic pricing,
modifier registration, market views, and capped expiring shocks. NeroLogistics can use the
string-keyed delivery seam; NeroEvents can apply shocks; NeroFactions can later register modifiers.
Core Link exposes scoped balance/trend, transaction paging, market search, trades, and alerts.

## Privacy and storage

World SavedData stores balances, escrow, claims, ownership, opt-out choices, and finite history
containing UUIDs, amounts, currency, timestamp, counterparty, and a reason code only. Retention is
purged at load and daily. `/eco export` writes subject-access JSON under the world folder;
`/eco erase` invokes Core-wide erasure. No transaction detail is info-logged. Sentry is capped,
scrubbed, no-PII, and opt-out.

See [the wiki](wiki/Home.md), [the audit](AUDIT.md), and [the runtime checklist](SMOKE-TEST.md).
