[![NeroLink App — Beta](https://img.shields.io/badge/NeroLink_App-Now_in_Beta-60d4e8?style=for-the-badge)](https://nerolandmc.net/nerolink/#beta) [![Explore the Neroland ecosystem](https://img.shields.io/badge/Explore-The_Neroland_Ecosystem-1a5a6c?style=for-the-badge)](https://nerolandmc.net/ecosystem/)

> 📱 **NeroLink App Beta — your Neroland world on your phone.** Check energy, alerts and machines live, claim quest rewards and search your storage without logging in. **[Join the beta at nerolandmc.net →](https://nerolandmc.net/nerolink/#beta)**
>
> 🌌 **Explore the Neroland ecosystem.** See how NeroEconomy fits together with the rest of the Nero mods — every mod, wiki and changelog in one place. **[View the ecosystem at nerolandmc.net →](https://nerolandmc.net/ecosystem/)** · [NeroEconomy on the website](https://nerolandmc.net/mods/neroeconomy/)

---

# NeroEconomy

**Buy, sell, haul and bid — a player-driven economy that turns a Neroland server from sixteen solo grinds into one living market.**

NeroEconomy is the **economy** mod of the Neroland ecosystem. It adds credits, bank terminals, offline player shops, trading terminals, market boards, supply contracts, delivery jobs, bounties and server-wide auctions — a whole retail-and-work layer that gives players a reason to specialise and trade instead of every player building every machine. A miner can sell ore to a fabricator, a logistics player can run delivery jobs, and a trader can corner the auction house.

Built on **Neroland Core**, so its currency API, progression gates, config framework, `c:` tags and data-erasure hook are shared with the rest of the lineup — NeroEconomy is the persistent provider behind Core's credits and never re-implements a competing currency.

---

## The economy

1. **Credits.** The shared currency unit. NeroEconomy is the persistent provider behind Core's credits API: balances are keyed to abstract account identifiers, new accounts get a one-time configurable starting balance, and every movement is an atomic, server-side ledger operation.
2. **Bank Terminal.** Check your balance, pay another player by name or offline UUID, redeem a Credit Chip, and collect proceeds and goods that are owed to you — durable, so nothing is lost if you log out mid-settlement.
3. **Player shops.** Owned block-entities with per-item fixed pricing that keep selling while their chunk is unloaded or their owner is offline. Stock and price from the owner view; buyers get a validated, server-checked purchase.
4. **Trading terminals.** Searchable access points that browse live server listings from one spot, with prices re-quoted at execution — a purchase is rejected if the price moved beyond the configured tolerance while you were looking at it.
5. **Market boards.** Server-wide sell offers (goods held in escrow) and standing buy offers (credits held in escrow), with idempotent fills and automatic refunds when an offer expires.
6. **Contracts, delivery jobs & bounties.** Server-tracked job objects with an escrowed payout and an open → claimed → fulfilled / expired state machine: agree to supply goods, haul a payload to a specific location, or claim a posted entity/player reward — all settled atomically, all durable across restarts.
7. **Auctions.** Timed, server-global bidding with escrowed bids, outbid refunds and an anti-snipe extension window, settling the lot to the winner and credits to the seller atomically.
8. **Credit Chips.** A craftable, redeemable chip worth a configurable number of credits — also seeded into the exotic meteor loot pool for servers running Nerospace.

## Built for shared servers

- 🛡️ **Anti-dupe by construction** — every credit and item movement is server-authoritative, escrowed and idempotent, so a crash, a disconnect or a spammed click can never dupe or destroy value.
- 🎛️ **Configurable, not prescriptive** — starting balance, retention windows, admin mutations, price-shock caps, execution tolerance, listing duration, auction anti-snipe, chip value, player bounties and remote trade are all server config.
- 🔭 **A public pricing API** — deterministic quotes, registerable modifiers and capped, expiring market shocks that other mods can drive.
- 📱 **Companion-app ready** — a per-player Core Link module exposes scoped balance and trend, paged transaction history, market search, validated trades and alerts to the NeroLink companion app.

## Privacy (POPIA / GDPR)

NeroEconomy processes player economy data — keyed to player **UUID**, never names. It **minimises** to what a transaction needs, and stored transaction rows carry only a reason code, amount, currency, timestamp and **salted one-way pseudonyms** for the counterparties — no names, no UUID strings, no chat, no location history, no inventory dumps.

History is **bounded** and has a **configurable, finite retention window**, purged at load and daily. Players can **opt out** of optional transaction logging with `/eco privacy logging true`; server operators can export a player's data with `/eco export` and erase them with `/eco erase`, which routes through Core's shared **data-erasure hook** so one request purges that player across NeroEconomy and every other Neroland mod. Transaction detail never reaches the server console at info level. Crash telemetry is anonymous, capped, scrubbed and opt-out, carrying **version strings only** — never IPs, usernames, UUIDs or world data.

## Why it fits the ecosystem

- 🧩 **Built on Neroland Core** — one currency API, one progression arc, shared `c:` tags, and its own creative tab. Core is the only hard dependency.
- 🔌 **Interoperates, never hard-depends** — siblings are detected at runtime behind string-keyed interfaces: **NeroLogistics** can post and fulfil delivery jobs, **NeroEvents** can apply capped price shocks, **NeroQuests** can pay credit rewards, and **Nerospace** meteors can drop Credit Chips. With them absent, everything degrades gracefully.
- 🧱 **Cross-loader** — NeoForge, Forge, and Fabric on Minecraft **26.1.2**, **26.2** and **26.3**.

### Coming later

Faction and company wallets with role-based spend permissions are **deferred until NeroFactions exists** — the modifier and account seams are already public so they can slot in without a migration. Cross-server/proxy economies and simulated supply/demand are also out of scope for this release.

## Requirements & compatibility

- **Requires [Neroland Core](https://modrinth.com/mod/nerolandcore)** — install it alongside NeroEconomy (it loads first). Core provides the currency API every balance settles through.
- **Modpacks are allowed and encouraged** — any platform, no need to ask. Use the official files and credit *NeroEconomy by Neroland* with links to the [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/neroeconomy) and the [GitHub repository](https://github.com/Neroland/neroeconomy). Full terms: [LICENSE](https://github.com/Neroland/neroeconomy/blob/main/LICENSE).

## Links

- 📖 **[Wiki](https://github.com/Neroland/neroeconomy/wiki)** — every block, system, and command documented.
- 💬 **[Discord](https://discord.gg/ArPXvYUzJG)** — chat, help, and sneak peeks.
- 🐞 **[Issues](https://github.com/Neroland/neroeconomy/issues)** — bug reports and feature requests.
- 🗒️ **[Changelog](https://github.com/Neroland/neroeconomy/blob/main/CHANGELOG.md)**
- 🔥 **[Also on CurseForge](https://www.curseforge.com/minecraft/mc-mods/neroeconomy)**

---

*Created by Neroland. The project logo was made with the help of AI image tools; in-game art is generated by the project's own tooling and refined by hand.*
