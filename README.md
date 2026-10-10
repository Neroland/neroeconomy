# NeroEconomy

NeroEconomy is a server-authoritative economy for the Neroland family of Minecraft mods. It is in
alpha: the version is `mod_version` in `gradle.properties` (`0.3.0-alpha.1` at the time of
writing; the 0.3 work is listed under Unreleased in the changelog). It targets Minecraft 26.1.2,
26.2 and 26.3 on Fabric, Forge, and NeoForge (nine builds) and requires Neroland Core at the version
pinned by `nerolandcore_version` in the same file (`1.13.0`) or a newer 1.x release.

## Shipped features

- **Credits and the Reserve.** A persistent Core credits provider with abstract account identifiers.
  Every payout the system makes comes out of a Reserve and every fee goes back into it, within daily
  budgets, so credits are never created without limit. Starting balances are locked until a player
  has some play time.
- **One Economy Hub screen** for the wallet, Vault, market, orders, investing, trading, jobs,
  auctions, shops and (for operators) administration, with a tooltip on every control, a "Show me"
  walkthrough per tab and an in-game Trader's Handbook.
- **The Vault.** A private store of goods for every player, where purchases made while away,
  refunds and expired orders are delivered.
- **Trading.** An order book where sell and buy orders match each other; Player Shops that keep
  selling while their owner is offline; direct trade with a two-sided window; and offers that are
  escrowed until the recipient answers. A purchase never costs more than the price the buyer
  confirmed.
- **Auto-trade.** Standing buy and sell rules that work while you are offline, and the Auto-Trader
  block that hoppers and ducts can feed.
- **The exchange.** Twelve fictional companies, an index fund, Savings Bonds, dividends, splits and
  daily limits at the Exchange Terminal, with a published price model and a practice mode that
  cannot touch real credits.
- **An autonomous economy.** Merchant Houses that buy and sell commodities with price pressure and
  daily caps, reference prices that follow player trade, Reserve-funded jobs, auctions and bounties,
  a simulation that keeps running and catches up when nobody is online, and an arbitrage guard.
- **Jobs and auctions.** Supply contracts, location delivery jobs, entity and player bounties, and
  timed anti-snipe auctions.
- **Credit Chip** redemption with a daily limit, a daily budget and stepped value, and a Nerospace
  exotic meteor-material entry.
- **Data packs** for companies, commodities, Merchant Houses, server bounties and arbitrage-guard
  conversions.
- **Public API and the companion app.** A deterministic Pricing API, a read-only commodity view, a
  runtime NeroLogistics posting seam, and a per-player Core Link module (link schema 2) that shows
  your data and trades for you, offline included.
- **Privacy.** Bounded pseudonymous transaction history, a logging opt-out, a full data export, and
  Core-wide erasure of every section. There is no wealth leaderboard.
- **Safety.** One combined store with recovery backups through Core, an economy clock that keeps
  deadlines stable across clock changes and long shutdowns, and a freeze that stops the economy if
  its store goes missing.

Faction wallets and faction permissions are deferred until NeroFactions exists. Cross-server/proxy
economies are not part of this release.

## Commands

`/eco`, `/economy` and `/neroeconomy` are three names for the same tree:

