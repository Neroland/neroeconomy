# Architecture and extensions

## Currency and stores

Core's `CurrencyApi` is the shared facade and `CoreCurrencies.CREDITS` is the currency. NeroEconomy
registers the real provider for the running server and clears its per-server state when the server
stops.

Four world stores hold the state, all in the overworld's saved data: the ledger (balances and
transaction history), the market (listings, buy offers, claims, processed request ids), the
obligations (jobs, auctions, bounty progress) and the shop owners. Every store is loaded through
Core's `SavedDataRecovery` guard, which keeps a backup copy and restores from it when a file cannot
be read. Each store has an eraser that removes one player and then refreshes its backup, and each
contributes a section to the data export.

The stores are separate files without a shared transaction boundary. As an interim measure the
economy stores are written to disk within about a second of a claim being collected.

## Platform seams

Common code declares eager `Services`, `PlatformInfo`, and `NetworkPlatform` seams. Each loader
implements them under the common package and registers lifecycle, command, networking, and event
hooks. Economy payloads use the isolated `neroeconomy:main` channel.

## Intents

Every serverbound payload is declared through one function that puts a shared guard in front of its
handler. The guard checks, in order, the player's request budget, that the menu the packet names is
the one the server has open for that player, and that the player may act: alive, not a spectator,
with the block still present, in the same dimension and within 8 blocks. Menu buttons pass the same
guard. A refused packet gets no reply.

Every value-moving intent carries a request id. One request ledger in the market store records the
ids per player for 10 minutes and across restarts, for screens, the Bank Terminal and the companion
app alike, so a replay acts once.

Prices, inventory, balance, location, kills, and progression are checked again on the server when
an intent runs. Menu values are synchronised in 16-bit pieces and reassembled, so 64-bit balances
and prices display correctly.

## Escrow and claims

Market goods, proceeds, payouts, lots, and bids move through durable escrow and claim stores. Goods
or credits enter escrow before an offer or job becomes visible, and expiry, cancellation and
settlement move them to the owner's claim. Per-account caps can refuse a new trade, but never a
refund, an expiry or a settlement.

Rows seeded by the admin gallery are flagged as demo rows: they hold no escrow, can never be bought
or filled, are left out of ordinary searches and of the companion app, and disappear without a
claim.

## Time

Deadlines and stored timestamps use one economy clock, persisted with the ledger, that never runs
backwards and advances by at most 72 hours between two readings. A time is converted back to
ordinary clock time before it is shown to a player or sent off the server.

## Extension points

Abstract `AccountId` keys leave room for faction wallets. Soft sibling integrations remain
runtime-detected and string-keyed; Core is the only hard dependency. The public API package and the
companion-app module are described in [Public APIs and integrations](APIs-and-Integrations.md).
