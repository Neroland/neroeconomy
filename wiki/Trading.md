# Trading

NeroEconomy has four ways to trade: the **order book** (sell and buy orders that match each other),
**Player Shops** (one stack at a fixed price, see [Player Shops](Player-Shops.md)), **direct trade**
(a two-sided window with another player) and **offers** (a gift or swap sent to a named player who
answers later). All of them settle on the server, and goods you buy while you are away wait in
your [Vault](Vault.md).

## The order book

The Trading Terminal and the Market Board both open the **Market** tab of the Economy Hub. You see
the best bid and ask for an item, the order book as price levels, and every Player Shop listing,
side by side. Search by item id (type part of one, such as `diamond`). Distance, dimension and
whether a seller's chunk is loaded do not matter.

### Orders

- A **sell order** puts a quantity of one item (or one modified stack) on the book at a price per
  unit. It comes from your main hand when you are in game, or from your vault (in game or from the
  companion app).
- A **buy order** names an item id, a quantity and the most you will pay per unit. The whole amount
  (quantity times price) leaves your balance and is held in escrow until the order fills, expires
  or is cancelled.
- Order sizes are 1 to 2304. You pay a flat **listing fee** (`listingFee`, 1 credit) for each order
  you post; it is not refunded.

### Matching

An order that crosses the other side fills at once:

- **Best price first, then oldest first.** Fills can be partial.
- A fill happens at the **resting** order's price. A buyer who bid more than the fill price gets
  the difference back from escrow in the same operation.
- Only **unmodified** items match buy orders. A renamed, enchanted, damaged or filled stack can be
  listed and bought directly, but a buy order names a plain item.
- **You never match yourself.** Your own orders on the other side are passed over.
- Goods you bought while in game go to your inventory when they fit, and to your vault otherwise.
  Goods bought by an order that was resting while you were away always go to your vault.
- Proceeds go to the seller's **credit claim**, less the market fee, and are collected at any
  terminal or from the Wallet tab.

Orders last `defaultListingDurationHours` (48 by default). When an order expires or you cancel it,
unsold goods return to your vault and unspent escrow returns to your claim. Refunds and expiry
always book, even when your vault is full.

### Prices and fees

| Charge | Key | Default | Applies to |
| --- | --- | ---: | --- |
| Market fee | `marketFeePercent` | 2 % | The seller's proceeds of every order-book sale, shop sale and filled buy order |
| Listing fee | `listingFee` | 1 credit | Each order posted; not refunded |
| Auction fee | `auctionFeePercent` | 5 % | The winning bid, from the seller's proceeds |
| Trade tax | `tradeTaxPercent` | 0 % | The credits side of a direct trade or an accepted offer |

A percentage fee is `ceil(total × percent / 100)`, at least 1 credit when the percentage is above
zero. It is worked out on the order total, so splitting an order into singles never pays less. All
fees go to the Reserve, the pool every system payout comes from; see
[Autonomous economy](Autonomous-Economy.md).

A price modifier registered by another mod changes the **buyer's** price only. The seller receives
the base price less the fee. A discount is paid by the Reserve but never exceeds the fee collected
on that same sale, so two accounts trading back and forth can never draw on the Reserve; a surcharge
goes to the Reserve. Modifiers never apply to Merchant House or exchange quotes. Orders placed by
auto-trade rules never get a discount.

### Limits

| Limit | Key | Default |
| --- | --- | ---: |
| Open sell orders per player (shop listings are not counted) | `maxListingsPerPlayer` | 64 |
| Open buy orders per player | `maxBuyOffersPerPlayer` | 32 |
| Vault stacks | `vaultStacks` | 54 |
| Data one stack may carry, in bytes | `maxStackDataBytes` | 8192 |

A purchase you start is refused **before any credit moves** when your vault cannot take the goods.

Items in the `neroeconomy:untradeable` item tag cannot be listed, offered or traded; see
[Configuration](Configuration-and-Administration.md).

### Order commands

