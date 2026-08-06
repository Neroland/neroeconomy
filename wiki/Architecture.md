# Architecture and extensions

Core's `CurrencyApi` is the shared facade and `CoreCurrencies.CREDITS` is the currency. NeroEconomy
owns world SavedData balances and registers the real provider for the active server. Accessors use
Core's recovery guard, and provider state is cleared at server stop.

Common code declares eager `Services`, `PlatformInfo`, and `NetworkPlatform` seams. Each loader
implements them under the common package and registers lifecycle, command, networking, and event
hooks. Economy payloads use the isolated `neroeconomy:main` channel.

Market goods, proceeds, payouts, lots, and bids move through durable escrow/claim stores. Packet
request ids are bounded and idempotent. Prices, inventory, balance, location, kills, and progression
are revalidated on the server at execution.

Abstract `AccountId` keys leave room for faction wallets. Soft sibling integrations remain
runtime-detected and string-keyed; Core is the only hard dependency.
