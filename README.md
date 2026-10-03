# NeroEconomy

NeroEconomy is a server-authoritative economy for the Neroland family of Minecraft mods. It is in
alpha: the version is `mod_version` in `gradle.properties` (`0.2.0-alpha.1` at the time of
writing). It targets Minecraft 26.1.2, 26.2 and 26.3 on Fabric, Forge, and NeoForge (nine builds)
and requires Neroland Core at the version pinned by `nerolandcore_version` in the same file
(`1.13.0`) or a newer 1.x release.

## Shipped features

- Persistent Core credits provider with abstract account identifiers and one-time starting balances.
- Bank Terminal payments to known accounts, and durable proceeds/item collection.
- Owned fixed-price Player Shops that continue selling while their chunks or owners are offline.
- Trading Terminal search across every shop and board listing on the server, with live server
  quotes. A purchase never costs more than the price the buyer confirmed.
- Market Board sell offers (goods escrow) and standing buy offers (credits escrow), with expiry
  refunds, cancellation, per-account caps and idempotent fills.
- Supply contracts, location delivery jobs, entity/player bounties, and timed anti-snipe auctions.
  Jobs are claimed, fulfilled and bid on from the Trading Terminal or by command; posting a job and
  creating an auction are command-only for now.
- Credit Chip redemption with a daily limit, and a Nerospace exotic meteor-material entry.
- Public deterministic Pricing API, runtime NeroLogistics posting seam, and a per-player Core Link
  module for the companion app (balance, history, market, trade, live balance updates).
- Bounded pseudonymous transaction history, player logging opt-out, full data export, and Core-wide
  erasure.
- Recovery backups of every store through Core, and an economy clock that keeps deadlines stable
  across clock changes and long shutdowns.

Faction wallets and faction permissions are deferred until NeroFactions exists. Cross-server/proxy
economies and supply/demand simulation are not part of this release.

## Commands

`/eco`, `/economy` and `/neroeconomy` are three names for the same tree:

- `balance [player|uuid]` (another player's balance needs permission level 2),
  `pay <player|uuid> <amount>`, `history`, and `privacy logging <true|false>`
- `market mine` and `market cancel <id>`
- `contract post item|tag <id> <quantity> <payout>`,
  `delivery post item|tag <id> <quantity> <dimension> <x> <y> <z> <payout>`,
  `bounty post entity <entity type id> <payout> [quantity]` and
  `bounty post player <player> <payout> [quantity]`; each has `claim <id>`, `release <id>` and
  `list`, and contracts and deliveries have `fulfill <id>`
- `auction create <quantity> <minimumBid> <minutes> [interplanetary]`, `auction bid <id> <amount>`
  and `auction list`
- `admin give|take|set <player|uuid> <amount>` (permission level 3 and `adminMutationsEnabled`)
- `export <player|uuid>` and `erase <player|uuid>` (permission level 3)
- `gallery` and `gallery clear` (permission level 3, creative mode and `adminMutationsEnabled`):
  a showcase scene of demo blocks and demo market rows for screenshots

The wiki's [commands page](wiki/Credits-and-Commands.md) has the full syntax.

## Configuration and API

Core writes `config/neroeconomy.properties`. Gameplay settings cover the starting balance, history
retention and bounds, admin mutations, price-multiplier caps, listing duration, auction anti-snipe
and minimum increment, the job claim timeout, idle shop expiry, per-account caps, the stack data
cap, Credit Chip value and daily limit, server-funded jobs, player bounties, and remote trade.
`telemetryEnabled` is local to each installation; telemetry remains inert until a build is made
with a real DSN. The wiki's
[configuration page](wiki/Configuration-and-Administration.md) lists every key.

The stable `za.co.neroland.neroeconomy.api` package exposes abstract accounts, deterministic pricing,
modifier registration, market views, and capped expiring shocks. NeroLogistics can use the
string-keyed delivery seam; NeroEvents can apply shocks; NeroFactions can later register modifiers.
Core Link exposes the player's own balance and history, market search, trades, a live balance
event, and alerts with fixed text.

## Privacy and storage

World SavedData stores balances, escrow, claims, jobs, auctions, shop ownership and opt-out choices
keyed to player UUIDs, never names, and a finite transaction history whose rows hold a reason code,
amount, currency, time and salted one-way pseudonyms rather than UUIDs. Retention is purged at load
and daily. `/eco export` writes a subject-access JSON file covering every store under the world
folder; `/eco erase` invokes Core-wide erasure. No log line carries a player, an amount or a
transaction. Sentry telemetry is inert in published builds, and is capped, scrubbed and opt-out.

See [the wiki](wiki/Home.md), [the privacy notice](PRIVACY.md), [building against Core](USING-CORE.md),
[the changelog](CHANGELOG.md), [the completion audit](AUDIT.md), and
[the runtime smoke test](SMOKE-TEST.md).
