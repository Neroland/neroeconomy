# NeroSecurity — handover from NeroEconomy

Priority: **Low** until NeroSecurity has content. It is a credit sink with the highest risk of locking
players out of their own money, so the rules below matter more than the calls.

APIs marked **added in 0.3.0** need NeroEconomy 0.3.0 or later.

## Rules for guarding economy blocks

- **An owner can always reach their own economy blocks.** Whatever NeroSecurity adds (locks, guards,
  restricted zones), the owner of a Player Shop, Auto-Trader or Market Board listing, and any player at a
  Bank Terminal, Trading Terminal or Exchange Terminal, must still be able to open it. Credits and goods
  are never held hostage by a lapsed security subscription.
- **Never break a block to enforce a restriction.** Breaking an Auto-Trader settles its rule (the rule's
  budget returns to the owner, listed goods go to the vault) and drops its slots like a chest; doing that
  automatically would scatter a player's goods.
- **Do not inspect economy state to decide access.** NeroEconomy exposes no balance or holdings of one
  player to another mod, and NeroSecurity should not need any.

## Paid security tiers (added in 0.3.0)

Claim a caller once from common init behind `isModLoaded("neroeconomy")`:
`EconomyCaller caller = SiblingEconomy.claim("nerosecurity", Set.of("security_tier"));`

| Reason code | Call |
| --- | --- |
| `security_tier` | `caller.charge(server, player, price, "security_tier", key)` — upgrade only when `result.ok()` |

- Charge when the player chooses a tier or renews it, never silently in the background.
- A failed renewal **downgrades** protection; it never removes access to the owner's own blocks.
- Charges go to NeroEconomy's Reserve (a sink). Never pay out (`pay`) for guarding: that would be a faucet.

## Feature detection

`isModLoaded("neroeconomy")` plus one bridge class compiled `compileOnly` against NeroEconomy's jar. With
NeroEconomy absent, tiers are free or configured another way.

## Privacy

Pass only the UUID you already hold. Never log who paid for which tier.