| Command | What it does |
| --- | --- |
| `/eco order sell <unitPrice> [quantity]` | Sells the stack in your main hand (up to 64). |
| `/eco order sell vault <item> <unitPrice> <quantity>` | Sells from your vault. |
| `/eco order buy <item> <quantity> <unitPrice>` | Places a buy order and escrows the credits. |
| `/eco order cancel <id>` | Cancels one of your orders. |
| `/eco order mine` | Lists your own orders with their ids. |

## Direct trade

`/trade <player>`, or sneak-use on a player with an empty hand, sends a request. They accept or
decline (`/trade accept`, `/trade decline`, or the chat buttons).

- Both players must be within `tradeRangeBlocks` (16) in the same dimension, **or both at an
  economy terminal**, in which case distance does not matter.
- A request lapses after 30 seconds. You can send one request every 5 seconds, and a refused
  request uses the cooldown up too. At most 8 requests wait for one player.
- The window holds up to **nine stacks and a credit amount per side**. What you put in leaves your
  inventory and is recorded in the store at once. The store is saved with the game's own save, together
  with player data, so after a crash every item is in exactly one place.
- **Any change by either side clears both confirmations.** After both confirm, the contents freeze
  for three seconds and then swap in one operation. Tooltips show the true item id whenever a stack
  is renamed.
- Closing the window, walking out of range, dying or disconnecting cancels the trade and returns
  everything. Anything a crash left on the table is returned to its owner's vault at the next start.
- `/trade mute <true|false>` (also `/eco offer mute <true|false>`) turns off trade requests and
  offers from other players.
- Creative and spectator players cannot put items from their hands into a trade; see
  [Fair play](Fair-Play.md).

## Offers

An offer sends items and credits to a named player and may ask for an item and quantity, credits, or
both in return. Everything you offer is **escrowed when you send**. The recipient accepts or
declines in game or in the app, and the swap is one operation. An offer you sent can be cancelled
by you at any time, and an unanswered one expires after `offerDurationHours` (48) and refunds in
full.

- An offer holds up to nine item lines.
- You can have `maxOffersPerPlayer` (16) open offers sent, at most four of them waiting with the
  same recipient, and no one can have more than 64 waiting for them. Someone who has muted offers,
  or whose inbox is full, gives the sender the same answer, which says nothing about them.
- Credits promised to another player cannot come from a starting balance that has not vested.
- A decline, a cancel and an expiry all return everything to the sender: items to the vault,
  credits to the balance.
- Accepting is refused before anything moves when either vault cannot take the goods or either side
  lacks what it must give. `tradeTaxPercent` applies to the credits.

| Command | What it does |
| --- | --- |
| `/eco offer send <player> hand <count> [credits <n>] [ask ...]` | Offers the stack in your hand (and optionally credits). |
| `/eco offer send <player> vault <item> <count> [credits <n>] [ask ...]` | Offers items from your vault. |
| `/eco offer send <player> credits <n> [ask ...]` | Offers credits only. |
| | `ask` is `ask item <item> <count> [credits <n>]` or `ask credits <n>`. |
| `/eco offer list` | Lists offers you sent and received. |
| `/eco offer accept <id>`, `/eco offer decline <id>`, `/eco offer cancel <id>` | Answers or takes back an offer. |
| `/eco offer mute <true\|false>` | Turns offers and trade requests off or on. |

## Collecting and cancelling

Credits you are owed (sale proceeds, payouts, refunds) wait as a **credit claim** until you press
Collect at any terminal or in the Wallet tab. Goods are delivered to your vault. Claims have no time
limit. `/eco market mine` lists your orders, jobs and auctions, and `/eco market cancel <id>` cancels
one.

## Rate limits and replays

Each player has two request budgets, shared by the screens, the commands and the companion app:
queries (burst 10, refilling 5 a second) and value-moving requests (burst 6, refilling 2 a second).
Every value-moving request carries a request id, remembered for 10 minutes (the latest 128 per
player) and across restarts, so a repeated or replayed request acts once. You must stay within 8 blocks of a block whose screen you
have open.

## How a price is worked out

An order has a base unit price. The price a buyer is charged is the base price multiplied by any
active market shock on the item or its tags and by any registered price modifier. The combined
multiplier is kept between `minPriceMultiplier` and `maxPriceMultiplier`, and the result is rounded
to a whole credit, never below 1. A buyer is never charged more than the price they confirmed.
