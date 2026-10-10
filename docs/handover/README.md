# Handover documents — what other mods can do with NeroEconomy

One document per mod that needs, or can choose, a change on its own side to work with NeroEconomy 0.3.0,
plus the asks for Neroland Core. NeroEconomy never edits another mod and never references another mod's
classes; everything here is either data, a Core channel, or a call into NeroEconomy's public `api`
package from a bridge class that the other mod compiles `compileOnly` and loads only behind
`isModLoaded("neroeconomy")`.

APIs marked **added in 0.3.0** are new in NeroEconomy 0.3.0. Check `SiblingEconomy.API_VERSION >= 1`
before using them; with an older NeroEconomy, keep Core's `CurrencyApi` and check its result.

## Documents

| Mod | Priority | What it is about | Document |
| --- | --- | --- | --- |
| NeroColonies | High | Budgeted payout and market price for the export sale | [NEROCOLONIES-HANDOVER.md](NEROCOLONIES-HANDOVER.md) |
| NeroQuests | High | Budgeted quest rewards, daily contracts, economy objectives, milestone gates | [NEROQUESTS-HANDOVER.md](NEROQUESTS-HANDOVER.md) |
| NeroFactions | High | Unchanged pricing API, faction treasuries, trade reputation from sales | [NEROFACTIONS-HANDOVER.md](NEROFACTIONS-HANDOVER.md) |
| NeroLogistics | High / Medium | Auto-Trader container (nothing needed), optional freight fee | [NEROLOGISTICS-HANDOVER.md](NEROLOGISTICS-HANDOVER.md) |
| Nerospace | High (done here) | Commodities and meteor chip done; optional first-visit reward and fees | [NEROSPACE-HANDOVER.md](NEROSPACE-HANDOVER.md) |
| Nerotech | High (done here) | Commodities and arbitrage guard done; optional machine charges | [NEROTECH-HANDOVER.md](NEROTECH-HANDOVER.md) |
| NeroAgriculture | High (done here) | Farm goods capped here; replace the premium-goods provider with tags | [NEROAGRICULTURE-HANDOVER.md](NEROAGRICULTURE-HANDOVER.md) |
| NeroNotes | Medium | Disk Exchanger pricing bridge with composer royalty | [NERONOTES-HANDOVER.md](NERONOTES-HANDOVER.md) |
| NeroDecor | Low | Optional item tags mirroring block tags | [NERODECOR-HANDOVER.md](NERODECOR-HANDOVER.md) |
| NeroEvents | Low | Market shocks, economy channels, paid events | [NEROEVENTS-HANDOVER.md](NEROEVENTS-HANDOVER.md) |
| NeroRuins | Low | Commodity data and never-commodity tags for relics | [NERORUINS-HANDOVER.md](NERORUINS-HANDOVER.md) |
| NeroSecurity | Low | Paid tiers; owners always reach their own economy blocks | [NEROSECURITY-HANDOVER.md](NEROSECURITY-HANDOVER.md) |
| NeroCompanion | Low | Cosmetic purchases, price display, carry bots | [NEROCOMPANION-HANDOVER.md](NEROCOMPANION-HANDOVER.md) |
| Neroland Core | Medium | Ten additive asks | [CORE-ASKS.md](CORE-ASKS.md) |

**Nothing required:**

- **NeroCreatures** — its eleven drops are priced by NeroEconomy's `commodities/compat/nerocreatures.json`
  with tight daily caps, and the apex trophy is never a commodity. Bounties on its creatures are
  Reserve-funded on NeroEconomy's side. Optional: item tags for its drop families.
- **NeroPower** — its pellets, battery cells and spent fuel are priced by
  `commodities/compat/neropower.json`; the partly burnt `fuel_rod` is never a commodity, because its burn
  state is in item components. Optional: item tags.

## The budgeted-payout API in one page (added in 0.3.0)

```java
EconomyCaller caller = SiblingEconomy.claim("yourmod", Set.of("reason_a", "reason_b")); // once, common init
EconomyResult r = caller.pay(server, player, amount, "reason_a", requestKey);    // Reserve -> player, budgeted
EconomyResult r = caller.charge(server, player, amount, "reason_b", requestKey); // player -> Reserve
EconomyResult r = caller.transfer(server, payer, payee, amount, "reason_a", requestKey); // fee to the Reserve
if (!r.ok()) player.sendSystemMessage(Component.translatable(r.translationKey()));
```

- Claim from common init; a mod id can be claimed once, only by a loaded mod, and not after the first
  server has started. Reason codes are a closed set of `[a-z0-9_]{1,32}`, at most 32.
