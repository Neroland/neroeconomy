# APIs and integrations

## Java API

The stable package `za.co.neroland.neroeconomy.api` exposes abstract account ids, market views,
quotes, price contexts, modifiers, a deterministic registry, the pricing engine, market shocks and
the delivery-job seam. Names in this package are only ever added, never renamed or removed.

### Pricing

- `PricingEngine.quote(listing, buyer)` returns a `PriceQuote`: the base unit price multiplied by
  the active shocks and the registered modifiers, with the combined multiplier clamped to
  `minPriceMultiplier`..`maxPriceMultiplier`, rounded to a whole credit and never below 1.
- `PricingEngine.executionPriceWithinQuote(quotedUnitPrice, executionUnitPrice)` is the rule every
  purchase settles under: it is true only when the execution price is at or below the quoted price.
  A buyer is therefore never charged more than the price they confirmed. Use this method for
  anything that charges a buyer.
- `PricingEngine.executionPriceAccepted(quotedUnitPrice, executionUnitPrice)` is the older,
  symmetric check: it accepts a price up to `executionPriceTolerance` above or below the quote. It
  is kept for existing callers and should not be used to charge anyone.
- Every consumer must treat a quote as indicative; settlement prices again.

### Modifiers and shocks

- `PriceModifierRegistry.register(modId, modifier)` registers one modifier per mod id (lower-case
  letters, digits, `_`, `.` and `-`); registering again replaces it. Modifiers are applied in mod-id
  order, so the same inputs produce the same quote. A modifier that returns a non-finite or
  non-positive value, or throws, is ignored for that quote.
- `MarketShocks.applyShock(target, multiplier, duration)` accepts an item id, or an item tag id
  written with a leading `#`. The multiplier is clamped to the server's limits and the shock
  expires on its own. Shocks are held in memory and are dropped when the server stops.

### Delivery jobs

- `DeliveryJobApi.post(issuer, itemOrTag, quantity, destination, payout, expiresAt)` posts a
  delivery job for a player. It returns `null`, and posts nothing, unless all of these hold:
  - the target is a registered item id, or a non-empty item tag written with a leading `#`, at most
    64 characters long, and not in the `neroeconomy:untradeable` tag;
  - `quantity` and `payout` are at least 1;
  - `expiresAt` is in the future on the economy clock
    (`NeroEconomyCommon.CURRENCY_PROVIDER.now()`), not the system clock;
  - the issuer is below `maxJobsPerPlayer` and can afford the payout, which is escrowed.
- The destination is a string in the form `dimension|x|y|z`, for example
  `minecraft:overworld|120|64|-40`. It is not validated when the job is posted; a destination in
  any other form can never be fulfilled, so the job would only expire and refund.
- `DeliveryJobApi.postSystem(server, itemOrTag, quantity, destination, payout, expiresAt)` posts a
  server-funded job with no player issuer. The payout is minted when the job is fulfilled and the
  delivered goods are consumed. It returns `null` when `systemJobsEnabled` is off or when the job
  would take the day's total past `systemJobDailyPayoutCap`.
- `DeliveryJobApi.logisticsPresent()` reports whether NeroLogistics is installed.

## Companion-app module

The Core Link module is `neroeconomy`, schema version 1. It has three snapshot sections
(`balance`, `transactions`, `market`), one action (`trade`) and one event (`balance`). Everything
is scoped to the requesting player and runs on the server thread. The module keeps no copy of any
economy state.

Schema 1 originally shipped keys that the companion app does not read. Each object now carries both
the original keys and the keys the app reads. All times are wall-clock milliseconds since the Unix
epoch.

### `balance` section

No parameters.

| Key | Type | Meaning |
| --- | --- | --- |
| `currency` | string | The currency id. |
| `balance` | number | The player's balance. |
| `recentTrend` | array | Up to 10 of the player's own recent rows, newest first: `at`, `amount` (unsigned), `reason` (reason code). |
| `amount` | number | The balance again, under the key the app reads. |
| `symbol` | string | The display symbol, `cr`. |
| `history` | array of numbers | Up to 20 balance samples, oldest first, ending at the current balance. |
| `asOf` | number | When the snapshot was taken. |

### `transactions` section

Parameters: `cursor` (offset, default 0) and `limit` (1 to 50, default 25). Only the player's own
retained rows are returned, newest first, up to `historyLimitPerPlayer`.

| Key | Type | Meaning |
| --- | --- | --- |
| `items` | array | Original shape: `reason`, `amount` (unsigned), `currency`, `timestamp`. |
| `rows` | array | The shape the app reads; see below. |
| `nextCursor` | string | The `cursor` value for the next page. **Left out on the last page.** |
| `asOf` | number | When the snapshot was taken. |

Each entry of `rows`:

| Key | Type | Meaning |
| --- | --- | --- |
| `id` | string | An opaque id that stays the same as newer rows arrive. |
| `at` | number | When the transaction happened. |
| `kind` | string | One of `purchase`, `sale`, `transfer`, `escrow`, `refund`, `payout`, `bid`, `deposit`, `withdrawal`, `admin`. |
| `amount` | number | Signed: positive when the player received credits, negative when they paid. |
| `item` | string | A fixed description of the kind, such as "Market purchase" or "Payment received". The transaction log stores no item. |
| `qty` | number | Always 0. The transaction log stores no quantity. |
| `reason` | string | The reason code, such as `SHOP_PURCHASE`. |

