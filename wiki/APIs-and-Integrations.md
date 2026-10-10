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
  server-funded job with no player issuer. The payout is moved from the Reserve into escrow when the
  job is posted, and the delivered goods are consumed. It returns `null` when `systemJobsEnabled` is
  off, when the job would take the day's total past `systemJobDailyPayoutCap`, or when the Reserve
  cannot cover the payout. An expired job returns its payout to the Reserve.
- `DeliveryJobApi.logisticsPresent()` reports whether NeroLogistics is installed.

### Cross-mod API (version 1)

Since 0.3 a sibling mod can pay, charge and move credits through one handle, with a reason code, a daily
budget and a request key. Everything below is in `za.co.neroland.neroeconomy.api` and is only ever added to.
`SiblingEconomy.API_VERSION` is `1` for this set and goes up by one for every later addition, so a mod can
feature-detect: read it, and use a newer call only when it is high enough. Compile against this mod's jar
(`compileOnly`) and keep every call behind one bridge class that is loaded only when
`isModLoaded("neroeconomy")` is true. Class-free routes through Core need no compile dependency at all and
are listed under [Channels and gates](#channels-and-gates-through-core).

#### Claiming a handle

```java
// Common init, once per mod. The reason codes are a closed set you choose.
static final EconomyCaller CALLER = SiblingEconomy.claim("mymod", Set.of("quest_reward", "sale"));

// Later, on the server thread.
if (SiblingEconomy.API_VERSION >= 1 && SiblingEconomy.available(server)) {
    EconomyResult result = CALLER.pay(server, playerId, 50, "quest_reward", "quest:42:stage:3");
    if (!result.ok()) player.sendSystemMessage(Component.translatable(result.translationKey()));
}
```

- A mod id is lower-case letters, digits, `_`, `.` and `-` (1 to 64), and must be a loaded mod. `neroeconomy`
  and `unknown` cannot be claimed. A reason is lower-case letters, digits and `_` (1 to 32); at most 32 a
  caller. `claim` throws `IllegalArgumentException` for a bad id or reason and `IllegalStateException` when
  the id is already claimed, is not loaded, or a server has already started. Claims close when the first
  server binds, so claim during mod init.
- `SiblingEconomy.available(server)` is true only for the running server while the economy is not frozen.

#### Calls

| Call | What it does |
| --- | --- |
| `pay(server, player, amount, reason, requestKey)` | Reserve to player. 1 to `siblingMaxSinglePayout` credits. Counted against the caller's daily budget, the shared budget and the per-player cap. No partial payment: a batch caller loops and stops at the first refusal. |
| `charge(server, player, amount, reason, requestKey)` | Player to Reserve, a sink. A starting balance that has not vested may be spent here, like any system fee. |
| `transfer(server, payer, payee, amount, reason, requestKey)` | Payer to payee in one operation. The payee gets `amount - feeFor(amount)`; the fee goes to the Reserve as a `SIBLING_FEE` row. Never Reserve-funded; unvested starting credits cannot be used (`FUNDS_LOCKED`). Payer and payee must differ. |
| `deposit(server, player, account, amount, reason, requestKey)` | Player to one of the caller's own accounts (see below). |
| `withdraw(server, account, player, amount, reason, requestKey)` | One of the caller's own accounts to a player. Never Reserve-funded. |
| `balance(server, account)` | What one of the caller's own accounts holds; 0 for an account of another kind. |
| `canPay(server, player, amount)` | The decision `pay` would make on the Reserve and budget checks now. Nothing moves or is counted. |
| `remainingToday(server)` | The least of the caller's remaining daily limit, the shared remaining limit and the Reserve balance. |
| `feeFor(amount)` | The `transfer` fee: `siblingTransferFeePercent` of the amount, rounded up, at least 1 credit when the percentage is above 0, never more than the amount. |

Every call runs on the server thread, checks before anything moves, and writes one ledger row whose `origin`
is `<modid>/<reason>`. Nothing is logged at info level about a payment; debug lines carry the caller, the
reason and the result, never an amount or a player.

#### Results

`EconomyResult` says what happened. Nothing moved unless it is `APPLIED`. `ok()` is true for `APPLIED` and
`DUPLICATE`. `translationKey()` is a short plain-words lang key (`economy_result.neroeconomy.<name>`) you can
show to a player. New constants are only ever appended.

| Value | Meaning |
| --- | --- |
| `APPLIED` | The credits moved. |
| `DUPLICATE` | The same request key was already applied; nothing moved this time. |
| `INVALID` | A missing, malformed or out-of-range argument, or an account of another kind. |
| `UNKNOWN_REASON` | The reason is not in the set the caller claimed. |
| `UNAVAILABLE` | No server is bound, or the server passed is not the running one. |
| `FROZEN` | The economy is frozen until an operator acts. |
| `RESERVE_SHORT` | The Reserve cannot cover the payment. |
| `CALLER_BUDGET_SPENT` | The caller's own daily limit is used up. |
| `SIBLING_BUDGET_SPENT` | The limit all other mods share is used up. |
| `PLAYER_CAP_REACHED` | Other mods have paid this player the daily cap already. |
| `INSUFFICIENT_FUNDS` | The paying side does not hold the credits. |
| `FUNDS_LOCKED` | The player has the credits, but part is a starting balance that has not vested. |
| `OVERFLOW` | The receiving balance would pass the largest storable amount. |
| `RATE_LIMITED` | The caller is calling faster than allowed, or the request-key table is full. |

#### Budgets and caps

All amounts are credits and all limits reset each economy day. Arithmetic is exact; there is no rounding
except the transfer fee.

| Key | Default | Limits |
| --- | --- | --- |
| `siblingDailyBudget` | 5,000 | All other mods together, including untyped Core deposits (counted under the label `unknown`). |
| `siblingCallerDailyBudget` | 2,500 | One caller, unless overridden. |
| `siblingCallerBudgets` | empty | Per-mod overrides as `modid=amount,modid=amount`, for example `neroquests=3000,nerocolonies=1500`. |
| `siblingPerPlayerDailyCap` | 2,000 | One player, across all callers; 0 turns the cap off and keeps no per-player count. |
| `siblingMaxSinglePayout` | 1,000 | One `pay`. |
| `siblingTransferFeePercent` | 5.0 | The `transfer` fee, 0 to 50. |

`pay` is refused, in this order, for a missing Reserve balance, a spent caller budget, a spent shared budget,
a reached player cap, or a balance that cannot hold the amount. Each caller is also rate-limited (a burst
of 64, then 16 a second); this is per caller, not per player.

#### Request keys

A request key makes a call safe to repeat. The same key from the same caller within 24 economy-hours answers
`DUPLICATE` and moves nothing. Only an applied request is remembered, so a refused one can be retried with the
same key. A key is 1 to 64 characters of letters, digits and `_ . : / # -`, and must never hold a player id or
name: a key that reads as a UUID, or holds 32 hex digits in a row, is refused as `INVALID`. Build keys from your
own record ids, such as `quest:42:stage:3`. A null key means no replay protection, for a caller that already
guarantees once-only.

#### Accounts for other mods

`new ForeignAccountId("mymod", "faction/ironclad")` names an account a mod keeps, such as a faction treasury.
The kind must be the mod id that claimed the caller; the value is `[a-z0-9_.:/-]{1,64}`, an id and never a name.
It holds real credits, persists with the ledger and counts in the supply check. A caller can only deposit to,
withdraw from and read accounts of its own kind. If the mod is later removed the account stays in the ledger and
`/eco admin supply` lists it apart, marked as unclaimed.

```java
ForeignAccountId treasury = new ForeignAccountId("mymod", "faction/ironclad");
CALLER.deposit(server, memberId, treasury, 100, "sale", "donation:8841");
long held = CALLER.balance(server, treasury);
```

#### Events

`EconomyEvents` lets a mod hear what settled, with `compileOnly` listeners:
`onSold`, `onBought`, `onFilled`, `onDividends` and `onSiblingPaid`.

```java
EconomyEvents.onSold(sold -> {
    if (sold.venue() == EconomyEvents.Venue.HOUSE) progress.count(sold.seller(), sold.itemId(), sold.quantity());
});
```

- An event carries **one party only**: the seller's side, the buyer's side or the order owner. A buyer and seller
  of the same trade are never in one event, a Merchant House is never named as anyone's counterparty, and no event
  has a balance, a name or free text. `Dividends` is world-level (day, total, factor, stock count) and names no
  holder. `Venue` is `BOARD`, `SHOP`, `HOUSE`, `AUTO_TRADER` or `AUCTION`.
- Events are queued where the operation settles and handed out at the end of the economy's server tick, after
  the ledger applied and the store was marked dirty. A listener therefore never runs inside a half-finished
  match and may call the economy. Nothing fires for a refused or rolled-back operation, the demo gallery, practice
  mode, or goods from a creative or spectator source.
- A listener that throws is caught, reported once at warn without the event, and stays registered.
- Do not keep the player id beyond your own handling: count the progress and drop the id.

#### Channels and gates through Core

These need no compile dependency: a mod listens to Core's `ThresholdEvents` and `GateEvents` by id. No scope is
ever a person.

| Channel | Scope | Value | Threshold | Rising |
| --- | --- | --- | --- | --- |
| `neroeconomy:reserve` | `world` | Reserve balance | `reserveAlertLow` (25,000) | false when it falls below, true when it recovers |
| `neroeconomy:house_stock` | commodity key | units held | 0 | false when a House sells out, true when stock returns |
| `neroeconomy:market_shock` | item id or `#tag` | multiplier x 1000 | 1000 | true when a shock starts, false when it ends |
| `neroeconomy:sector_index` | sector word | index in cents | the band level from the day open (`sectorBands`, default 5 and 10 percent, above and below) | true above the level, false below it |

The Reserve, House stock and sector channels are checked every 30 seconds; the first check after a start only
records the state. Shock channels fire the moment a shock starts or ends.

Six per-player milestones are Core progression gates, scope `player`: `neroeconomy:first_sale`,
`first_purchase`, `first_investment`, `first_bond`, `first_offer_accepted` and `trader_100` (a hundred settled
sales). A gate opens once a second for players who are online; a milestone reached while the player was offline
opens at their next check. A quest pack can use them with no code through a gate objective.

#### Asking for a market shock

A mod named in `shockSources` (default `neroevents`) can request a shock without a compile dependency, by
firing a Core threshold crossing on the channel `<modid>:market_shock_request`:

| Field | Meaning |
| --- | --- |
| `scope` | The item id, or `#tag`, to shock. |
| `value` | The multiplier times 1000, clamped to `minPriceMultiplier`..`maxPriceMultiplier`. |
| `threshold` | How long, in economy-minutes, 1 to `shockMaxMinutes` (default 1440). |
| `rising` | True applies the shock; false ends the shock on that scope early. |

Requests from a mod that is not listed or not loaded, malformed ones, and requests past `maxActiveShocks`
(default 64; replacing a shock does not count) are ignored with one debug line. The typed
`MarketShocks.applyShock` stays for `compileOnly` callers, and `activeCount`, `active` and `clearShock` read and
end shocks.

#### Commodities and queries

Data stays the main route: a mod ships `data/<modid>/neroeconomy/commodities/*.json` (see
[Data packs](Data-Packs.md)) and adds component-keyed items to the `neroeconomy:never_commodity` tag. For a
default without a data file:

```java
CommodityApi.Registration r = CommodityApi.registerDefault("mymod", new CommodityApi.CommoditySpec(
        "mymod:brass_ingot", 12, CommodityClass.CRAFTED, null, null, null, null, null, null, null, null));
CommodityApi.exclude("mymod", "mymod:charged_cell");
```

- `registerDefault` takes the same fields and limits as one data entry; a null field takes the class default.
  The key is an item id in the caller's namespace, or a tag `#<caller>:...` or `#c:...`. Results: `ACCEPTED`,
  `BAD_CALLER`, `BAD_KEY`, `FOREIGN_KEY`, `INVALID`, `TOO_LATE` (after the server starts) and `TOO_MANY`
  (256 a caller).
- **A data pack always wins.** A default only loads when its items resolve, passes the same arbitrage guard as
  data (a looping default has its buy side switched off), and appears in `/eco admin reload-check` with the
  source `code:<modid>`.
- `exclude` stops unmodified stacks of an item being a commodity whatever the data says.
- Read-only queries, all copies: `CommodityApi.referencePrice`, `bid` and `ask` return an `OptionalLong` for an
  item id (empty when it is no commodity, not quoted yet, or the House does not buy or sell it now);
  `ExchangeApi.sectors` and `sector` return `SectorQuote(sector, indexCents, dayOpenCents,
  dayChangeBasisPoints, members)`, where a sector index is the equal-weighted mean of its listed companies.

### Currency from other mods

Core's `CurrencyApi` is the facade mods already written against Core use, and it keeps working. Since 0.3
every credit it moves is a transfer with a typed reason, never a mint. Prefer the handle above, which adds a
reason, a per-mod budget and idempotence:

- An untyped `deposit` is a transfer from the **Reserve**, counted against `siblingDailyBudget`
  (5,000 credits a day by default). It returns `false` when that budget or the Reserve is
  exhausted, so a caller must check the result.
- An untyped `withdraw` is a transfer **into** the Reserve.
- `CommodityApi` is a read-only view of the commodities the Merchant Houses trade: reference prices,
  the daily limits, what a House is quoting now, and what one player may still trade today.

The repository's `docs` folder also holds a set of short integration notes, one per sibling mod, that
describe what each mod can do with NeroEconomy using only data, a Core channel or a call into the
public `api` package. They are the place to look before adding a bridge class.

## Companion-app module

The Core Link module is `neroeconomy`, **schema version 2**. Everything is scoped to the requesting
player and runs on the server thread, and a reply never names another player. The module keeps no
copy of any economy state beyond the daily remote-spend row described in [Privacy](Privacy-and-Erasure.md).

| Kind | Names |
| --- | --- |
| Sections | `balance`, `transactions`, `market`, `summary`, `orders`, `vault`, `stocks`, `portfolio`, `offers`, `jobs`, `auctions` |
| Actions | `trade`, `order_place`, `order_cancel`, `rule_set`, `rule_delete`, `rules_pause`, `stock_buy`, `stock_sell`, `bond_buy`, `pay`, `offer_send`, `offer_accept`, `offer_decline`, `offer_cancel`, `claim_collect`, `vault_deposit`, `vault_withdraw` |
| Live topics | `balance`, `orders`, `offers`, `portfolio`, `alerts` |

Schema 1 keys (the `balance`, `transactions` and `market` sections and the `trade` action, described
below) are unchanged and kept for one release; the other sections and actions are new in schema 2.
The full wire format, with a sample of every section and action, is in the NeroLink wiki. In short:

- **Offline.** Every action works while the player is offline except `vault_deposit` and
  `vault_withdraw`, which touch a live inventory (a schema 1 `trade` *sell from the hand* also needs
  the player in game). Goods come from and go to the player's [Vault](Vault.md), and rules made from
  the app are vault-backed.
- **Every action needs a request id**, is re-validated and re-priced on the server, honours
  `remoteTradeEnabled`, counts against `remoteDailySpendLimit` and `remoteDailyGoodsLimit`, and is
  rate-limited per action. See [Fair play](Fair-Play.md).
- **`market` with `view=book&item=<id>`** returns one item's book as price levels (at most 10 a side;
  counts only, no owner or order id). `stocks` carries `feeBps` and `holdMinutes`. `vault` carries
  `inGame` and `liveActions`. `stock_sell` with `structuredRefusal: true` returns a reduced-gain
  refusal as an ok result (`sold: false`, `payable`, `acceptParam`, `acceptValue`), accepted by
  sending the sale again with a new request id.

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

