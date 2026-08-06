# APIs and integrations

The stable package `za.co.neroland.neroeconomy.api` exposes abstract account ids, market views,
quotes, price contexts, modifiers, a deterministic registry, the pricing engine, and market shocks.

Register a modifier with `PriceModifierRegistry.register(modId, modifier)`. Keys are sorted, so the
same inputs produce the same quote. `MarketShocks.applyShock(target, multiplier, duration)` accepts
an item or tag key, clamps to server volatility limits, and expires automatically. Every consumer
must treat quotes as indicative; settlement re-prices.

Core Link module `neroeconomy` schema 1 exposes player-scoped balance/trend, cursor-paged history,
market search, validated trade actions, and rate-limited alerts. It stores no bridge-side copy.

Soft seams are string-keyed: NeroLogistics delivery posting, NeroEvents shocks, future NeroFactions
modifiers, and NeroCreatures bounty entity ids. Core remains the only hard dependency.
