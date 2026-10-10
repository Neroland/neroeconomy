[![NeroLink App — Beta](https://img.shields.io/badge/NeroLink_App-Now_in_Beta-60d4e8?style=for-the-badge)](https://nerolandmc.net/nerolink/#beta) [![Explore the Neroland ecosystem](https://img.shields.io/badge/Explore-The_Neroland_Ecosystem-1a5a6c?style=for-the-badge)](https://nerolandmc.net/ecosystem/)

> 📱 **NeroLink App Beta — your Neroland world on your phone.** Check energy, alerts and machines live, claim quest rewards and search your storage without logging in. **[Join the beta at nerolandmc.net →](https://nerolandmc.net/nerolink/#beta)**
>
> 🌌 **Explore the Neroland ecosystem.** See how NeroEconomy fits together with the rest of the Nero mods — every mod, wiki and changelog in one place. **[View the ecosystem at nerolandmc.net →](https://nerolandmc.net/ecosystem/)** · [NeroEconomy on the website](https://nerolandmc.net/mods/neroeconomy/)

---

# NeroEconomy

**Buy, sell, haul and bid — a player-driven economy that turns a Neroland server from sixteen solo grinds into one living market.**

NeroEconomy is the **economy** mod of the Neroland ecosystem. It adds credits, a private Vault, bank terminals, offline player shops, direct trade, a matching market, auto-trading, a fictional stock exchange, Merchant Houses, supply contracts, delivery jobs, bounties and server-wide auctions — a whole retail-and-work layer that gives players a reason to specialise and trade instead of every player building every machine. A miner can sell ore to a fabricator, a logistics player can run delivery jobs, and a trader can corner the auction house — or buy a share in a fictional company and watch the market move while the server sleeps.

> ⚠️ **Alpha.** NeroEconomy is in alpha (`0.2.0-alpha.1` is the current release; the 0.3 "living economy" work described below is still being finished). Back up your world before upgrading from 0.2 — the four older stores are adopted into one combined store on first start — and expect rough edges. Bug reports are very welcome.

Built on **Neroland Core**, so its currency API, progression gates, config framework, `c:` tags and data-erasure hook are shared with the rest of the lineup — NeroEconomy is the persistent provider behind Core's credits and never re-implements a competing currency.

---

## The economy

1. **Credits and the Reserve.** The shared currency unit. NeroEconomy is the persistent provider behind Core's credits API: balances are keyed to abstract account identifiers, and every movement is an atomic, server-side ledger operation. Every payout the system makes comes out of a **Reserve** and every fee goes back into it, within daily budgets, so credits are never created without limit. Starting balances are locked until a player has some play time.
2. **Bank Terminal.** Check your balance, pay another player by name or offline UUID, redeem a Credit Chip, and open your Vault.
3. **The Vault.** A private store of goods for every player. Purchases made while you were away, refunds and expired orders are delivered there, so nothing is lost if you log out mid-settlement.
4. **Trading terminals & the market.** A real order book: sell and buy orders **match each other** (best price first, oldest first), with sell goods and buy credits held in escrow, idempotent fills and automatic refunds on expiry. Terminals search orders and shops side by side, and a purchase never costs more than the price you confirmed.
5. **Player shops.** Owned block-entities with per-item fixed pricing that keep selling while their chunk is unloaded or their owner is offline; stock and proceeds go to the Vault.
6. **Direct trade & offers.** `/trade <player>` opens a two-sided window — any change clears both confirmations and the contents freeze briefly before one atomic swap. **Offers** send items and credits to a named player, escrowed until they accept or decline, in game or in the app.
7. **Auto-buy, auto-sell & the Auto-Trader.** Standing rules keep placing orders while you are offline, within daily value caps, and the **Auto-Trader** block takes feed from hoppers and ducts. Each rule reports a single clear status.
8. **The Neroland Exchange.** Twelve fictional companies, an index fund and 1-, 3- and 7-day Savings Bonds at the Exchange Terminal, with a published price model, spreads and fees, holding periods, position limits, dividends, splits and daily limits with trading halts. A **practice mode** with play credits lets you learn the ropes without touching real credits. Returns are generous but finite — it is a game economy, not a money printer.
9. **Merchant Houses & the autonomous economy.** Merchant Houses buy and sell commodities with price pressure and daily caps, reference prices follow real player trade, and Reserve-funded jobs, auctions and bounties keep things moving. The simulation **keeps running — and catches up — when nobody is online**, with an arbitrage guard to keep it honest.
10. **Contracts, delivery jobs & bounties.** Server-tracked job objects with an escrowed payout and an open → claimed → fulfilled / expired state machine: agree to supply goods, haul a payload to a specific location, or claim a posted entity/player reward — all settled atomically, all durable across restarts.
11. **Auctions.** Timed, server-global bidding with escrowed bids, outbid refunds and an anti-snipe extension window, settling the lot to the winner and credits to the seller atomically.
12. **Credit Chips.** A craftable, redeemable chip with a stepped daily value and a shared daily budget — also seeded into the exotic meteor loot pool for servers running Nerospace.

## Everything has a screen

The **Economy Hub** puts the wallet, Vault, market, orders, investing, trading, jobs, auctions and shops in one screen (and, for operators, administration) — `/eco hub` opens it. Every control has a tooltip, every tab has a **"Show me"** in-game walkthrough on clearly labelled sample data, and the **Trader's Handbook** is handed to new players on first join, so nobody needs a wiki to start trading.

## Built for shared servers

- 🛡️ **Anti-dupe by construction** — every credit and item movement is server-authoritative, escrowed and idempotent, so a crash, a disconnect or a spammed click can never dupe or destroy value.
- ⚖️ **Fair-play protections** — locked starting credits, wash-trading and alternate-account guards on reference prices, daily limits on the Credit Chip, the exchange and app trading, and no wealth leaderboard. The docs are upfront about what a mod *cannot* defend against (server operators, and `online-mode=false` servers).
- 🎛️ **Configurable, not prescriptive** — starting balance, retention windows, admin mutations, price-shock caps, execution tolerance, listing duration, auction anti-snipe, chip value, player bounties and remote trade are all server config.
- 🔭 **A public pricing API** — deterministic quotes, registerable modifiers and capped, expiring market shocks that other mods can drive.
- 📱 **Trade from your phone, even logged out** — a per-player Core Link module lets the NeroLink companion app show your balance, orders, Vault, portfolio and offers, and place orders, manage auto-trade rules, buy and sell shares and bonds, pay players and answer offers while you are offline, within daily spend and goods limits you control (and can revoke).

## Privacy (POPIA / GDPR)

NeroEconomy processes player economy data — keyed to player **UUID**, never names. It **minimises** to what a transaction needs, and stored transaction rows carry only a reason code, amount, currency, timestamp and **salted one-way pseudonyms** for the counterparties — no names, no UUID strings, no chat, no location history, no inventory dumps.

History is **bounded** and has a **configurable, finite retention window**, purged at load and daily. Players can **opt out** of optional transaction logging with `/eco privacy logging true`; server operators can export a player's data with `/eco export` and erase them with `/eco erase`, which routes through Core's shared **data-erasure hook** so one request purges that player across NeroEconomy and every other Neroland mod. Transaction detail never reaches the server console at info level. The bundled crash reporter (Sentry, EU servers) is inert in current builds; once live it is anonymous, capped and scrubbed, carrying **stack traces and version strings only** — never IPs, usernames, UUIDs or world data. **On by default** — opt out any time with `telemetryEnabled = false` in `config/neroeconomy.properties`.

## Why it fits the ecosystem

- 🧩 **Built on Neroland Core** — one currency API, one progression arc, shared `c:` tags, and its own creative tab. Core is the only hard dependency.
- 🔌 **Interoperates, never hard-depends** — siblings are detected at runtime behind string-keyed interfaces: **NeroLogistics** can post and fulfil delivery jobs, **NeroEvents** can apply capped price shocks, **NeroQuests** can pay credit rewards, and **Nerospace** meteors can drop Credit Chips. With them absent, everything degrades gracefully.
- 🧱 **Cross-loader** — NeoForge, Forge, and Fabric on Minecraft **26.1.2**, **26.2** and **26.3**.

### Coming later

Faction and company wallets with role-based spend permissions are **deferred until NeroFactions exists** — the modifier and account seams are already public so they can slot in without a migration. Cross-server/proxy economies are also out of scope for this release.

## Requirements & compatibility

- **Requires [Neroland Core](https://modrinth.com/mod/nerolandcore)** — install it alongside NeroEconomy (it loads first). Core provides the currency API every balance settles through.
- **Modpacks are allowed and encouraged** — any platform, no need to ask. Use the official files and credit *NeroEconomy by Neroland* with links to this page and the [GitHub repository](https://github.com/Neroland/neroeconomy). Full terms: [LICENSE](https://github.com/Neroland/neroeconomy/blob/main/LICENSE).

## Links

- 📖 **[Wiki](https://github.com/Neroland/neroeconomy/wiki)** — every block, system, and command documented.
- 💬 **[Discord](https://discord.gg/ArPXvYUzJG)** — chat, help, and sneak peeks.
- 🐞 **[Issues](https://github.com/Neroland/neroeconomy/issues)** — bug reports and feature requests.
- 🗒️ **[Changelog](https://github.com/Neroland/neroeconomy/blob/main/CHANGELOG.md)**
- 🟢 **[Also on Modrinth](https://modrinth.com/mod/neroeconomy)**

---

*Created by Neroland. The project logo was made with the help of AI image tools; in-game art is generated by the project's own tooling and refined by hand.*
