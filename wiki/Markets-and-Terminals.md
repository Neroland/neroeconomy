# Markets and terminals

Every economy block opens the **Economy Hub** at its own tab. This page lists the blocks and how
searching and buying work at them. The rules of orders, direct trade and offers are on
[Trading](Trading.md).

## The blocks

| Block | Opens | Use |
| --- | --- | --- |
| Bank Terminal | Wallet | Your balance, paying players, collecting what you are owed, the Vault and Credit Chips. See [Bank Terminal](Bank-Terminal.md). |
| Trading Terminal | Market | Search and buy. |
| Market Board | My orders | Search, buy, and post and manage your own orders. |
| Exchange Terminal | Invest | Shares, the index fund and Savings Bonds. See [Exchange and investing](Exchange-and-Investing.md). |
| Merchant Stall | Market, filtered to its House | Buy from and sell to one Merchant House. See [Autonomous economy](Autonomous-Economy.md). |
| Auto-Trader | Its rule | Run a standing rule from a block. See [Auto-trading](Auto-Trading.md). |
| Player Shop | The shop's own screen | Sell one stack at a fixed price. See [Player Shops](Player-Shops.md). |

All of them are craftable. `/eco hub [tab]` opens the hub at a tab from anywhere, so you need not stand at a
block just to look.

## Searching

- A search covers every Player Shop listing and every order on the book on the server, with the Merchant
  Houses' orders listed as Houses. Distance, dimension and whether the seller's chunk is loaded do
  not matter.
- The search box matches item ids. Type part of an id, such as `diamond`, or leave it empty to list
  everything. Results are sorted by item id and shown eight to a page.
- The price on each row is the price you would pay now, after any price shock or modifier.
- A row never names the seller. You see "a player", "a shop" or a Merchant House.

## Buying

Select a row and press Buy to buy one item, or place a buy order for more. The server prices the
purchase again when it runs:

- **You are never charged more than the price on the row you confirmed.** If the price went up in the
  meantime the purchase is refused and nothing moves; if it went down you pay the lower price.
- If a shop now sells a different item from the one you were shown, the purchase is refused.
- You cannot buy your own listing, and you need the credits and room in your inventory or Vault. A
  purchase is refused **before any credit moves** when your Vault cannot take the goods.

The message tells you which of these stopped a purchase. The confirm step restates what you spend
and what you receive.

## Collecting

Credits you are owed (sale proceeds, payouts, refunds) wait as a credit claim until you press
Collect at any terminal or in the Wallet tab. Goods you bought while away, and refunds of goods, are
delivered to your [Vault](Vault.md). Claims have no time limit.

## Limits

| Limit | Key | Default |
| --- | --- | ---: |
| Open sell orders per player (shop listings are not counted) | `maxListingsPerPlayer` | 64 |
| Open buy orders per player | `maxBuyOffersPerPlayer` | 32 |
| Vault stacks | `vaultStacks` | 54 |
| Data one escrowed stack may carry, in bytes | `maxStackDataBytes` | 8192 |

When your Vault is full, a new purchase, fill or bid that would add to it is refused until you take
items out. Refunds, expiry and settlement are never refused because of a limit.

Items in the `neroeconomy:untradeable` item tag cannot be listed, auctioned, put in a shop, asked for
by a buy order or job, or used to fill one. The tag is empty unless a server adds items to it with a
datapack.

## Rate limits and replays

Each player has two request budgets, shared by the screens and the companion app:

| Kind of request | Burst | Refill |
| --- | ---: | --- |
| Queries: searching, changing page or tab, nudging a shop price | 10 | 5 per second |
| Value-moving: buying, posting, filling, collecting, claiming, fulfilling, bidding, paying | 6 | 2 per second |

From a screen, a request over the budget is ignored without a message. The companion app instead
gets its last snapshot back, or a "too many requests" answer to an action.

Every value-moving request carries a request id. The server remembers each id for 10 minutes, and
across a restart, so a repeated or replayed request acts once.

You must stay within 8 blocks of the block whose screen you have open, in the same dimension. A
spectator can open the screens but cannot act through them.

## How a price is worked out

An order has a base unit price. The price charged is the base price multiplied by any active market
shock on the item or its tags and by any registered price modifier. The combined multiplier is kept
between `minPriceMultiplier` and `maxPriceMultiplier`, and the result is rounded to a whole credit,
never below 1. Merchant House and exchange quotes are not changed by modifiers.
