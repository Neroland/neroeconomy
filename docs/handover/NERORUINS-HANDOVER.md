# NeroRuins — handover from NeroEconomy

Priority: **Low** until NeroRuins has items. Everything here is data.

## Relics, salvage and keycards as commodities (data only)

Once item ids exist, ship a commodity file from your own data, keyed only on your own items:

```
data/neroruins/neroeconomy/commodities/neroruins.json
{ "commodities": [
  { "item": "neroruins:salvage_scrap", "reference_price": 8, "npc_daily_demand": 64, "class": "farmable",
    "house": "general", "sector": "mining", "required": false }
] }
```

- Fields are the ones NeroEconomy's data packs use: `item` or `tag`, `reference_price`, `npc_daily_supply`,
  `npc_daily_demand`, `per_account_daily_buy`, `per_account_daily_sell`, `elasticity`, `recovery`,
  `spread_percent`, `class` (`staple`, `farmable`, `crafted`, `rare`), `house`, `sector`, `required`,
  `enabled`. NeroEconomy's `/eco admin reload-check` reports any problem and runs the arbitrage guard.
- Anything a structure regenerates or a loot table rolls without limit is `farmable`.
- With `"required": false`, ids the game does not know are skipped quietly, so the file is harmless
  before the items exist.
- Code route (added in 0.3.0): `CommodityApi.registerDefault("neroruins", spec)` does the same from
  common init; a data pack entry for the same key always wins.

## Named relics and vault keycards: never a commodity (data only)

A relic whose identity is in its components (a name, a lore line, a bound location) must never be priced
by item id. Add such items to the item tag `neroeconomy:never_commodity`, and keycards that must not
change hands to `neroeconomy:untradeable`, from your own data with `"replace": false`. An auction-only
flag is planned in NeroEconomy but not part of 0.3.0; until then the never-commodity tag keeps relics off
the Merchant Houses while players can still sell them to each other.

## Feature detection

None needed: data under the `neroeconomy` paths is ignored when NeroEconomy is absent.
