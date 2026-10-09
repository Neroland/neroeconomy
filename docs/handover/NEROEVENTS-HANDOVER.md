# NeroEvents — handover from NeroEconomy

Priority: **Low** until NeroEvents has content. These are the seams to use once it does.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later.

## Market shocks

A shock multiplies the price of an item or tag for a while, clamped to NeroEconomy's price band
(`minPriceMultiplier` … `maxPriceMultiplier`). Two ways in:

**Class-free, through Core (added in 0.3.0).** Fire a Core `ThresholdEvents` crossing on channel
`neroevents:market_shock_request`:

| Field | Meaning |
| --- | --- |
| `scope` | The item id (`minecraft:iron_ingot`) or tag with a leading `#` (`#c:ingots/iron`) |
| `value` | The multiplier × 1000 (`1500` = prices up 50 %, `700` = down 30 %) |
| `threshold` | Duration in economy-minutes, 1 … `shockMaxMinutes` (1,440 by default) |
| `rising` | `true` applies the shock, `false` ends the shock on that key early |

NeroEconomy accepts requests only from mods listed in its `shockSources` config (default
`["neroevents"]`), allows at most `maxActiveShocks` (64) at once and ignores a malformed request.

**Typed (exists since 0.2).** `MarketShocks.applyShock(String tagOrItem, double multiplier, Duration)`,
compiled `compileOnly` against NeroEconomy's jar behind `isModLoaded("neroeconomy")`.

## Reacting to the economy (added in 0.3.0, class-free)

Listen on Core `ThresholdEvents` for NeroEconomy's world channels — `neroeconomy:sector_index`,
`neroeconomy:market_shock`, `neroeconomy:reserve`, `neroeconomy:house_stock` — listed in
[the index](README.md#class-free-channels). A crash in a sector could start a world event, for example.

## Paid events and event rewards (added in 0.3.0)

Claim a caller once: `SiblingEconomy.claim("neroevents", Set.of("event_entry", "event_reward"))`.

| Reason code | Call | Notes |
| --- | --- | --- |
| `event_entry` | `caller.charge(server, player, fee, "event_entry", key)` | Admit only when `result.ok()` |
| `event_reward` | `caller.pay(server, player, amount, "event_reward", key)` | Budgeted; refused when today's budget is spent |

Budgets are per calling mod (`siblingCallerDailyBudget`, 2,500 by default) inside a shared sibling
budget. Plan rewards so a busy day running out of budget is acceptable, and tell the player in plain words
with `result.translationKey()`.

## Supply drops

Goods dropped by an event are ordinary items; NeroEconomy prices them through its commodity data when
players sell them. To ask players to deliver goods for a Reserve-funded payout, use
`DeliveryJobApi.postSystem` (exists; its own daily cap).

## Privacy

Shock requests and channels carry places and goods, never a player. Charges and rewards take the UUID you
already hold; never log it.
