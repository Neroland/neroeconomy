# NeroLogistics — handover from NeroEconomy

Priority: **High** for the container contract (already met on NeroEconomy's side), **Medium** for fees
and job fulfilment, which are optional.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## What needs nothing on your side

- **Feeding the Auto-Trader.** NeroEconomy's Auto-Trader block entity is a vanilla `WorldlyContainer`,
  with eighteen slots on every face: slots 0–8 are inputs (any tradeable stack may be inserted, nothing
  extracted), slots 9–17 are outputs (extract only). Ducts and drones that move items through vanilla
  `Container` reach it on every loader. It never loads a chunk; its rule pauses while it is unloaded.
- **Commodities.** NeroEconomy ships `commodities/compat/nerologistics.json` (ducts, the network module,
  drones, the hyperspeed card, the wireless terminal and the network controller). Item and fluid cells are
  in NeroEconomy's `neroeconomy:never_commodity` tag, because a cell's contents are in its components.

## Optional: a per-launch freight fee (added in 0.3.0)

A rocket cargo launch already records a `ShipDenial` when it cannot go. A fee fits the same place:

1. In a compat class behind `isModLoaded("neroeconomy")`, claim once from common init:
   `EconomyCaller caller = SiblingEconomy.claim("nerologistics", Set.of("freight_fee", "delivery_payout"));`
2. At the launch point in `ShipmentManager`, after every other check passes and only when the origin has
   a dispatcher (`ShipOrigin.dispatcher()` is not null):
   `caller.charge(server, dispatcher, fee, "freight_fee", requestKey)` with `requestKey` = the flight id.
3. Launch only when `result.ok()`. Otherwise record a new denial, for example `CANNOT_PAY` (append it;
   keep the Nerospace-mirrored names), and show the result's lang key in the port status line.

The fee goes to NeroEconomy's Reserve (a sink). Unvested starting credits may pay it. A launch with no
dispatcher (an automated port) is never charged.

| Reason code | When |
| --- | --- |
| `freight_fee` | Charged per rocket cargo launch |
| `delivery_payout` | Reserved for paying a dispatcher for a delivery (see below) |

## Optional: fulfilling posted delivery jobs

`DeliveryJobApi` (exists since 0.2) posts delivery jobs, player-funded or Reserve-funded. NeroEconomy
0.3.0 does not yet have a path for a machine, rather than a player, to fulfil one; that path is planned
behind the same API. Until it exists, do not pay dispatchers from your side with `pay`, or deliveries
become a faucet; leave `delivery_payout` unused.

## Feature detection

Your `compat/NerospaceCompat` pattern: `Services.PLATFORM.isModLoaded("neroeconomy")`, then one bridge
class compiled `compileOnly` against NeroEconomy's jar. Without NeroEconomy, or with an older one, launches
stay free.

## Privacy

The dispatcher's UUID is passed to NeroEconomy only for the charge; never log it with the fee and never
show it on a port to another player.