- `buy` works while the player is logged out: the goods are delivered to their Vault. When the player
  is online the goods go to their inventory when they fit, and to the Vault otherwise.
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
| `delivery` | string | `inventory`, `claim` (the goods went to the player's Vault), `credit_claim` or `listed`. |

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
| `neroeconomy:offer_received` | You received a trade offer. |
| `neroeconomy:offer_accepted` | A trade offer you sent was accepted. |
| `neroeconomy:offer_expired` | A trade offer you sent expired and was returned to you. |
| `neroeconomy:rule_paused` | One of your auto-trade rules has paused. |
| `neroeconomy:rule_filled` | One of your auto-trade rules is complete. |
| `neroeconomy:bond_matured` | A Savings Bond matured and was paid into your balance. |
| `neroeconomy:dividend_paid` | Your shares paid a dividend. |
| `neroeconomy:stock_delisted` | A company you held shares in left the exchange, and you were paid out. |
| `neroeconomy:order_filled` | One of your market orders was filled. |
| `neroeconomy:vault_full` | Your vault is full. Take some items out before more can arrive. |

## Soft integrations

Soft seams are string-keyed: NeroLogistics delivery posting, NeroEvents shocks, future NeroFactions
modifiers, NeroCreatures bounty entity ids, and the Nerospace meteor entry for the Credit Chip.
Core remains the only hard dependency.
