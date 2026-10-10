# Data packs

Companies, commodities, Merchant Houses, server bounties and a few rules are data. A data pack (or a
mod's own data) adds, replaces or removes them without code. All files are read when a world
starts and again on `/reload`.

Validation is strict. A file or an entry that is not valid is skipped, with **one** log warning
that names the file and a fixed reason word (never anything from the file itself). Every other
entry still loads, so a bad file can never stop the server. `/eco admin reload-check` (level 3)
lists what was skipped and every clash.

A file in a pack higher in the pack order replaces the whole file of the same id below it.

## Companies (the exchange)

`data/<namespace>/neroeconomy/stocks/<path>.json` defines the company `<namespace>:<path>`.

```json
{
  "name": "stock.neroeconomy.deepcore_mining",
  "description": "stock.neroeconomy.deepcore_mining.description",
  "sector": "mining",
  "type": "steady",
  "start_price": 120,
  "volatility": 3.6,
  "drift": 6.5,
  "dividend_yield": 1.5,
  "linked": ["#c:ingots/iron", "#c:raw_materials/iron", "minecraft:iron_ore"],
  "enabled": true
}
```

| Field | Required | Meaning |
| --- | :-: | --- |
| `name` | yes | Lang key of the company's name (lower-case letters, digits, `.` `_` `-`). |
| `description` | no | Lang key of a one-line description. Default: the name key plus `.description`. |
| `sector` | yes | One lower-case word (letters, digits, `_`), at most 32 characters. Its name is the lang key `sector.neroeconomy.<sector>`. The shipped sectors are mining, farming, energy, logistics, space, colonies, creatures and decor. |
| `type` | yes | `steady` (small moves, pays dividends) or `risky` (large moves either way). |
| `start_price` | yes | The listing price in whole credits: 1 to 1,000,000. |
| `volatility` | yes | The size of the random part of the price in one economy day, as a percentage (one standard deviation): above 0, at most 50. |
| `drift` | yes | How much the price's anchor grows in one economy week, as a percentage: -50 to 100. |
| `dividend_yield` | no | Dividend per economy week as a percentage of the price: 0 to 25. Default 0. |
| `linked` | yes | Up to 32 item ids and item tag ids (with a leading `#`) whose trade moves the company and whose market shocks reach it. May be empty. No entry twice. |
| `enabled` | no | `false` keeps the file but takes the company off the exchange. Default `true`. |

At most 64 companies are listed. When a company's file goes away (or says `"enabled": false`) it is
**delisted**: its price stops at its last value and its holders are paid out at that price. A new
company starts at its start price, and one that returns carries on from its last price. The index
fund and the three Savings Bond terms are not data; the index is derived from the listed companies
and the bond rates are configuration keys. How the numbers are used is on
[Exchange and investing](Exchange-and-Investing.md).

## Commodities (what the Merchant Houses trade)

`data/<namespace>/neroeconomy/commodities/<path>.json` defines commodities. A file holds one entry
or a list under `"commodities"`.

```json
{ "commodities": [
  { "tag": "minecraft:logs", "reference_price": 4, "npc_daily_supply": 2048,
    "npc_daily_demand": 512, "class": "farmable", "house": "masons", "sector": "colonies" },
  { "item": "minecraft:glass", "reference_price": 3, "npc_daily_supply": 1024,
    "npc_daily_demand": 512, "spread_percent": 12.5 }
] }
```

| Field | Required | Meaning |
| --- | :-: | --- |
| `item` or `tag` | one of | An item id, or an item tag id **without** a leading `#`. A tag covers every item in it, all at the same price. |
| `reference_price` | yes | Whole credits per unit: 1 to 1,000,000. |
| `npc_daily_supply` | no | Units the Houses will **sell** per economy day in total (0 to 1,000,000). Default 0: they never sell it. |
| `npc_daily_demand` | no | Units the Houses will **buy** per economy day in total. Default 0: they never buy it. |
| `per_account_daily_buy`, `per_account_daily_sell` | no | Units one account may buy from or sell to the Houses per day, up to the matching daily total. Default: a share of the total set by the class. |
| `elasticity` | no | How hard the price reacts to trade with the Houses: 0.5 moves it by 50 % across a full day's cap. 0 to 4. Default from the class. |
| `recovery` | no | The fraction of that pressure that decays per simulation step (above 0, at most 1). Default from the class. |
| `spread_percent` | no | The Houses sell this far above the reference price and buy this far below it: 2 to 75. Default `commoditySpreadPercent`. |
| `class` | no | `staple`, `farmable`, `crafted` or `rare`. Default `staple`. |
| `house` | no | The id of the Merchant House that trades it. Default `general`. |
| `sector` | no | The exchange sector its trade counts toward. Default none. |
| `required` | no | `false` skips the entry without a warning when the item or tag is missing. Default `true`; always `false` in a compat file. |
| `enabled` | no | `false` takes the commodity off the market. Default `true`. |

Classes set the defaults of the numbers a file leaves out:

| Class | Elasticity | Recovery | One account may take |
| --- | ---: | ---: | --- |
| staple | 0.4 | 0.04 | 1/4 of the day |
| farmable | 0.9 | 0.02 | 1/8 of the day |
| crafted | 0.6 | 0.03 | 1/4 of the day |
| rare | 0.8 | 0.01 | 1/4 of the day |

- **Prices.** The Houses sell at the **ask** (reference plus the spread, rounded up) and buy at the
  **bid** (reference less the spread, rounded down). A reference price of 1 has a bid of 0: the
  Houses sell it but never buy it.
- **Only unmodified stacks** are ever a commodity. This mod's Credit Chip, anything in the item tag
  `neroeconomy:untradeable`, and anything in `neroeconomy:never_commodity` (potions, enchanted and
  written books, filled maps, shulker boxes, bundles, storage cells and other items whose identity
  lives in their data) are refused.
- **One commodity per item.** An entry that names an item beats one that names a tag containing it.
  Between two entries of the same kind the first in file-id order wins.
- **Compat files.** `commodities/compat/<modid>.json` is read only when that mod is loaded, and an
  entry whose item or tag has no members is skipped silently. NeroEconomy ships compat files for
  other Neroland mods.
- At most 4096 commodities load, and at most 1024 entries are read from one file.

## Merchant Houses

`data/<namespace>/neroeconomy/houses/<id>.json` defines the House `<id>`.

```json
{ "name": "house.neroeconomy.smiths", "working_capital": 8000, "restock_per_day": 1.0,
  "trades": ["metals"], "sectors": ["mining"] }
```

| Field | Meaning |
| --- | --- |
| `name` | Lang key of the House's name. Default `house.<namespace>.<id>`. |
| `working_capital` | Credits the House keeps to buy with: 0 to 1,000,000,000. Default 5000. It is topped up from the Reserve toward this every step, within `npcDailyBudget`, and anything above it is swept back. |
| `restock_per_day` | How much of each commodity's `npc_daily_supply` it restocks per economy day: 0 to 24. Default 1.0. |
| `trades` | Further commodity `house` ids this House trades, besides its own id. |
| `sectors` | Sectors whose commodities this House trades when no House is named for them. |
| `enabled` | `false` leaves the House out. |

A commodity is traded by the House whose id its `house` field names; else the first House (in id
order) that lists that id under `trades`; else the first that lists its sector under `sectors`; else
`general`. There is always a `general` House (a built-in one with 5000 credits of working capital
stands in if no pack defines it). At most 64 Houses load.

## Server bounties

`data/<namespace>/neroeconomy/bounties/<name>.json`:

```json
{ "bounties": [ { "entity": "minecraft:zombie", "quantity": 8, "payout": 40 } ] }
```

`entity` is a hostile mob type (an id that is not a registered monster is left out; players are never
a target), `quantity` is 1 to 64 kills and `payout` 1 to 10,000 credits, paid from the Reserve within
`systemJobDailyPayoutCap`. The simulation posts at most `systemBountiesPerDay` per economy day.

## Arbitrage guard data

Some conversions happen in a mod's code rather than in a recipe file, so the arbitrage guard cannot
read them. `data/<namespace>/neroeconomy/guard/<path>.json` teaches it:

```json
{ "output_bonus": [ { "recipe_type": "nerotech:ore_processing", "extra_output": 1 } ],
  "conversions": [ { "id": "nerotech:scrubber_fouling",
      "inputs": [ { "item": "nerotech:filter_cartridge", "count": 1 } ],
      "result": { "id": "nerotech:dirty_filter", "count": 1 } } ] }
```

`output_bonus` says every recipe of that type gives `extra_output` (1 to 64) more per operation, the
best machine anyone could own; `conversions` are extra recipes (inputs are an `item` or a `tag` with
an optional `count`). A file with `comment` fields is fine; they are ignored. A pack that adds a
machine should add a file like this, or the guard may switch a commodity's buy side off. See
[Autonomous economy](Autonomous-Economy.md).

## Tags

| Tag | Effect |
| --- | --- |
| `neroeconomy:untradeable` | Items that cannot be listed, offered, traded, auctioned, stocked or deposited. Empty by default. |
| `neroeconomy:never_commodity` | Items that can never be a commodity (identity lives in their data). Shipped with a list of such items. |

```json
{ "replace": false, "values": ["minecraft:bedrock"] }
```

Put it at `data/neroeconomy/tags/item/untradeable.json`.
