# Player Shops

A Player Shop sells one stack of one item at a fixed price. Its stock is held in the server's
central market escrow, not in the block, so the listing keeps selling when the owner logs out or
the shop's chunk unloads, and it appears in every Trading Terminal search on the server.

## Owning a shop

- The player who places a shop owns it. Placing a shop always starts a fresh record: if an earlier
  shop stood at the same position, its record is settled first and the new shop belongs to the
  placer.
- Only the owner can stock the shop, take stock back and change the price. The price moves in steps
  of 1 or 10 credits and cannot go below 1.
- A stack that may not be traded (an item in the `neroeconomy:untradeable` tag, or a stack carrying
  more data than `maxStackDataBytes`) is not accepted as stock. If one reaches the shop anyway, it
  goes to the owner's [Vault](Vault.md).
- Hoppers and other automation cannot insert into or extract from a shop.
- Shop listings do not count towards `maxListingsPerPlayer`. They are listed by `/eco market mine`,
  but they are not cancelled with `/eco market cancel`: take the stock back at the shop.

## Buying

The buyer's view shows the stock and the price that will be charged, with any active price shock or
modifier already applied. "Buy one" buys one item per click.

The server checks the listing, the stock, the buyer, the price, the balance and the room in the
buyer's inventory again at the moment of purchase:

- you are never charged more than the price you saw; if the price went up, the purchase is refused,
  and if it went down, you pay the lower price;
- if the owner changed the item on offer after you looked, the purchase is refused;
- you cannot buy from your own shop.

Payment goes straight to the owner's balance. A repeated or replayed click cannot settle twice. When
a shop sells out, the owner gets a "shop sold out" alert in the companion app.

## Breaking and removing

- A shop can be broken by its owner, by an operator with permission level 2, or by anyone if the
  shop has no owner on record.
- However a shop is removed — mined, blown up or replaced by a command — its unsold stock goes to
  the owner's Vault and its ownership record is removed. Nothing is dropped and nothing is
  lost.
- Commands that replace blocks without block updates (`/setblock`, `/fill`, `/clone`, structure
  placement) are caught by a sweep that runs every 30 seconds for loaded chunks.

## Idle expiry

A shop listing that has had no sale and no visit from its owner for `shopListingMaxIdleDays` (90 by
default) expires: the stock goes to the owner's Vault and the shop is left empty. Opening the
shop as its owner, restocking, changing the price or making a sale restarts the count. Set the key
to `0` to turn expiry off.

## Erasure

When a shop owner's data is erased, the ownership record, the listing and its stock are removed (the stock is destroyed). The
shop block stays in the world without an owner, and anyone can break it. See
[Privacy](Privacy-and-Erasure.md).
