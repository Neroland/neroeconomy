# Architecture and extensions

## Currency and the store

Core's `CurrencyApi` is the shared facade and `CoreCurrencies.CREDITS` is the currency. NeroEconomy
registers the real provider for the running server and clears its per-server state when the server
stops.

**One store, one save.** All economy state is one saved-data file in the overworld's data folder,
`neroeconomy:economy`, loaded through Core's `SavedDataRecovery` guard, which keeps a backup copy and
restores from it when the file cannot be read. It is made of sections, each with a hard bound or a
per-account bound, a purge, an eraser and an export:

| Section | Holds |
| --- | --- |
| ledger | Balances, system accounts, transaction rows, the supply accounting, vesting and flags |
| market | Player Shop listings, credit claims, processed request ids |
| obligations | Jobs, auctions, bounty progress |
| shop owners, auto-trader owners | Who owns each shop and Auto-Trader block |
| goods, vault | The prototype table of modified stacks, and each player's Vault |
| book | Every open sell and buy order, and the matching engine |
| offers, rules, trade escrow | Offers, auto-trade rules, goods on an open direct-trade table |
| exchange | Prices, holdings, bonds and practice portfolios |
| houses | The Merchant Houses' stock, pressure, reference prices and daily counts |
| admin, gallery, remote spend | Operator counts, the gallery's sandbox, companion-app spend |
| simulation | How far the simulation has got, and the world's secret |

Because there is one file, a save can never persist half an operation. The only operations that span
two files are goods crossing between a live inventory and the store; both are written by the game's
own save, in one pass.

A small marker store records that an economy exists. If the marker is there and the store is not
(and no backup could be restored), or the store was written by a newer version, the economy
**freezes**: every value-moving operation is refused until it is restored or an operator accepts a
fresh start. The four 0.2 stores are adopted once on first load and then emptied.

## Pure rules, thin edges

Rules that move credits or goods live in classes with no Minecraft registry dependency, so they run
in plain unit tests, including an operation fuzzer. A **lot** is `(item id, count, payload id)`:
payload 0 is an unmodified stack, fungible with every other; any other payload id points at a stored
prototype stack (name, enchantments, contents) and is fungible only with itself. The edges convert
between lots and item stacks and enforce the `neroeconomy:untradeable` tag and the encoded-size cap.

## Ledger plans and the Reserve

Every movement of credits is a typed transfer. Multi-leg moves are a **ledger plan**: every leg
applies or none. Every system payout comes out of the Reserve through a plan, within a daily budget;
sections that hold credits (the order book's escrow, offers, rules, jobs and auctions) are
**escrow holders**, and sections that hold goods are **lot holders**. Invariants keep balances,
escrow and the exchange's principal equal to what the sections record. See
[Autonomous economy](Autonomous-Economy.md).

## Platform seams

Common code declares eager `Services`, `PlatformInfo`, and `NetworkPlatform` seams. Each loader
implements them under the common package and registers lifecycle, command, networking, and event
hooks. Economy payloads use the isolated `neroeconomy:main` channel. Common code stays loader-free.

## Intents

Every serverbound payload is declared through one function that puts a shared guard in front of its
handler. The guard checks, in order, the player's request budget, that the menu the packet names is
the one the server has open for that player, and that the player may act: alive, not a spectator,
with the block still present, in the same dimension and within 8 blocks. Menu buttons pass the same
guard. A refused packet gets no reply.

Every value-moving intent carries a request id. One request ledger records the ids per player for 10
minutes and across restarts, for screens, commands and the companion app alike, so a replay acts
once. Prices, inventory, balance, location, kills, and progression are checked again on the server
when an intent runs, and pages are pushed by the server: the client never computes a price or a
balance. Menu values are synchronised in 16-bit pieces and reassembled, so 64-bit balances and
prices display correctly.

## Escrow and claims

Goods or credits enter escrow before an order, offer, job or bid becomes visible, and expiry,
cancellation and settlement move them to the owner: goods to the Vault, credits to the balance or a
credit claim. Per-account caps can refuse a new trade, but never a refund, an expiry or a settlement.
Rows seeded by the admin gallery are flagged as demo rows: they hold no escrow, can never be bought
or filled, and disappear without a claim.

## Time and the simulation

Deadlines and stored timestamps use one economy clock that never runs backwards and advances by at
most 72 hours between two readings. The economy advances in fixed simulation steps and catches up
after a pause or shutdown (see [Autonomous economy](Autonomous-Economy.md)). A time is converted back
to ordinary clock time before it is shown to a player or sent off the server.

## Data

Companies, commodities, Merchant Houses, server bounties and arbitrage-guard conversions are data
packs; see [Data packs](Data-Packs.md).

## Extension points

Abstract `AccountId` keys leave room for faction wallets. Soft sibling integrations remain
runtime-detected and string-keyed; Core is the only hard dependency. The public API package and the
companion-app module are described in [Public APIs and integrations](APIs-and-Integrations.md).
