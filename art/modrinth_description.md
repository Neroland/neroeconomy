# NeroEconomy

**Buy, sell, haul and bid — a player-driven economy that turns a Neroland server from sixteen solo grinds into one living market.**

NeroEconomy is the **economy** mod of the Neroland ecosystem. It adds credits, player shops, trading terminals, market boards, contracts, delivery jobs, bounties, server-wide auctions, bank blocks and company/faction wallets — a whole retail-and-work layer that gives players a reason to specialise and trade instead of every player building every machine. A miner can sell ore to a fabricator, a logistics player can run delivery jobs, and a faction can pool credits to post bounties and bankroll a colony.

Built on **Neroland Core**, so its currency and reputation APIs, progression gates, config framework, `c:` tags and data-erasure hook are shared with the rest of the lineup — NeroEconomy adds markets and transactions on top and never re-implements the underlying ledger. *(Planned — in design; not yet released.)*

---

## The economy

1. **Credits.** The shared currency unit, backed by Core's currency API — balances live behind Core, and NeroEconomy adds the ways credits move: sales, payouts, fees and transfers, each an atomic, server-side ledger operation with an audit event.
2. **Player shops.** Owned block-entities that hold an inventory and per-item buy/sell prices; the owner stocks and prices them, and every trade is validated against the buyer's live balance and the shop's stock at execution. Shops expose a standard item-handler capability, so hoppers, Create funnels and AE2 buses can restock them.
3. **Trading terminals.** Networked access-point blocks that browse and execute trades against connected shops and market boards from one spot — filtered, searchable, server-validated so listings can't be spoofed. Think an AE2 terminal, but for the economy.
4. **Market boards.** Server-wide listing surfaces where players post standing buy and sell offers visible to everyone, accepted remotely through escrow. This is the price-discovery layer that feeds the optional supply/demand model.
5. **Contracts, delivery jobs & bounties.** Server-tracked job objects with an escrowed payout and an open → claimed → fulfilled / expired state machine: agree to supply goods, haul a payload from origin to destination, or claim a posted reward — all settled atomically on the server.
6. **Auctions.** Timed, server-global bidding events with escrowed bids and a synced countdown, settling the lot to the winner and credits to the seller atomically.
7. **Banks & wallets.** Secure bank blocks (deposit, withdraw, balance, transfer) and shared company/faction wallets with role-based spend permissions, all settling through Core's currency API.

## Built for shared servers

- 🔥 **Trade beats self-sufficiency, sometimes** — pricing, scarcity and delivery friction are tuned so buying from another player is occasionally the smarter move.
- 🛡️ **Anti-dupe by construction** — every credit and item movement is server-authoritative and transactional, with escrow so a crash or disconnect mid-trade can never dupe or destroy value.
- 👥 **Group-aware from day one** — wallets, contracts and bounties work for individual players and for companies/factions alike.
- 🎛️ **Configurable, not prescriptive** — starting credits, fees, taxes, faucets, sinks, price volatility and per-feature toggles are all config, so owners run the economy as light flavour or a hard constraint.

## Privacy (POPIA / GDPR)

NeroEconomy processes player transaction data — personal data keyed to player **UUID** (never names). It **minimises** to only what a transaction needs (UUID + balance/transaction state), never IPs, chat or location history. Transaction history and audit logs have a **configurable, finite retention window** and are purged automatically past it; players can **opt out** of non-essential logging, and detail never hits the server console at info level.

Every feature that touches player data routes through Core's shared **data-erasure hook**, so a single erase request purges you across NeroEconomy and every other Neroland mod, with export on request supported. Any crash telemetry is anonymous and opt-out, carrying **version strings only** — never IPs, usernames, UUIDs or world data.

## Why it fits the ecosystem

- 🧩 **Built on Neroland Core** — one currency API, one reputation system, one progression arc, and shared `c:` tags. NeroEconomy ships in its own creative tab.
- 🔌 **Interoperates, never hard-depends** — soft synergy lights up when siblings are present: **NeroLogistics** routing/drones auto-fulfil delivery jobs, **NeroFactions** backs faction wallets and contracts, **NeroEvents** nudges market prices and themes auctions, **NeroQuests** pays credit rewards, and **NeroCreatures** drops carry trade and bounty value. With them absent, features degrade gracefully.
- 🚀 **Grows into space** — delivery jobs and auctions reach across dimensions in the space arc (Earth → orbit → alien colonies), gated behind Core milestones like `reached_orbit`, and **Nerospace** planet ores feed the market with trade value.
- 🧱 **Cross-loader** — NeoForge, Forge, and Fabric on Minecraft **26.1.2** and **26.2**.

## Requirements & compatibility

- **Requires [Neroland Core](https://modrinth.com/mod/nerolandcore)** — install it alongside NeroEconomy (it loads first). Core provides the currency and reputation APIs every balance settles through.
- Conventional `c:` tags on materials and Core's common compat tags mean Create, AE2, Mekanism, Ad Astra and **Energized Power** (live now on MC 26.1+) interoperate with shop, bank and delivery item handling as the 26.x ecosystem fills in — no hard dependency on any of them.
- **Modpacks are allowed and encouraged** — any platform, no need to ask. Use the official files and credit *NeroEconomy by Neroland* with links to the [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/neroeconomy) and the [GitHub repository](https://github.com/Neroland/neroeconomy). Full terms: [LICENSE](https://github.com/Neroland/neroeconomy/blob/main/LICENSE).

## Links

- 📖 **[Wiki](https://github.com/Neroland/neroeconomy/wiki)** — every block, system, and command documented.
- 💬 **[Discord](https://discord.gg/ArPXvYUzJG)** — chat, help, and sneak peeks.
- 🐞 **[Issues](https://github.com/Neroland/neroeconomy/issues)** — bug reports and feature requests.
- 🗒️ **[Changelog](https://github.com/Neroland/neroeconomy/blob/main/CHANGELOG.md)**
- 🔥 **[Also on CurseForge](https://www.curseforge.com/minecraft/mc-mods/neroeconomy)**

---

*Created by Neroland. The project logo was made with the help of AI image tools; in-game art is generated by the project's own tooling and refined by hand.*
