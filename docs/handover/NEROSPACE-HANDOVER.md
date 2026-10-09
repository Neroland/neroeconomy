# Nerospace — handover from NeroEconomy

Priority: **High** for NeroEconomy's side (commodities and the meteor Credit Chip, both done there);
everything on Nerospace's side is **optional**.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## What needs nothing on your side

- **Commodities.** NeroEconomy ships `commodities/compat/nerospace.json`: planet ores and gems by `c:`
  tag, nerosium dust, rocket fuel canisters and the alien materials. It loads only when your items exist.
- **The meteor Credit Chip.** NeroEconomy's meteor material entry now uses the keys Core reads (`tier`,
  `weight_override`), so the chip drops from natural meteors. Each account's daily redemptions are capped
  and paid from the Reserve.
- **Interplanetary auctions** already check Core's `nerolandcore:reached_orbit` gate.

## Optional: a one-off reward for a first planet visit (added in 0.3.0)

Your `PlanetVisitEvents.onVisit` already fires once per player and planet. If you want a credit reward,
pay it from your side (NeroEconomy never imports Nerospace types):

```java
EconomyCaller caller = SiblingEconomy.claim("nerospace", Set.of("first_planet_visit", "launch_fee", "docking_fee"));
PlanetVisitEvents.onVisit(visit -> caller.pay(server, visit.player().getUUID(), reward,
        "first_planet_visit", "visit/" + visit.planet().asString() + "/" + sequence));
```

The request key must not contain the UUID; use your own visit record sequence. On refusal
(`RESERVE_SHORT`, budget spent) send `Component.translatable(result.translationKey())` and do not retry
automatically: a missed welcome bonus is better than a backlog of payouts. A data-only alternative is a
NeroQuests `reach_dimension` quest with a currency reward.

## Optional: launch, freight and docking fees (added in 0.3.0)

These need a pre-launch decision point on your side that can refuse a flight. `FlightRequestResult`
already carries denials; add one such as `CANNOT_PAY` (append only — NeroLogistics mirrors your denial
names and maps unknown ones to `OTHER`).

1. After every other launch check passes, `caller.charge(server, pilot, fee, "launch_fee", flightId)`.
2. Launch only when `result.ok()`; otherwise deny with the new constant and show the result's lang key.
3. Docking at a station: the same with `"docking_fee"`.

Fees go to NeroEconomy's Reserve. Unvested starting credits may pay them.

| Reason code | When |
| --- | --- |
| `first_planet_visit` | One-off payout on a player's first arrival at a planet |
| `launch_fee` | Charged when a crewed or cargo flight launches |
| `docking_fee` | Charged when docking at a station |

## Feature detection

`isModLoaded("neroeconomy")` plus one bridge class compiled `compileOnly` against NeroEconomy's jar.
Without NeroEconomy, or with an older one, flights stay free and visits pay nothing.

## Privacy

Pass the UUID from the live `ServerPlayer` you already have. Do not log it with a fee or reward.
