# NeroCompanion — handover from NeroEconomy

Priority: **Low** until NeroCompanion has content.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later; check `SiblingEconomy.API_VERSION >= 1`.

## Cosmetic skins bought with credits (added in 0.3.0)

Claim a caller once from common init behind `isModLoaded("neroeconomy")`:
`EconomyCaller caller = SiblingEconomy.claim("nerocompanion", Set.of("cosmetic_purchase"));`

| Reason code | Call |
| --- | --- |
| `cosmetic_purchase` | `caller.charge(server, player, price, "cosmetic_purchase", purchaseId)` |

- Unlock the skin only when `result.ok()`; otherwise show `Component.translatable(result.translationKey())`.
- The confirm step restates the price and what the player gets.
- Use your own purchase id as the request key, so a double click can never charge twice.
- Credits go to NeroEconomy's Reserve.

## Showing prices (added in 0.3.0)

`CommodityApi.ask(server, itemId)` and `CommodityApi.bid(server, itemId)` return what a Merchant House
sells and pays for one unmodified unit now (empty when it does not). `CommodityApi.remainingToday(server,
player, itemId)` (exists) tells a player how much more of that item they may trade with the Houses today,
about that player only. Use them for a companion that says "this sells for about 12 credits".

## Carry bots that haul to market

No API needed. The Auto-Trader is a vanilla `WorldlyContainer`: slots 0–8 accept tradeable stacks from
any face, slots 9–17 give up what a buy rule bought. A bot that inserts into a vanilla container already
works. Respect two NeroEconomy rules:

- Goods from a creative-mode or spectator player are never a source of value; a bot owned by such a
  player should not feed a trader.
- The Auto-Trader never loads chunks; a bot should not either, just to keep it running.

## Privacy

Pass only the UUID you already hold; never log purchases.