`nextCursor` used to be a number, with `-1` on the last page. It is now a string and is omitted at
the end. No account reference or counterparty is ever sent.

### `market` section

Parameters: `q` (matched against item ids; at most 64 characters, and spaces are read as
underscores) and `page` (from 0). A page holds up to 25 sell listings and up to 25 buy offers.

| Key | Type | Meaning |
| --- | --- | --- |
| `items` | array | Original shape, sell listings only: `listingId`, `item`, `stock`, `unitPrice`. |
| `page` | number | The page returned. |
| `listings` | array | The shape the app reads; see below. |
| `pageSize` | number | 25. |
| `total` | number | Matching sell listings plus matching buy offers. |
| `hasMore` | boolean | Whether another page exists. |
| `asOf` | number | When the snapshot was taken. |

Each entry of `listings`:

| Key | Type | Meaning |
| --- | --- | --- |
| `listingId` | string | The id of the sell listing or buy offer. |
| `displayName` | string | The item's base name, at most 64 characters, with " (modified)" added when the stack is not the plain item. Never a custom (renamed) name. |
| `item` | string | The item id. |
| `qtyAvailable` | number | Stock on a sell listing; quantity still wanted on a buy offer. |
| `unitPrice` | number | For a sell listing, the price quoted to this player; for a buy offer, what it pays. |
| `side` | string | The operation the viewer performs: `buy` on a seller's listing, `sell` on a buy offer. |
| `pristine` | boolean | `false` for a renamed, enchanted, filled or damaged stack. |

The viewer's own listings and buy offers are left out of `listings`, because a trade against them
is always refused. Rows never say who posted them or where. The admin gallery's demo rows are never
included.

### `trade` action

One object. It can be disabled with `remoteTradeEnabled=false`.

| Parameter | Required | Meaning |
| --- | --- | --- |
| `requestId` | Yes | A UUID chosen by the client. A missing or malformed id is refused. An id already handled in the last 10 minutes is refused as a duplicate, so a retry must reuse the id of the request it retries. |
| `op` | Yes | `buy` or `sell`. |
| `listingId` | For `buy`, and for `sell` without `unitPrice` | The row to trade against. |
| `qty` | No | A whole number from 1 to 64; 1 if left out. |
| `quotedUnitPrice` | No (`buy`) | The most the buyer agreed to pay per item. Without it, the ceiling is the server's own quote at execution. |
| `unitPrice` | No (`sell`) | With it, the main-hand stack is posted as a new Market Board listing at that price. Without it, the buy offer named by `listingId` is filled from the main hand. |

- `buy` works while the player is logged out: the goods are booked to their item claim and wait at
  a terminal. When the player is online the goods go to their inventory.
- `sell` always takes the stack from the player's main hand, so the player must be in the game. A
  buy offer is filled only with unmodified items.

A successful result is the `balance` object as it stands after the trade, plus:

| Key | Type | Meaning |
| --- | --- | --- |
| `op` | string | `buy` or `sell`. |
| `listingId` | string | The row traded against, or the new listing when one was posted. |
| `qty` | number | The quantity traded or listed. |
| `price` | number | The unit price actually traded at, or the asking price of a new listing. |
| `total` | number | The credits that moved, or the asking total of a new listing. |
| `filled` | boolean | `true` when goods and credits changed hands; `false` when a listing was only posted. |
| `delivery` | string | `inventory`, `claim`, `credit_claim` or `listed`. |

When a buy offer is filled, the proceeds are booked to the seller's credit claim, so the balance in
the result has not changed yet.

A refused trade returns an error whose message is a plain sentence that can be shown as it is. The
error code is `VALIDATION` for a refused trade, `ACTION_DISABLED` when remote trade is off,
`PLAYER_OFFLINE_REQUIRED` for a `sell` while logged out, and `INTERNAL` for an unexpected failure.

### `balance` event

When a player's balance changes, the module publishes a player-scoped event on topic `balance`
(`neroeconomy.balance` on the wire) whose payload is the `balance` object. At most one event is
published per player per second, and only for a player whose app has asked this module for a
snapshot or an action in the last 120 seconds.

### Rate limits

Snapshots and actions share the per-player budgets the in-game screens use: snapshots take from the
query budget (burst of 10, 5 per second) and `trade` from the value budget (burst of 6, 2 per
second). An over-budget snapshot returns the last snapshot served for the same query with
`"throttled": true` added. An over-budget `trade` is refused with a "too many requests" message.

### Alerts

Alerts are a fixed catalogue. Each has a stable id and a fixed sentence; an alert never contains a
name, amount, item or place. A new alert replaces the player's previous alert with the same id, and
at most one alert of a kind is raised per player in 30 seconds.

| Id | Text |
| --- | --- |
| `neroeconomy:contract_fulfilled` | An economy job was fulfilled. |
| `neroeconomy:outbid` | You were outbid. |
| `neroeconomy:auction_won` | You won an auction. |
| `neroeconomy:shop_sold_out` | A player shop sold out. |

## Soft integrations

Soft seams are string-keyed: NeroLogistics delivery posting, NeroEvents shocks, future NeroFactions
modifiers, NeroCreatures bounty entity ids, and the Nerospace meteor entry for the Credit Chip.
Core remains the only hard dependency.