- `balance [player|uuid]` (another player's balance needs permission level 2),
  `pay <player|uuid> <amount>`, `history`, `hub [tab]`, and `privacy logging <true|false>`
- `order sell|buy|cancel|mine`, `offer send|list|accept|decline|cancel|mute`,
  `rule add|list|pause|resume|delete|pause-all`, and `/trade <player>` (with `accept`, `decline`
  and `mute`)
- `invest list|buy|sell|portfolio|bond ...` and `invest practice ...`
- `market mine` and `market cancel <id>`
- `contract post item|tag <id> <quantity> <payout>`,
  `delivery post item|tag <id> <quantity> <dimension> <x> <y> <z> <payout>`,
  `bounty post entity <entity type id> <payout> [quantity]` and
  `bounty post player <player> <payout> [quantity]`; each has `claim <id>`, `release <id>` and
  `list`, and contracts and deliveries have `fulfill <id>`
- `auction create <quantity> <minimumBid> <minutes> [interplanetary]`, `auction bid <id> <amount>`
  and `auction list`
- `admin give|take|set <player|uuid> <amount>`, `admin supply|stocks|houses|reload-check|health`,
  `admin market close|open` and `admin sim [step]` (permission level 3; changes also need
  `adminMutationsEnabled`)
- `export <player|uuid>` and `erase <player|uuid>` (permission level 3)
- `gallery` and `gallery clear` (permission level 3, creative mode and `adminMutationsEnabled`):
  a showcase of sandboxed sample data for screenshots

The wiki's [commands page](wiki/Credits-and-Commands.md) has the full syntax.

## Configuration, data packs and API

Core writes `config/neroeconomy.properties`. Settings cover the Reserve and its budgets, fees,
caps, retention, the simulation, Merchant Houses, the exchange, trading limits, the companion app's
daily limits and more. `telemetryEnabled` is local to each installation, defaults to `true` (crash
reporting on by default) and is set to `false` to opt out; telemetry remains inert until a build is
made with a real DSN. The wiki's [configuration page](wiki/Configuration-and-Administration.md)
lists every key, and its [data packs page](wiki/Data-Packs.md) documents the file formats.

The stable `za.co.neroland.neroeconomy.api` package exposes abstract accounts, deterministic pricing,
modifier registration, market views, a read-only commodity view and capped expiring shocks.
NeroLogistics can use the string-keyed delivery seam; NeroEvents can apply shocks; NeroFactions can
later register modifiers. Core Link exposes the player's own data, trading and exchange actions,
live topics, and alerts with fixed text.

## For server operators

NeroEconomy keeps one combined world store, `neroeconomy:economy`, with a recovery backup through
Core. Upgrading from 0.2 adopts the four older stores once; take a backup of the world first (see the
changelog's Migration notes). `/eco admin supply` shows circulation, the Reserve and the day's
minted and burned totals, `/eco admin health` shows whether the economy is frozen, and
`/eco admin market close` stops trading without losing anything.

### What NeroEconomy cannot defend against

The economy is built to stay sound against players, alternate accounts and a stolen app token. It
cannot defend against the people who run the server, or against a server that does not know who its
players are:

- **Operators.** Anyone with permission level 3 can grant, take or set credits, close the market, run
  simulation steps and accept a fresh start. Every such change is counted and recorded under a fixed
  reason, and the counts are shown on the Admin tab and by `/eco admin supply`, but nothing stops an
  operator from using them. Operators can also give items, change game modes (a creative player's
  items can reach the economy through a survival player or a hopper), edit data packs (prices,
  Merchant Houses, companies), edit or replace the save file, or move the host clock. Turn off
  `adminMutationsEnabled` to remove the in-game credit commands; nothing a mod does can remove file
  or console access.
- **`online-mode=false` servers.** With authentication off, a client can claim any name and so any
  account, and can create new accounts without limit. Locked starting credits, per-account caps, the
  daily remote limits and every per-player rate limit assume that one account is one person who
  cannot become someone else. On such a server, treat every balance as untrusted. NeroEconomy never
  stores or compares IP addresses, so it cannot and will not try to detect this.
- **The companion app's token.** A stolen token can act as the player within the daily remote limits
  (`remoteDailySpendLimit` for credits, `remoteDailyGoodsLimit` for goods) until the player revokes it
  in NeroLink.

The wiki's [fair play page](wiki/Fair-Play.md) has the full list.

## Privacy and storage

World SavedData stores balances, escrow, claims, the Vault, orders, offers, rules, holdings, jobs,
auctions, shop ownership and opt-out choices keyed to player UUIDs, never names, and a finite
transaction history whose rows hold a reason code, amount, currency, time and salted one-way
pseudonyms rather than UUIDs. Retention is purged at load and daily. `/eco export` writes a
subject-access JSON file covering every section under the world folder; `/eco erase` invokes
Core-wide erasure. No log line carries a player, an amount or a transaction. Sentry telemetry is
inert in published builds; once live it is on by default, capped, scrubbed and opt-out
(`telemetryEnabled=false` in `config/neroeconomy.properties`).

See [the wiki](wiki/Home.md), [the privacy notice](PRIVACY.md), [building against Core](USING-CORE.md),
[the changelog](CHANGELOG.md), [the completion audit](AUDIT.md), and
[the runtime smoke test](SMOKE-TEST.md).