- `SiblingEconomy.API_VERSION` is 1; it rises by one with every add-only change.
- Payouts are refused **before anything moves** when the Reserve, the calling mod's daily budget
  (`siblingCallerDailyBudget`, 2,500 by default, overridable per mod in `siblingCallerBudgets`, a
  comma-separated `modid=amount` list such as `neroquests=3000,nerocolonies=1500`), the shared sibling budget
  (`siblingDailyBudget`, 5,000) or the player's daily total from all mods (`siblingPerPlayerDailyCap`,
  2,000) cannot cover them. One payout is at most `siblingMaxSinglePayout` (1,000).
- A request key (1 to 64 characters of `A-Z a-z 0-9 _ . : / # -`, never a UUID or a name; a key that
  reads as a UUID or holds 32 hex digits in a row is refused as `INVALID`) makes a retry safe: the same
  key from the same mod within 24 economy-hours returns `DUPLICATE` and moves nothing. Only an applied
  request's key is remembered, so a refused one may be retried with the same key. `null` means no replay
  protection.
- Calls are rate-limited per calling mod (burst 64, 16 a second): `RATE_LIMITED`.
- Each ledger row carries the origin `<modid>/<reason>`, which operators and the player's own data
  export see.
- `deposit`/`withdraw`/`balance` move credits between a player and the caller's own accounts
  (`new ForeignAccountId("<modid>", "<id>")`, for example a faction treasury); a caller can only touch
  accounts of its own kind.
- Core's untyped `CurrencyApi.deposit` still works, counted against the shared budget only.

## Class-free channels

These need no NeroEconomy class: only Neroland Core. Scopes name places, sectors or goods, never a player.

### Published by NeroEconomy on Core `ThresholdEvents` (added in 0.3.0)

| Channel | Scope | Value | Threshold | `rising` |
| --- | --- | --- | --- | --- |
| `neroeconomy:sector_index` | Sector word (`mining`, `space`, …) | Sector index in hundredths of a credit | The level of each configured band (`sectorBands`, ±5 % and ±10 % of the day's open) | Upper band: `true` above it, `false` back below. Lower band: `false` below it, `true` back above |
| `neroeconomy:market_shock` | Item id or `#tag` | Multiplier × 1000 | 1000 | `true` when a shock starts, `false` when it ends |
| `neroeconomy:reserve` | `world` | The Reserve's balance | `reserveAlertLow` (25,000) | `false` when it falls below, `true` when it recovers |
| `neroeconomy:house_stock` | Commodity key | Units a Merchant House holds | 0 | `false` when a House sells out, `true` when it has stock again |

The Reserve, House stock and sector channels are checked every 30 seconds (the market-sweep cadence);
the first check after a start only records where each value stands, so a restart announces nothing.
A shock's channel fires when it is applied, cleared or lapses.

### Accepted by NeroEconomy: market-shock requests (added in 0.3.0)

Fire a crossing on channel `<yourmod>:market_shock_request`, where `<yourmod>` is listed in NeroEconomy's
`shockSources` config (a comma-separated list, default `neroevents`):

| Field | Meaning |
| --- | --- |
| `scope` | Item id or tag with a leading `#` |
| `value` | Multiplier × 1000, clamped to the configured price band |
| `threshold` | Duration in economy-minutes, 1 … `shockMaxMinutes` (1,440) |
| `rising` | `true` applies, `false` ends the shock on that key early |

At most `maxActiveShocks` (64) are active at once; malformed requests are ignored.

### Player milestones as Core gates (added in 0.3.0)

Player-scope gates under `data/neroeconomy/neroland_gates/`, usable by any `gate_open` quest objective:

| Gate | Opens when the player |
| --- | --- |
| `neroeconomy:first_sale` | Completes a first sale |
| `neroeconomy:first_purchase` | Completes a first purchase |
| `neroeconomy:first_investment` | Buys a first share or index unit |
| `neroeconomy:first_bond` | Buys a first Savings Bond |
| `neroeconomy:first_offer_accepted` | Has a first offer accepted |
| `neroeconomy:trader_100` | Reaches 100 settled sales |

A milestone reached while the player is offline (a filled order, a trade from the companion app) opens
at their next join.

### Data that works with no code

- Commodity files: `data/<yourmod>/neroeconomy/commodities/*.json`, keyed on your own items or tags;
  `"required": false` skips ids that do not exist.
- Item tag `neroeconomy:never_commodity`: items whose identity is in components, never priced by id.
- Item tag `neroeconomy:untradeable`: items that must never be listed, vaulted or sent to the app.

## Privacy rules for every caller

- Pass only the player UUID you already hold. NeroEconomy gives no mod another player's balance,
  holdings or name, and its events carry one party only, never the counterparty.
- Never log a UUID or name with a payment, and never put one in a request key.
- What NeroEconomy stores for these calls is listed in its `PRIVACY.md` and is exported and erased with
  the player's other economy data.

Questions: info@neroland.co.za.
