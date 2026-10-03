# Markets and terminals

The Trading Terminal and the Market Board share one screen. The Trading Terminal searches and buys;
the Market Board does the same and also posts offers.

## Searching

- A search covers every Player Shop listing and every Market Board listing on the server. Distance,
  dimension and whether the seller's chunk is loaded do not matter.
- The search box matches item ids. Type part of an id, such as `diamond`, or leave it empty to list
  everything. Results are sorted by item id and shown eight to a page.
- The price on each row is the price you would pay now, after any price shock or modifier.

## Buying

Select a row and press Buy to buy one item. The server prices the purchase again when it runs:

- **You are never charged more than the price on the row you confirmed.** If the price went up in
  the meantime the purchase is refused and nothing moves; if it went down you pay the lower price.
- If a shop now sells a different item from the one you were shown, the purchase is refused.
- You cannot buy your own listing, and you need the credits, room in your inventory and room in
  your claim list.

The message tells you which of these stopped a purchase.

## Market Board offers

A Market Board publishes two kinds of offer, both backed by escrow:

- **Sell offer.** Hold the goods in your main hand, enter a quantity and a unit price, and press
  "Post held". The goods leave your inventory and are held in escrow. When someone buys, the
  payment becomes a credit claim for you.
- **Buy offer.** Type the full item id (for example `minecraft:diamond`) in the search box, enter a
  quantity and a unit price, and press "Want". The full amount (quantity times unit price) leaves
  your balance and is held in escrow. A seller selects the offer's row and presses Buy to fill one
  item from their main hand. You receive the goods as an item claim and the seller receives a
  credit claim.

Buy offers are shown at Market Boards and in the companion app, not at Trading Terminals.

A buy offer is filled only with **unmodified** items: a stack that is renamed, enchanted, filled
(such as a container with contents) or damaged is refused, because the offer names a plain item.

Offers last `defaultListingDurationHours` (48 by default). When an offer expires, the remaining
goods or credits go back to its owner's claim.

## Cancelling

`/eco market mine` lists your own open rows with their ids, and `/eco market cancel <id>` cancels a
Market Board listing or a buy offer. The stock returns to your item claim, or the unspent credits
to your credit claim. An id that is not yours gets the same answer as an id that does not exist.

## Collecting claims

Purchases that could not go straight into your inventory, proceeds, payouts, refunds and returned
stock wait as claims. Press Collect at a Trading Terminal, a Market Board or a Bank Terminal to
receive them. Claims have no time limit. Items that do not fit stay in the claim.

## Limits

| Limit | Key | Default |
| --- | --- | ---: |
| Open Market Board sell listings per player (shop listings are not counted) | `maxListingsPerPlayer` | 64 |
| Open buy offers per player | `maxBuyOffersPerPlayer` | 32 |
| Uncollected item-claim stacks per player | `maxClaimStacksPerPlayer` | 256 |
| Data one escrowed stack may carry, in bytes | `maxStackDataBytes` | 8192 |

When your claim list is full, a new purchase, fill, bid or cancel that would add to it is refused
until you collect. Refunds, expiry and settlement are never refused because of the limit.

Items in the `neroeconomy:untradeable` item tag cannot be listed, auctioned, put in a shop, asked
for by a buy offer or job, or used to fill one. The tag is empty unless a server adds items to it
with a datapack.

## Rate limits and replays

Each player has two request budgets, shared by the screens and the companion app:

| Kind of request | Burst | Refill |
| --- | ---: | --- |
| Queries: searching, changing page or tab, nudging a shop price | 10 | 5 per second |
| Value-moving: buying, posting, filling, collecting, claiming, fulfilling, bidding, paying | 6 | 2 per second |

From a screen, a request over the budget is ignored without a message. The companion app instead
gets its last snapshot back, or a "too many requests" answer to a trade.

Every value-moving request carries a request id. The server remembers each id for 10 minutes, and
across a restart, so a repeated or replayed request acts once.

You must stay within 8 blocks of the block whose screen you have open, in the same dimension. A
spectator can open the screens but cannot act through them.

## How a price is worked out

A listing has a base unit price. The price charged is the base price multiplied by any active
market shock on the item or its tags and by any registered price modifier. The combined multiplier
is kept between `minPriceMultiplier` and `maxPriceMultiplier`, and the result is rounded to a whole
credit, never below 1.
