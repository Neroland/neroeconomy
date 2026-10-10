# Vault

Every player has a **Vault**: a private store of goods, kept on the server, that holds what the
economy owes you in items. It replaces the old "item claims". You open it at a Bank Terminal (the
Vault tab of the Economy Hub), or read it from the companion app.

## What goes in

- Goods you **bought while away**, including fills of your standing buy orders and buy rules.
- **Refunds and returns**: goods from a cancelled or expired order, a declined, cancelled or
  expired offer, and the unsold stock of a Player Shop that was removed or went idle.
- Goods an **auto-trade rule** buys for you when it works from your vault.
- Items you **deposit** yourself.
- Goods from a trade a crash cut short.

Goods you buy while in game go straight to your inventory when they fit, and to the vault
otherwise.

## Deposits and withdrawals

Deposits and withdrawals happen with you present, at the Bank Terminal's Vault screen. It is a
chest-style grid over your vault beside your inventory; shift-click, dragging, number keys and
double-click all work, and every click is settled against the server before the next. The companion
app can read the vault and sell or offer from it, but moving items between the vault and your
inventory is the one thing it cannot do while you are offline.

- Only goods that may be traded can be deposited: nothing in the `neroeconomy:untradeable` tag, and
  no stack carrying more data than `maxStackDataBytes`.
- Creative and spectator players cannot deposit from their hands or inventory; see
  [Fair play](Fair-Play.md).
- Taking items out always works.

## Size

A vault holds up to `vaultStacks` stacks (54 by default, in whole rows of nine; the setting can be
set from 9 to 54). `maxClaimStacksPerPlayer` (256) also applies, and the smaller of the two wins.

- A purchase **you start** is refused before any credit moves when your vault cannot take the
  goods, and the message says so.
- **Refunds, expiries and fills that were already owed to you always book**, even past the limit,
  because refusing would destroy goods. While the vault is over its limit, deposits and further
  purchases to it are blocked until you take enough out. You get the alert "Your vault is full"
  (in game and in the app), and the operator is warned if one account runs far past the limit.
- An account with an empty vault has no entry in the world data at all.

## Modified items

A renamed, enchanted or filled item (such as a shulker box with contents) is kept exactly as it is,
in its own line. It only stacks with an identical stack, and it is **not** matched by buy orders,
which name a plain item. Everything the vault shows about a modified stack in the app is its base
item name; a custom name is never sent.

## Privacy

Your vault is stored under your player UUID, is included in `/eco export` (as item id and count),
and is destroyed when you are erased. See [Privacy](Privacy-and-Erasure.md).
