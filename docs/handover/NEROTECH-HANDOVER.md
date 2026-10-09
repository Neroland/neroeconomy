# Nerotech — handover from NeroEconomy

Priority: **High** for NeroEconomy's side (commodities and the arbitrage guard), **optional** on Nerotech's.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## What needs nothing on your side

- **Commodities.** NeroEconomy ships `commodities/compat/nerotech.json`: dusts by `c:` tag, coils,
  circuit boards, machine frames, fusion and higher fuel cells, filter cartridges (sell-only) and bio-fuels
  (buy-side). It loads only when your items exist.
- **The arbitrage guard** on NeroEconomy's side reads your processing recipe types and applies the two
  code-side bonuses (the ore multiplier and the scrubber step) itself, so the Merchant Houses never buy an
  ingot for more than a third of what they sell the raw ore for, and never buy dirty filters. If you add a
  recipe type or change a bonus, tell NeroEconomy (a changelog line is enough): the guard needs to know.

## Optional: machines that charge credits per operation (added in 0.3.0)

A machine owned by a player may charge its owner per operation, for example for a premium processing
mode:

```java
EconomyCaller caller = SiblingEconomy.claim("nerotech", Set.of("machine_operation"));
EconomyResult result = caller.charge(server, owner, cost, "machine_operation", null);
```

- Run the operation only when `result.ok()`; otherwise idle with a status line built from
  `result.translationKey()`.
- Charge in batches (per completed stack or per minute of running), not per tick: calls are rate-limited
  per calling mod, and a refusal of `RATE_LIMITED` would stall machines.
- Charges go to NeroEconomy's Reserve (a sink). A machine with no owner is never charged.

| Reason code | When |
| --- | --- |
| `machine_operation` | Credits a machine charges its owner for running |

Never pay out from a machine (`pay`): an unattended machine that earns credits is a money printer.

## Data-only options

Packs can tune `commodities/compat/nerotech.json`. Extra item tags on your side (for example `c:dusts/*`
on every dust) widen what tag-keyed commodities cover with no code here.

## Feature detection

`isModLoaded("neroeconomy")` plus one bridge class compiled `compileOnly` against NeroEconomy's jar.

## Privacy

The owner's UUID is passed only for the charge; never log it with the cost.
