# NeroQuests — handover from NeroEconomy

Priority: **High.** Quest credit rewards are the other live faucet (4,035 credits across the shipped
quests, 2,485 of them per player), and daily contracts would become the largest repeatable one.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## What changes on your side

### 1. Pay `neroquests:currency` rewards through a budgeted payout (added in 0.3.0)

Today `CurrencyReward.grant` calls `CurrencyApi.deposit` and ignores the result. That still works but
draws on one shared pool for every mod that has not moved over, and a refused deposit is silent.

From common init, inside a NeroEconomy guard:

```java
EconomyCaller caller = SiblingEconomy.claim("neroquests", Set.of("quest_reward", "daily_contract"));
```

In the bridge used by `CurrencyReward.grant` (credits only; another currency keeps the Core path):

```java
EconomyResult result = caller.pay(server, context.playerId(), amount, "quest_reward", requestKey);
```

- `requestKey`: your own completion record id (quest id plus a completion sequence number). Never the
  player's UUID or name. Pass `null` if the engine already guarantees a reward is granted once.
- When `!result.ok()`, tell the player in chat with `Component.translatable(result.translationKey())`
  and record the reward as **owed**, so it can be retried later (for example on next login), rather than
  lost. Retrying with the same request key can never pay twice.

| Reason code | When |
| --- | --- |
| `quest_reward` | A `neroquests:currency` reward |
| `daily_contract` | Rewards of the planned daily contracts |

A single payout is capped at `siblingMaxSinglePayout` (1,000 by default). A reward above it must be
split or lowered in the quest data; the shipped quests are all below it.

### 2. Build daily contracts on the budget

Before offering a contract, `caller.canPay(server, player, amount)` and `caller.remainingToday(server)`
tell you whether today's budget can still cover it; offer only contracts that can be paid. Contracts can
also post goods for delivery with the existing `DeliveryJobApi.postSystem` (Reserve-funded, its own daily
cap).

### 3. A counted economy objective (added in 0.3.0)

A new objective type, for example `neroquests:economy`, with `"event": "sold" | "bought" | "earned"`, an
optional `item` or `tag`, and a `count` (units for sold/bought, credits for earned). Feed it from
NeroEconomy's `EconomyEvents`:

| Event | Fires when | Fields you get |
| --- | --- | --- |
| `onSold` | The player's side of a settled sale | seller UUID, item id, quantity, unit price, venue, time |
| `onBought` | The player's side of a settled purchase | buyer UUID, item id, quantity, unit price, venue, time |
| `onFilled` | A resting order filled (owner may be offline) | owner UUID, item id, quantity, unit price, side |
| `onSiblingPaid` | A budgeted payout from any mod landed | player UUID, mod id, reason, amount |

Events never name the other party. Count them against the quest; do not store them anywhere else.
Exclude `onSiblingPaid` with `modId == "neroquests"` from "earned", or quest rewards will complete
"earn N" quests by themselves.

## What works with no change

- **Milestone gates** (added in 0.3.0, data only): `gate_open` objectives on
  `neroeconomy:first_sale`, `first_purchase`, `first_investment`, `first_bond`, `first_offer_accepted`
  and `trader_100`. Offline events open the gate at the player's next join.
- **World channels** (added in 0.3.0, data only): `custom_event` objectives on Core threshold channels
  `neroeconomy:sector_index` (scope = sector word), `neroeconomy:market_shock` (scope = item or tag),
  `neroeconomy:reserve` (scope `world`). The channels are listed in [the index](README.md#class-free-channels);
  `rising` means the number went up.

## Things to fix regardless

- Several objectives only check carried items, so one item set completes them on every alternate
  account. Budgets limit the damage, but a once-per-world reward is the safer shape for large amounts.

## Feature detection

`Services.PLATFORM.isModLoaded("neroeconomy")`, then one bridge class compiled `compileOnly` against
NeroEconomy's jar (the same rule NeroFactions follows). Inside it, use the new calls only when
`SiblingEconomy.API_VERSION >= 1`; otherwise keep `CurrencyApi.deposit` and check its result. The
existing `CurrencyApi.hasRealProvider()` check stays.

## Privacy

You pass the UUID you already hold. Events give you one party's UUID only; never log it, never store it
beyond quest progress, and never show one player another player's trade.
