# NeroColonies — handover from NeroEconomy

Priority: **High.** The colony export sale is one of two live faucets in the ecosystem: it pays credits
for goods a colony produced from nothing, at a fixed price, with no limit.

APIs marked **added in 0.3.0** do not exist in NeroEconomy before 0.3.0; check
`SiblingEconomy.API_VERSION >= 1` before using them (see [Feature detection](#feature-detection)).

## What changes on your side

### 1. Pay export sales through a budgeted payout (added in 0.3.0)

Today `ExportBuffer.sell` calls `CurrencyApi.deposit(colony.ownerId(), CREDITS, credits)`. That still
works, but it now draws on one small pool shared by every mod that has not moved over
(`siblingDailyBudget`, 5,000 credits per day by default), and its result is ignored, so goods can leave
the buffer while no credits arrive.

Claim a caller once, from common init, inside your existing NeroEconomy guard:

```java
EconomyCaller caller = SiblingEconomy.claim("nerocolonies", Set.of("export_sale", "stage_reward"));
```

In `ExportBuffer.sell`:

1. Work out the total for the buffer (see 2).
2. `EconomyResult result = caller.pay(server, colony.ownerId(), total, "export_sale", requestKey);`
   with `requestKey` = the colony id plus a per-colony sale counter (never the owner's UUID or name).
3. Remove the goods from the buffer **only when `result.ok()`**. Otherwise keep them and show the
   owner `Component.translatable(result.translationKey())`.

| Reason code | When |
| --- | --- |
| `export_sale` | Selling the export buffer |
| `stage_reward` | Optional: a one-off credit reward for a colony stage or structure milestone |

A single payout is capped at `siblingMaxSinglePayout` (1,000 by default); sell a full buffer in
several calls and stop at the first refusal.

### 2. Price exports from the market instead of a fixed table (added in 0.3.0)

`CommodityApi.bid(server, itemId)` returns what a Merchant House pays for one unmodified unit now
(empty when it does not buy that item). Use it in place of `ExportEntry.baseValue()` times the config
multiplier, and keep your own table as the fallback when it is empty. NeroEconomy ships
`commodities/compat/nerocolonies.json` (colony staples and the four upgrade modules), so the main
outputs already have a price.

### 3. Handle "the market cannot pay right now"

| Result | What the player sees (lang key in NeroEconomy) |
| --- | --- |
| `RESERVE_SHORT` | The market's reserve is low; try again later |
| `CALLER_BUDGET_SPENT` / `SIBLING_BUDGET_SPENT` | Today's budget for colony exports is used up; resets at the next economy day |
| `PLAYER_CAP_REACHED` | This owner has had today's limit of credits from other mods |
| `FROZEN` / `UNAVAILABLE` | The economy is paused; nothing was sold |
| `DUPLICATE` | Treat as success (the same sale was already paid) |

Goods stay in the buffer for every refusal.

## What needs nothing on your side

- **The Auto-Trader** is a vanilla `WorldlyContainer`, so colony depots, hoppers and ducts feed it.
- **Your Core threshold channels**: if NeroEconomy later reacts to them (not part of 0.3.0), it does so
  as a listener; nothing changes on your side.

## Feature detection

Keep the pattern NeroColonies already uses in `CompatRegistry`: `Services.PLATFORM.isModLoaded("neroeconomy")`,
then load one bridge class compiled `compileOnly` against NeroEconomy's jar. Inside the bridge, check
`SiblingEconomy.API_VERSION >= 1`; with an older NeroEconomy keep the current `CurrencyApi.deposit` path
and check its boolean result. With NeroEconomy absent, nothing changes.

## Data-only options

Without any code change, a pack can tune `commodities/compat/nerocolonies.json` (prices, daily caps).
The export sale itself needs the code change above.

## Privacy

Pass the owner's UUID you already hold; NeroEconomy stores only today's total per player for the daily
cap (erasable and exported). Never put a UUID or name in the request key or a log line.
