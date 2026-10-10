# NeroEconomy 0.3 — design

The single specification for the 0.3.0 work: a living economy with trading, an exchange, autonomous
markets, a screen for everything and offline trading from the companion app. It records decisions,
not history. Where this document and older notes disagree, this document wins; the abuse rules in
[Abuse rules](#abuse-rules) override everything else.

## Principles

1. **Money never leaves the ledger.** Escrow, claims, fees, NPC working capital and exchange
   principal are balances of system accounts, not numbers written off and written back.
2. **Nothing creates credits without limit.** Every payout the system makes is a transfer out of
   the Reserve. Only four things mint: the Reserve's initial fill, its small daily top-up, the
   one-time migration of escrow that already existed, and an operator's explicit grant.
3. **One store, one save.** All economy state is one saved-data file under Core's backup guard, so
   a save can never persist half an operation.
4. **Wall-clock simulation.** The economy advances in fixed steps of economy time and catches up
   after a pause or a shutdown. It never depends on game ticks, day/night, `/tick`, `/time` or sleep.
5. **Pure rules, thin edges.** Rules that move credits or goods live in classes with no Minecraft
   registry dependency so they run in plain JUnit, including the operation fuzzer.
6. **The server decides.** Clients and the app send intents with a request id; the server
   re-validates and re-prices everything at execution.

## Accounts and supply

| Account | Holds |
| --- | --- |
| `player:<uuid>` | A player's spendable balance |
| `system:reserve` | The pool every system payout comes from and every sink pays into |
| `system:escrow` | Credits promised but not yet delivered: buy-order escrow, credit claims, player-funded job payouts, standing auction bids, rule budgets, offer credits |
| `system:exchange` | Principal of open share and bond positions, and interest reserved for bonds |
| `system:house/<id>` | A Merchant House's working capital |

- **Invariant A:** the sum of every account balance equals `minted − burned`.
- **Invariant B:** the `system:escrow` balance equals the sum of every recorded escrow obligation.
- **Invariant C:** the `system:exchange` balance equals open principal plus reserved bond interest.
- Minting types: `RESERVE_MINT`, `MIGRATION`, `ADMIN_GRANT`, `ADMIN_SET` (upward). Burning types:
  `ADMIN_TAKE`, `ADMIN_SET` (downward). Every other movement is a transfer with a typed reason.
- Minted and burned totals are kept per reason and per economy-day. `/eco admin supply` and the
  admin screen show circulation, Reserve, escrow, exchange, minted and burned today — aggregates only.
- System accounts appear in transaction rows by their stable name (they are not personal data).
  Player references stay salted pseudonyms. Rows carry reason, amount, currency and time — never an
  item name or free text.

### The Reserve

| Key | Default | Meaning |
| --- | --- | --- |
| `reserveInitial` | 250000 | Minted once when the economy is first created or migrated |
| `reserveDailyMint` | 5000 | Minted once per economy-day, skipped while the Reserve is at or above its cap |
| `reserveCap` | 2000000 | The top-up stops here; sinks may still push the Reserve higher |
| `npcDailyBudget` | 20000 | Most the Merchant Houses may pay out to players per day |
| `exchangeDailyBudget` | 15000 | Most the exchange may pay in gains, dividends and newly reserved bond interest per day |
| `siblingDailyBudget` | 5000 | Most that raw deposits from other mods may draw per day |
| `systemJobDailyPayoutCap` | 5000 | Existing key; system jobs now escrow their payout from the Reserve when posted |

- A single payout that the Reserve or its daily budget cannot cover is **refused before anything
  moves**, and the caller is told why in plain words.
- Batch payouts (dividends, bond interest reservations in one step) are **scaled down pro rata**
  when the budget is short; the screen shows the factor that was applied.
- Starting balances and Credit Chips are transfers from the Reserve. A new account gets what the
  Reserve can pay (possibly nothing).
- An untyped `CurrencyApi.deposit` from another mod is a transfer from the Reserve counted against
  `siblingDailyBudget`; it returns `false` when the budget or the Reserve is exhausted. An untyped
  `withdraw` is a transfer into the Reserve. The public API offers the typed, per-mod equivalents.
- Erasing a player moves their balance, their escrow and their exchange principal to the Reserve
  with reason `ERASURE_SWEEP`. Their goods are destroyed.

### Faucets under control

- **Starting balance** is locked until the account has `startingBalanceVestMinutes` (30) of play
  time: locked credits can pay fees and buy from Merchant Houses but cannot be paid, offered,
  traded, used to buy from another player, or put into shares or bonds. Share and bond principal is
  only ever taken from credits the account has earned, so an early sale or redemption cannot turn a
  locked balance into a spendable one. Goods bought from a House with locked credits are accepted
  as a bounded spread loss: a House sells above its reference and buys below it, so the goods
  return less than was spent, at most the starting balance per account.
- **Credit Chip**: `creditChipDailyLimit` redemptions per account per day, with the value stepping
  down across the day (100 %, 80 %, 60 %, 40 %, 20 % of `creditChipValue`), paid from the Reserve.
  All accounts together may draw at most `creditChipDailyBudget` (3000) a day, so many accounts
  cannot out-drain the daily mint. A creative or spectator player cannot redeem one.
- **System jobs** escrow their payout from the Reserve when posted and are refused when the daily
  cap or the Reserve cannot cover them.

### Sinks

| Key | Default | Charged |
| --- | --- | --- |
| `marketFeePercent` | 2.0 | On the seller's proceeds of every board, shop and matched sale |
| `listingFee` | 1 | Flat, per board listing or buy order posted; not refunded |
| `auctionFeePercent` | 5.0 | On the winning bid, from the seller's proceeds |
| `exchangeFeePercent` | 1.0 | On every share buy and sell |
| `tradeTaxPercent` | 0.0 | On the credits side of a direct trade or an accepted offer |

A fee is `ceil(total × percent / 100)`, at least 1 credit when the percentage is above zero, and is
computed on the order total, so splitting an order into singles never pays less. All fees go to the
Reserve. A price modifier registered by another mod changes the **buyer's** price only: the seller
receives the base price less the fee, a discount is paid by the Reserve but never exceeds the fee
collected on that same sale (so two accounts trading back and forth can never draw on the Reserve),
and a surcharge goes to the Reserve. Modifiers never apply to Merchant
House or exchange quotes.

## Storage

- One saved-data store, `neroeconomy:economy`, loaded through Core's recovery guard. Its sections:
  ledger, market (shops, claims, request ids), obligations, shop owners, vault, book, offers,
  rules, exchange, simulation.
- **Migration** from the four 0.2 stores happens once on first load: they are read, adopted as
  sections and then emptied on disk so they can never be imported twice. Item claims become vault
  contents; board listings and buy offers become book orders; credit escrow that was written off
  the ledger under 0.2 is restored to `system:escrow` with reason `MIGRATION`.
- A small marker store records that an economy exists. If the marker says so but the economy store
  comes back empty and no backup could be restored, the economy **freezes**: every value-moving
  operation is refused and operators are told. `/eco admin health` shows the state and lets a
  level-3 operator accept a fresh start.
- **Inventory boundary.** Goods crossing between the store and a live inventory are the only
  operations that span two files: the store and the player's data. Neither is saved on its own
  schedule. The store is saved only by the game's own save (autosave, `/save-all`, shutdown), which
  writes player data first and then chunks and saved data in one pass, so a hard kill rolls the
  inventory and the store back to the same save and an item is left in exactly one place. Nothing
  in the mod requests an early save of the store; a source-scan test fails if any code does. The
  residual window is a crash in the instant of a save itself, while the player files are written
  and the store is still being written: the same class of risk as any item in vanilla Minecraft,
  and the only one left.
- Every section has hard bounds, a purge, an eraser and an export.

### Goods

A **lot** is `(item id, count, payload id)`. Payload id 0 means an unmodified stack — fungible with
every other unmodified stack of that item. Any other payload id points at a stored prototype stack
(custom name, enchantments, contents) and is fungible only with itself. The pure rules see lots;
the edge converts to and from item stacks and enforces the `neroeconomy:untradeable` tag and the
encoded-size cap. Prototypes no lot references are dropped at the next save.

### Vault

Each account has a vault of up to `vaultStacks` (54) stacks. Deposits and withdrawals happen at the
Bank Terminal with the player present. Purchases made away from the game, fills of standing orders,
refunds and expiries deliver to the vault. A player-initiated purchase is refused **before any
credit moves** when the vault cannot take the goods; refunds and expiries always book, even past
the cap, and deposits stay blocked until the vault is back under it.

## Trading

### The book

- A **sell order** is a lot at a price per unit; a **buy order** is an item id, a quantity and a
  price per unit with its credits in escrow. Shops stay as they are (one slot, owner-priced) and
  are listed beside the book.
- **Matching**: an order that crosses the other side fills at once — best price first, oldest
  first, partial fills allowed, at the resting order's price. Only unmodified lots match buy
  orders. An account never matches itself. A buyer who bid higher than the fill price gets the
  difference back.
- Sell orders are sourced from the hand (in game) or the vault (in game or from the app).
- Proceeds go to the seller's credit claim. Goods bought at once go to the buyer's inventory when
  they are present and it fits, otherwise to their vault; goods bought by a resting order always go
  to the vault. A cancelled or expired buy order returns its escrow to the owner's credit claim.
- Open market screens get a delta when the book changes; nothing is pushed per tick.

### Direct trade

`/trade <player>` or sneak-use on a player with an empty hand sends a request (accept, decline,
timeout). Both players must be within `tradeRangeBlocks` (16) or both at an economy terminal. A
two-sided window holds up to nine stacks and a credit amount per side. **Any change by either side
clears both confirmations.** After both confirm, the contents freeze for three seconds and then
swap in one operation. Tooltips show the true item id whenever a stack is renamed. Closing, moving
out of range, dying or disconnecting cancels and returns everything. Requests are rate-limited and
a player can mute them.

### Offers

An offer sends lots and credits to a named account and may ask for an item id and quantity and
credits in return. Everything offered is escrowed at send. The recipient accepts or declines in
game or in the app; an unanswered offer expires after `offerDurationHours` (48) and refunds. A
sender may have at most four open offers waiting with the same recipient (on top of the per-sender
and per-inbox caps), so filling one inbox takes many accounts, and a recipient can mute offers.

### Auto-trade

A **rule** is `{item id or tag, side, limit price, quantity per day, total budget or quantity,
source or destination}`. A buy rule escrows its budget; a sell rule escrows its goods. Rules place
and refresh ordinary book orders, so they are matched by the same engine. They run in the
simulation step and when the book changes, under a global per-tick budget. Caps: `maxRulesPerPlayer`
(8) and `ruleDailyValueCap` (10000 credits per account per day). Each rule reports one status:
running, filled, out of budget, nothing to sell, paused (chunk unloaded), price not met.

The **Auto-Trader** block sits against a container and is itself a vanilla `WorldlyContainer`, so
hoppers, ducts and drones can feed it. It never loads chunks: when its chunk is unloaded its rule
pauses and says so. A vault-backed rule keeps running with the owner offline and the server empty.

## The exchange

Listed companies are data: `data/<namespace>/neroeconomy/stocks/*.json`. Twelve fictional
companies ship, one index fund and three bond terms.

- **Price model**, one update per simulation step, a pure function of (state, step index, secret):
  each stock has an anchor that grows by its drift and a price that reverts toward the anchor,
  with a random term from a keyed hash of the stock id and step index, a bounded nudge from the
  traded volume of its linked commodity tags, and any active market shock. Moves are limited per
  step and per day, the price never falls below a floor, and a stock that hits its daily limit is
  halted for an hour.
- **The exchange is the counterparty.** Buys pay the ask and sells receive the bid (a spread around
  the model price), plus `exchangeFeePercent`. Player orders do not move the price.
- **Principal and gains.** A buy moves principal to `system:exchange`. A sell returns principal;
  a gain is paid by the Reserve within `exchangeDailyBudget`, a loss goes to the Reserve. When the
  Reserve cannot pay a gain in full the sell is refused unless the player accepts the exact reduced
  amount quoted; principal always returns in full.
- **Dividends** are paid once per economy-day on units held for a full day, pro rata when the
  budget is short. **Splits**: a share at or above 2000 credits splits 10-for-1 at the day roll.
- **Limits.** A lot cannot be sold for `shareHoldMinutes` (60) after it was bought. Positions are
  capped per stock (`maxPositionPerStock`, 5000 credits at cost) and in total (`maxPortfolio`,
  20000). An immediate round trip always loses the spread and both fees.
- **Products.** *Savings Bonds*: fixed term, interest reserved from the Reserve at purchase, cannot
  lose; redeemed early they return principal only. *Steady* shares: small moves, pay dividends.
  *Risky* shares: large moves either way. The index fund is a basket of every listed stock.
- **Practice mode** uses play credits in a separate sandbox that can never touch the ledger.
- **The secret** is a per-world value from `SecureRandom`, stored server-side only, never derived
  from the world seed and never sent to a client or the app.

## Autonomous economy

- **Commodities** are data: `data/<namespace>/neroeconomy/commodities/*.json` (item or tag,
  reference price, NPC daily supply and demand caps, per-account daily caps, elasticity, recovery
  rate, optional buy-only or sell-only). Files under `commodities/compat/<modid>.json` load only
  when that mod's items exist.
- **Merchant Houses** are data too. Each step a house refreshes its orders on the book: it sells
  from a slowly restocked inventory and buys within its working capital. Selling to a house lowers
  its bid for that commodity and buying raises its ask; both recover over time. Caps are global
  and per account. A house is funded from the Reserve within `npcDailyBudget` and its income is
  swept back.
- **Reference prices** drift toward what players pay each other: volume-weighted, capped per
  account, ignoring self-matches and tiny trades, clamped per step. A reference moves only when at
  least `referenceMinPairs` (3) distinct pairs of accounts traded the commodity since the last
  step, and each pair counts for no more than the pair ranked that many places from the top, so
  two accounts washing one trade after another move nothing. The pairs are kept in memory only, as
  one-way hashes, and cleared every step. Over a day a reference stays within
  `referenceDriftDayPercent` (10 %) of where the day began. The arbitrage guard re-checks every step
  in which any reference moved, not only once a day.
- The step also runs NPC auctions and posts system jobs, funded as above.
- **Empty server.** Steps are counted in economy time. On boot or un-pause the missed steps are
  replayed a few per tick, never more than `simCatchUpMaxHours` (72) worth.
- **Arbitrage guard.** A check walks every recipe it can read (vanilla types and the generic
  machine shapes) and fails if any chain turns goods a house sells into goods a house buys at a
  profit at reference prices including the spread, or if any house ask is not above its bid. It
  runs in unit tests against the shipped data and from `/eco admin reload-check`. A commodity that
  fails has its NPC buy side switched off until the data is fixed.

## Screens

One **Economy Hub** with tabs: Wallet, Vault, Market, My orders, Invest, Trade, Jobs, Auctions,
Shop, Admin (level 3). Each block opens the hub at its own tab: Bank Terminal → Wallet, Trading
Terminal → Market, Market Board → My orders, Exchange Terminal → Invest, Auto-Trader → its rule,
Merchant Stall → Market filtered to its house. Tabs that need item slots (Vault, direct trade,
Auto-Trader) are their own menus, opened by the server from the tab bar.

Pages are pushed by the server; the client never computes a price or a balance. Every string is a
lang key, every control has a tooltip, every confirm step restates what will be spent and
received, every empty state says what to do next, and every tab has a **?** button that opens its
demo.

## Demos

- A **Show me** walkthrough per tab: client-side, scripted over sample data, with a permanent DEMO
  banner. It sends no intent and cannot reach the ledger.
- The **Trader's Handbook** item, given once on first join (config) and craftable.
- The **gallery** command builds one cluster per system from sandboxed data that never reaches the
  market, link snapshots, the Reserve or telemetry.

## Companion app (link schema 2)

Sections, all scoped to the requesting player: `summary`, `balance`, `transactions`, `market`,
`orders`, `vault`, `stocks`, `portfolio`, `offers`, `jobs`, `auctions`. Field names follow the
shipped app; schema 1 keys stay for one release. The `balance` topic is published, throttled.

| Action | Offline | Note |
| --- | --- | --- |
| `trade` | buy: yes, to the vault | Schema 1 compatibility; a schema 1 sell from the hand still needs the player in game |
| `order_place`, `order_cancel` | yes | Goods from and to the vault |
| `rule_set`, `rule_delete`, `rules_pause` | yes | Vault-backed rules only |
| `stock_buy`, `stock_sell`, `bond_buy` | yes | Holding period and limits apply |
| `pay` | yes | Currency to another account |
| `offer_send`, `offer_accept`, `offer_decline` | yes | Items, blocks and currency through vault and escrow |
| `offer_cancel` | yes | The sender takes back an open offer: goods to the vault, credits to the balance |
| `claim_collect` | yes | Proceeds to the balance, goods to the vault |
| `vault_deposit`, `vault_withdraw` | no | They touch a live inventory |

Additions found while building the app screens (all optional keys inside schema 2): `market` with
`view=book&item=<id>` returns the item's book as price levels (at most 10 a side, counts only, no owners);
`stocks` carries `feeBps` and `holdMinutes`; `vault` carries `inGame` and `liveActions`; `stock_sell` with
`structuredRefusal: true` returns a reduced-gain refusal as an ok result (`sold: false`, `payable`,
`acceptParam`, `acceptValue`), accepted with a new `requestId`.

Every action needs a `requestId`, is re-validated and re-priced on the server, honours
`remoteTradeEnabled`, counts against `remoteDailySpendLimit` (5000 credits per account per day)
and is rate-limited per action.

Goods leaving an account through the app are limited too, by value: `remoteDailyGoodsLimit` (5000
credits' worth per account per day) counts goods in offers, sell orders, sell rules (including
resuming a paused one) and the goods an accepted offer asks for. A unit is valued at the higher of
its data reference and its live House reference; a non-commodity unmodified unit counts
`remoteGoodsFloorValue` (10) and a modified one (for example a filled shulker box)
`remoteModifiedGoodsValue` (500) or its item's reference if higher. Goods count at their full
worth, not by the price asked, so a sell order at 1 credit counts the same as one at fair price.
The two limits are counted apart and together bound what a stolen token can cost in a day.

The request-id ledger remembers the latest 128 ids per player for 10 minutes, across restarts. An
id older than that, or pushed out by 128 newer ones, is accepted again; only the account owner or
token holder can produce that many requests, so a replay gains nothing a fresh id would not.

## Abuse rules

Nothing overrides these.

- Every intent is validated for: sign, zero, overflow, stale container, menu open, range, level,
  alive and not a spectator, and a per-player rate limit.
- Arithmetic is exact; fees round up; an order split into singles gains nothing.
- No operation may leave credits or goods in two places or in none: disconnect, death, dimension
  change, chunk unload, full inventory, full vault, replayed and reordered packets, two players on
  one row in one tick.
- Alternate accounts are handled by caps, fees and vesting. **IP addresses are never stored or
  compared.**
- The exchange leaks nothing about the next price; the holding period, position limits, floor and
  halt always apply.
- Merchant Houses cannot be drained: daily caps, elasticity, the arbitrage guard, and a Reserve
  that can never go negative.
- Nothing a creative-mode player or the gallery does can put value into the real economy except
  through the audited operator commands.
- A stolen app token can spend at most the daily remote limit and move goods worth at most the
  daily remote goods limit.
- Every store has a hard bound and a purge.
- Operators and `online-mode=false` servers are outside what a mod can defend; this is documented.

The 0.3 review of these rules, attempt by attempt and with the tests that hold each one, is in
[ABUSE-REVIEW-2026-10](ABUSE-REVIEW-2026-10.md). "Reason-coded" administrator changes means a fixed
transaction type (`ADMIN_GRANT`, `ADMIN_TAKE`, `ADMIN_SET`), never operator-typed text.

## Privacy

Player identifiers are stored only where the function needs them, are listed in `PRIVACY.md`, and
are exportable, erasable through Core and bounded. No player name or identifier appears in a log,
alert, broadcast, telemetry event or analytics event. Counterparties are never named to each other
except where a player addresses someone themselves. There is no wealth leaderboard.
