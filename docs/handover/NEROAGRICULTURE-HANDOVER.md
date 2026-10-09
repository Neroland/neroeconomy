# NeroAgriculture — handover from NeroEconomy

Priority: **High** for NeroEconomy's side: the crop tower sets the price floor for diamonds, nether stars
and echo shards (roughly 840 diamonds an hour per tower at baseline), so the Merchant Houses must never
buy those goods without tight caps. Changes on NeroAgriculture's side are small and data-first.

## What needs nothing on your side

- **Commodities.** NeroEconomy ships `commodities/compat/neroagriculture.json`: the five tier fragments,
  biomass, crop waste, fertilisers and biofuel canisters, as class `farmable` (steep elasticity, small
  per-account share of each day's demand). Vanilla diamonds, nether stars and echo shards are class `rare` on
  NeroEconomy's side, with world-wide daily demand of 8, 1 and 4 units and one or two per account.
- **Component-keyed items** — `resource_fragment`, `resource_seed`, `charged_seed`, `food_seed`,
  `alien_seed`, `engineered_food`, `alien_produce` — are in NeroEconomy's `neroeconomy:never_commodity`
  tag: their identity is in item components, so a price by item id would be wrong. Seeds are never bought
  because they replicate themselves.

## Asked of NeroAgriculture

### 1. Retire the unused premium-goods provider in favour of tags (data)

`AgricultureApi.PremiumGoodsProvider` has no implementation and prices in energy (`unitPriceNf`), not
credits. NeroEconomy will not implement it: pricing belongs to the commodity data, and a provider keyed
by `ServerPlayer` would hand player state to the economy for no reason. Instead:

- Keep the interface (removing public API breaks callers) but mark it deprecated.
- Publish item tags NeroEconomy's data can key on, for example `neroagriculture:fragments`,
  `neroagriculture:fertilisers`, `neroagriculture:biofuels`, and put fragments in the matching `c:`
  tags where one exists. A tag-keyed commodity then follows every new tier with no code anywhere.

### 2. A harvest channel (optional, class-free)

If you want world events and quests to react to farm output, publish a Core `ThresholdEvents` crossing,
for example channel `neroagriculture:tower_output`, scope = dimension id, value = items harvested in the
last economy-hour, rising when it passes a configured level. Never put a player in the scope.
NeroEconomy 0.3.0 does not consume it; NeroQuests' `custom_event` and NeroEvents can.

## Data-only options

A pack can tighten `commodities/compat/neroagriculture.json` (lower `npc_daily_demand`, lower
`per_account_daily_sell`, higher `elasticity`) without any code. NeroEconomy's arbitrage guard re-checks
the result.

## Feature detection

Nothing above needs NeroEconomy's classes. Tags and the threshold channel work with NeroEconomy absent.
