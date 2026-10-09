# NeroFactions — handover from NeroEconomy

Priority: **High.** NeroFactions compiles against NeroEconomy's `api` package today; a rename there would
silently switch its pricing off. NeroEconomy keeps that package add-only.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## What stays the same

`PriceModifierRegistry.register("nerofactions", …)`, `PriceModifier`, `PriceContext`, `MarketListingView`
and `AccountId` keep their names and signatures. `integration/economy/EconomyIntegration` builds and runs
unchanged against 0.3.0.

## What changed in how your modifier is applied (no code change, but know it)

- A modifier changes the **buyer's** price only. The seller always receives the base price less the
  market fee.
- A **surcharge** goes to the Reserve.
- A **discount** is paid by the Reserve, but never more than the market fee collected on that same sale
  (2 % by default), so two accounts trading back and forth can never draw on it. Your discount cap is
  15 %, so most of a large discount is not applied today: the buyer pays at most the fee less.
- Modifiers never apply to Merchant House quotes or the exchange.
- NeroEconomy still clamps the product of every modifier and shock to its own price band and skips a
  modifier that throws.

To give the full faction discount, the difference above the fee needs its own funding. The intended
route is a faction treasury (below) that pays the buyer's difference; until that exists, the discount is
effectively capped at the fee.

## What changes on your side

### 1. Faction treasuries through foreign accounts (added in 0.3.0)

NeroEconomy 0.3.0 persists accounts of a third kind owned by a sibling mod. Your dormant
`FactionAccountId` uses kind `faction`; foreign accounts use the **caller's mod id** as their kind, so:

```java
EconomyCaller caller = SiblingEconomy.claim("nerofactions", Set.of("dues", "treasury_payout", "terminal_purchase"));
AccountId treasury = new ForeignAccountId("nerofactions", factionId);   // replaces FactionAccountId
caller.deposit(server, player, treasury, amount, "dues", requestKey);          // member -> treasury
caller.withdraw(server, treasury, player, amount, "treasury_payout", requestKey); // treasury -> member
caller.balance(server, treasury);
```

Only `nerofactions` can move `nerofactions` accounts. Balances survive restarts and count in the
economy's supply checks. A faction id is not personal data.

| Reason code | When |
| --- | --- |
| `dues` | A member pays into the faction treasury |
| `treasury_payout` | The treasury pays a member |
| `terminal_purchase` | The faction terminal priced in credits (charge the player with `caller.charge`, or deposit into the treasury) |

### 2. Trade reputation from the sale event (added in 0.3.0)

Register `EconomyEvents.onSold(sold -> …)` and award reputation when `sold.itemId()` matches one of a
faction's specialities. You get the seller's UUID, the item id, quantity, unit price and venue — never
the buyer. Do the reputation write in your own store as you do today; do not keep the event.

### 3. The faction terminal in credits

Price it with `caller.charge(server, player, price, "terminal_purchase", requestKey)` and deliver only
when `result.ok()`; show `result.translationKey()` otherwise.

## Feature detection

Unchanged: `compileOnly` NeroEconomy, one `isModLoaded("neroeconomy")` guard, `EconomyIntegration` the
only class importing NeroEconomy types. Use the new calls only when `SiblingEconomy.API_VERSION >= 1`.

## Data-only options

Faction trade tables stay data. Nothing in NeroEconomy needs a data change for them.

## Privacy

The modifier and the event use the buyer's or seller's UUID only on the server, at that moment. Do not
log it, and never tell one player who traded with them.
