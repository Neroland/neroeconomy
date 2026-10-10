# NeroEconomy privacy notice (POPIA / GDPR)

NeroEconomy has the largest player-data surface in the Neroland lineup, because an economy is by
definition a record of what players did with each other. This document describes exactly what is
stored, where, for how long, and how a player exercises their rights. Every statement below maps to
a config key in `config/neroeconomy.properties`, to a command, or to a named file.

All storage is **server-side world data**, held in the world folder of the server that runs the
mod. NeroEconomy sends nothing to Neroland. The only paths by which data leaves the server are the
optional companion-app module and the crash telemetry, both described below; telemetry is inert in
every published build.

## Who is responsible and who to contact

- **The server operator.** The person or organisation that runs a server chooses to install and
  configure NeroEconomy, and the data described here exists only in that server's files. The
  operator is the party responsible for the data on that server. Requests to see, export, correct
  or erase your data go to the operator of the server you play on. Export, balance correction and
  `/eco erase` need operator permissions; Neroland Core also gives every player a command to erase
  their own data, described under [Erasure](#erasure).
- **The mod's author.** Neroland provides the software. Neroland does not run your server, does not
  receive the data described here, and cannot read, export or erase it.
- **Contact.** Questions about the software or about this notice: `info@neroland.co.za`.

This notice describes what the software does. It is not legal advice, and it does not replace the
privacy notice an operator may need to publish for their own server.

## What is stored

One combined world store holds everything. It is the saved-data file `neroeconomy:economy`, in the
overworld's `data` folder, and it is made of named sections. "UUID" means the player's Minecraft
account UUID. Player names are never stored. Two small marker and recovery files sit beside it.

The older 0.2 stores (`neroeconomy:ledger`, `neroeconomy:market`, `neroeconomy:obligations`,
`neroeconomy:shop_owners`) are read once, when a world is first opened by 0.3, and adopted into the
combined store. Their files are then emptied, so they hold no player data afterwards.

### Inventory

Every row below is a section of `neroeconomy:economy`. A section that holds nothing writes no
field at all, and an account with nothing in a section has no row in it.

| Section | Data and fields | Keyed by | Purpose | Retention | In `/eco export` | What erasure does |
| --- | --- | --- | --- | --- | --- | --- |
| `ledger` | Balance: account kind, UUID, currency, amount | UUID | The player's credits | Until erasure | Yes (`balance`) | Removed; the credits are destroyed |
| `ledger` | Initialised-account flag | UUID | Pay the starting balance once only | Until erasure | No separate field | Removed |
| `ledger` | Starting-balance lock: the amount still locked and a flag that the account has not yet vested | UUID | Stop a fresh account paying out its starting credits to others | Until the account has `startingBalanceVestMinutes` of play time | Yes (`starting_balance_vesting`) | Removed |
| `ledger` | Handbook flag: the Trader's Handbook was given on first join | UUID | Never give the book twice | Until erasure | Yes (`handbook_given`) | Removed; a returning player is treated as new |
| `ledger` | Transaction-logging opt-out flag | UUID | Remember the player's choice | Until erasure | Yes (`transaction_logging_opt_out`) | Removed |
| `ledger` | Transaction rows: reason code, amount, currency, time, source reference, destination reference, and for a payment made by another mod an `origin` (`<modid>/<reason>`) | Salted pseudonym, not UUID | Audit trail and the player's own history | `transactionRetentionDays` and `maxTransactionRecords` | Yes, every retained row that names the player (`transactions`) | Every row that names the player's pseudonym is deleted |
| `ledger` | Credit Chip counter: day number, chips redeemed that day | UUID | Enforce `creditChipDailyLimit` | Dropped by the daily sweep once the day has passed | Yes (`credit_chip_redemptions`) | Removed |
| `ledger` | Erasure tombstone: the pseudonym of an erased account | Salted pseudonym | Stop a starting balance being paid again | Life of the world | No | Created by erasure; never removed |
| `ledger` | System accounts (Reserve, escrow, exchange, Merchant Houses), minted and burned totals per reason and day, pseudonym salt (one random value per world), economy-clock offset and last reading | Not per player | Supply accounting, pseudonyms, stable deadlines | Life of the world | No | Unchanged |
| `market` | Shop listings: listing id, seller, the item stack, unit price, expiry, location, last activity time, demo flag | UUID | Hold goods in escrow and sell them from a Player Shop | Until sold, cancelled, expired or erased | Yes (`market.listings`) | Removed; the escrowed stock is destroyed |
| `market` | Credit claims: credits owed to the player, split by reason | UUID | Deliver sale proceeds, payouts and refunds | Until collected or erased | Yes (`market.credit_claim`, `market.credit_claim_by_reason`) | Removed; the credits are destroyed |
| `market` | Processed request ids: a random id and the time it was handled | UUID | Refuse a replayed packet or app action | 10 minutes, at most 128 per player | Yes (`market.recent_request_ids`) | Removed |
| `vault` | The player's Vault: lots of (item id, count, payload id). Goods bought while away, refunds, expired orders and offers, and items the player deposited | UUID | Hold the player's goods safely between visits | Until withdrawn or erased; no time limit | Yes, item id and count only (`vault`) | Removed; the goods are destroyed |
| `goods` | Prototype stacks: the full item data (name, enchantments, contents) of modified stacks that a lot refers to. Not tied to a player | Prototype id | Keep a modified stack intact while it sits in a vault, an order or an offer | Dropped at the next save once no lot refers to it | No (the export lists item id and count only) | Dropped as soon as the erasure leaves no lot referring to it |
| `book` | Sell orders: order id, owner, item, count, unit price, expiry, source (hand, vault, rule or House). Buy orders: order id, owner, item, remaining quantity, unit price, credits in escrow, expiry, source | UUID | The order book: hold goods and credits until an order fills, expires or is cancelled | Until filled, cancelled, expired (`defaultListingDurationHours`) or erased | Yes (`book.sell_orders`, `book.buy_orders`) | Removed. Escrowed goods are destroyed; escrowed credits move to the Reserve |
| `offers` | Offers: id, sender, recipient, the lots and credits given, the item and credits asked in return, created and expiry time. A mute flag | UUID of sender and of recipient | Hold what is offered until the recipient answers | Until accepted, declined, cancelled, expired (`offerDurationHours`) or erased | Yes (`offers.sent`, `offers.received`, `offers_muted`) | Offers the player sent are removed (goods destroyed, credits to the Reserve). Offers sent to them are refunded to the sender: credits as a claim, goods to the sender's vault. If that cannot be booked at once, the offer is kept under the sender's own name until it expires. The mute flag is removed |
| `rules` | Auto-trade rules: id, owner, item or tag, side, limit price, quantity per day, total, amount done, filled today, budget left in escrow, binding (vault or an Auto-Trader position), paused flag, status, created time. Per account: the pause-all switch and the credits its rules filled today | UUID | Keep standing buy and sell rules running while the owner is away | Until the owner deletes the rule or is erased (a filled rule stays listed as filled) | Yes (`rules`) | Removed; what is left of a buy budget moves to the Reserve |
| `trade_escrow` | The goods each side of an open direct trade has put on the table | UUID | Make sure a crash cannot lose or copy goods mid-trade | Empty as soon as the trade ends; anything found on load goes to the owner's vault | Yes (`trade_escrow`) | The other player's goods are returned first; the erased player's are destroyed |
| `auto_traders` | Dimension and block position of each Auto-Trader, and its owner | UUID | Decide who may configure a block, and reach every block of an erased player in one pass | Until the block is removed or the owner is erased | Yes (`auto_traders`) | Rows removed; the blocks stay in the world without an owner |
| `exchange` | Holdings: stock id, units, cost, and per-lot units, cost and time bought. Savings Bonds: id, term, principal, interest, bought and maturity time. Last dividend day and amount | UUID | The player's shares, index units and bonds | Until sold, matured or erased | Yes (`exchange.holdings`, `exchange.bonds`, dividend fields) | Removed; cost, principal and reserved interest move from the exchange account to the Reserve |
| `exchange` | Practice portfolio: play balance, play holdings and bonds, last active day, last reset day | UUID | Practice mode, a sandbox that never touches the ledger | Dropped after 30 economy days without use | Yes (`exchange.practice`) | Removed |
| `exchange` | Prices: the state of every listed company and the index fund | Company id | Where each price has got to | Life of the world | No (about no player) | Unchanged |
| `houses` | Per account and commodity: credits paid to and received from Merchant Houses today. Per account: credits of trade with other players counted toward reference prices today | UUID | Enforce per-account daily caps and bound the reference-price drift | Dropped at the economy-day roll | Yes (`merchant_houses`) | Removed |
| `houses` | House stock, working capital, price pressure, drifted reference prices and the House orders on the book | House id and commodity | Run the Merchant Houses | Life of the world | No (about no player) | Unchanged |
| `remote_spend` | Economy day, credits spent and goods value sent away through the companion app that day | UUID | Bound what a stolen app token can cost a player in a day | The current economy day only; earlier rows are dropped whenever a row is written | Yes (`remote_spend`) | Removed |
| `sibling` | Per other mod: credits it paid out today (a count per mod id, no player). Per player, only while `siblingPerPlayerDailyCap` is above 0: the credits other mods paid them today. Applied request keys: a caller-chosen key and the time it lapses | Mod id; UUID for the per-player count; request keys carry no player (a key that reads as a UUID is refused) | Hold each mod to its daily budget, hold one player to the daily cap, and make a repeated call by another mod harmless | Counts: the current economy day only, cleared at the day roll (at most 256 mods and 100,000 players). Request keys: 24 economy-hours, at most 50,000 | Yes, the player's own count and the day (`sibling_payouts`) | The player's count is removed |
| `milestones` | Per player: the milestones earned (first sale, first purchase, first investment, first Savings Bond, first offer accepted, a hundred sales), the ones whose progression gate is still waiting to open, and the count of settled sales up to 100 | UUID | Open a progression gate once for a player, including a milestone reached while they were offline, so quest packs can use it | Until erasure; at most 100,000 players; a player with nothing earned has no row | Yes (`milestones`) | The player's row is removed |
| `obligations` | Jobs (contracts, deliveries, bounties): id, type, issuer, claimant, target (an item, tag or entity id, or for a player bounty the target player's UUID), quantity, delivery destination, payout, expiry, state, claim time | UUID | Run the job and hold its payout | Open jobs until they settle; settled jobs for `transactionRetentionDays` after their expiry time | Yes, with the player's role (`obligations.jobs`) | See [Erasure](#erasure) |
| `obligations` | Auctions: id, seller, the lot's item stack, minimum bid, highest bid, highest bidder, end time, interplanetary flag, state | UUID | Run the auction and hold the lot and the bid | Running auctions until they settle; settled auctions for `transactionRetentionDays` after their end time | Yes, with the player's role (`obligations.auctions`) | See [Erasure](#erasure) |
| `obligations` | Bounty progress: job id, kill count | UUID | Pay a bounty only at its full quantity | Until the bounty settles, expires or is cancelled | Yes (`obligations.bounty_progress`) | Removed |
| `obligations` | System-job budget: day number and total posted | Not per player | Enforce `systemJobDailyPayoutCap` | Overwritten daily | No | Unchanged |
| `shop_owners` | Dimension and block position of each Player Shop, and its owner | UUID | Decide who may stock, price and break a shop | Until the shop block is removed or the owner is erased | Yes (`shops`) | Rows removed; the blocks stay in the world without an owner |
| `admin` | The market-freeze switch and a count per kind of operator action | Not per player | Operators' view of what has been done | Life of the world | No (a count says how often, never whose) | Unchanged |
| `gallery` | The gallery's sample board rows, and the positions and ids of the blocks and label stands it placed | Not per player | Let `gallery clear` find what to remove after a restart | Until the gallery is cleared | No | Unchanged. It holds no player identifier and never reaches the market, the Reserve, link snapshots or telemetry |
| `simulation` | Index of the last completed step and its length; the world's secret (32 random bytes); a few named whole numbers kept between steps | Not per player | Catch up after a pause; key the simulation's random streams | Life of the world | No. The secret is never exported, logged or sent to a client or the app | Unchanged |
| Recovery files: Core's backup of the combined store, and `neroeconomy:economy_marker` (records that an economy exists) | The same data as the store; the marker holds no player data | As the store | Restore a store whose file cannot be read; freeze the economy rather than start a second one | Rewritten as the store changes | Same data as the store | Refreshed in the same erasure request |
| Export files (`<world>/neroeconomy-exports/<uuid>.json`) | The export described under [Export](#export) | UUID, which is also the file name | Hand a player their data | The lesser of `transactionRetentionDays` and 7 days | It is the export | Deleted |

Each Player Shop block also stores its listing id, its price and a demo flag in the chunk. None of
those identifies a player. An Auto-Trader block stores no player identifier at all: its owner is
recorded only in the `auto_traders` section.

Every section has a hard bound or is bounded by the number of accounts times a per-account cap
(for example `maxListingsPerPlayer`, `maxOffersPerPlayer`, `maxRulesPerPlayer`, `vaultStacks`).
Nothing is kept for a player who has nothing in it. Wealth is never ranked: see
[Player and operator controls](#player-and-operator-controls).

### Transaction rows are pseudonymous

The source and destination references in a transaction row are **not** UUIDs. Each is the first 12
bytes of a SHA-256 digest of a per-world random salt plus the account's stable key, written as hex.
An empty reference means the server side of a mint or burn, or an opted-out account.

The rows are pseudonymous, not anonymous. The salt is stored in the same ledger file, so anyone who
holds that file and knows a UUID can work out that UUID's pseudonym.

A row made by a call from another mod also carries an `origin`: the mod id and a reason code that mod
chose, such as `mymod/quest_reward`. It is a fixed-shape label, never free text, and names no player.
It appears in the row in the owner's own export, so a player can see which mod paid them.

### Free text and item data

No store has a field a player can type free text into. An item stack placed in escrow (a listing, a shop's stock, an
auction lot) or waiting in a claim is stored as the game stores it, including its components. That
can include text a player typed, such as an anvil name. `maxStackDataBytes` caps how much data one
escrowed stack may carry.

### Stored block coordinates

NeroEconomy keeps no movement or location history. It stores these coordinates:

- the dimension and block position of every owned Player Shop;
- on each listing, the dimension and block position of the shop or Market Board it was posted at
  (a listing posted from the companion app records the word `link` instead);
- on each delivery job, the destination its issuer typed.

### What is never stored

Player names, chat, IP or other network addresses, inventory contents (other than items a player
put into escrow or is owed), and movement or location history.

## Data held in memory only

These are never written to disk and never sent anywhere.

| Data | Keyed by | Purpose | Cleared |
| --- | --- | --- | --- |
| Packet rate limiter: remaining request budget | UUID | Drop floods of packets and app requests | On logout and on server stop |
| Alert limiter: time of the last alert of each kind | UUID and alert kind | At most one alert of a kind per player in 30 seconds | On erasure and on server stop |
| Companion-app snapshot cache: the player's own last snapshots, at most 512 entries in total | UUID, section and query | Answer an over-budget app with what it last saw | On erasure and on server stop |
| Balance-event window: time of the player's last app request and last balance event | UUID | Send live balance updates only to an app that is in use | On erasure and on server stop; an entry is ignored 120 seconds after the last app request |
| Shop double-click memory: the game tick of a buyer's last purchase at one shop | UUID, per loaded shop | Refuse two purchases in the same tick | At the first purchase more than 100 ticks later, or when the shop unloads |
| Direct-trade requests and open trade windows: who asked whom, and the stacks on the table (the goods themselves are also recorded in the `trade_escrow` section) | UUID | Run `/trade` | A request lapses after half a minute; both are dropped on logout, on erasure and on server stop |
| Open Economy Hub state: the tab and page, the search text, the last quote shown | UUID | Show the player their own pages | When the hub closes, on logout and on server stop |
| Exchange rate limiters (real and practice): time of recent actions | Account | At most `exchangeActionsPerMinute` actions a minute | On erasure and on server stop |
| Reference-price tally for the current simulation step: the credits counted per item and per pair of accounts, the pair held only as a one-way 64-bit hash of the two UUIDs | Hash of two UUIDs | Keep two accounts trading back and forth from moving a reference price | Cleared every simulation step and on server stop |
| Order-book and rule work queues: which items changed and which rules wait to be looked at | Item id, rule id | Re-evaluate rules without scanning every tick | Emptied as they are worked through |

## Retention

| Data | Retention | Key |
| --- | --- | --- |
| Transaction rows (age) | `transactionRetentionDays` days (default 90; `0` keeps no rows at all) | `transactionRetentionDays` |
| Transaction rows (count) | Oldest rows dropped beyond `maxTransactionRecords` server-wide (default 10000; `0` keeps none). Between sweeps the list may run up to 256 rows over the cap | `maxTransactionRecords` |
| Rows shown to one player in game and in the app | `historyLimitPerPlayer` (default 100). This limits display only; it does not limit the export | `historyLimitPerPlayer` |
| Settled jobs and auctions | Kept for `transactionRetentionDays` after their expiry or end time, then removed (`0` removes them on the next pass) | `transactionRetentionDays` |
| Book orders (sell and buy) and jobs | Expire after `defaultListingDurationHours` (default 48). Goods go back to the owner's vault; escrowed credits go to the owner's credit claim | `defaultListingDurationHours` |
| Offers | Expire after `offerDurationHours` (default 48); everything goes back to the sender | `offerDurationHours` |
| Auto-trade rules | Until the owner deletes them or is erased; a buy rule's unspent budget is paid back when it is deleted | — |
| Vault contents | No time limit: kept until withdrawn or erased | — |
| Exchange holdings and bonds | Until sold, matured or erased | — |
| Practice portfolios | Dropped after 30 economy days without use | — |
| Per-account Merchant House counts | Dropped at the economy-day roll | — |
| Remote-spend rows | The current economy day only | — |
| Other mods' payout counts (per mod and per player) | The current economy day only, cleared at the day roll; the per-player count is kept only while `siblingPerPlayerDailyCap` is above 0 | `siblingPerPlayerDailyCap` |
| Other mods' applied request keys | 24 economy-hours, at most 50,000 | — |
| Milestones | Until erasure | — |
| Player Shop listings | Expire into the owner's vault after `shopListingMaxIdleDays` without an owner interaction or a sale (default 90; `0` never) | `shopListingMaxIdleDays` |
| Credit claims | No time limit: kept until collected or erased | — |
| Processed request ids | 10 minutes | — |
| Credit Chip counter | Until the day it counts has passed | — |
| Export files | The lesser of `transactionRetentionDays` and 7 days; with `transactionRetentionDays=0`, deleted at the next sweep | `transactionRetentionDays` |
| Balances, opt-out flag, shop ownership | Until erasure | — |
| Erasure tombstones | Life of the world | — |

The transaction sweep runs when the world loads, when the server starts, and daily after that. The
export-file sweep runs when the server starts and daily after that. Market, order and offer expiry
runs when the server starts, every 30 seconds while it runs and in every simulation step (every
`simStepMinutes` of economy time), so what fell due while the server was stopped is settled at the
next start.

Ages are measured on the mod's **economy clock**, not the calendar. The economy clock never runs
backwards and moves forward by at most 72 hours between two readings, so a server that was shut
down for longer than 72 hours treats the shutdown as 72 hours. Rows and deadlines can therefore
last longer in calendar time than the configured number of days. Export files are the exception:
they are aged by their file modification time.

Setting `transactionRetentionDays=0` or `maxTransactionRecords=0` turns transaction logging off for
the whole server. When the row bound is reached, the oldest ordinary rows go first; rows that record
credits created or removed by an operator or the Reserve top-up, and rows of 10 000 credits or
more, are dropped only when they alone exceed the bound.

## Transaction-logging opt-out

Any player can run `/eco privacy logging true` for their own account, and `/eco privacy logging
false` to resume. Opting out does **not** stop a row being written. From then on:

- a row is still written for each transaction, holding the reason, amount, currency and time;
- the opted-out account's side of the row is left blank, so the row no longer refers to that
  player;
- in a two-party transaction the other party's reference is kept, so their history stays complete.

Rows written before opting out keep the player's pseudonym and age out under the retention rules.
Rows written while opted out cannot be linked back to the player by this mod, so they do not
appear in `/eco history`, in the companion app or in an export, and erasure cannot find them; they
age out under the same retention rules. Balances, escrow and claims are not affected by the
setting.

## Player and operator controls

| Action | Command | Who | Notes |
| --- | --- | --- | --- |
| Opt out of, or back into, linked transaction rows | `/eco privacy logging <true\|false>` | Any player, own account | See above |
| See your own retained rows | `/eco history` | Any player | Reason, amount and time only; capped at `historyLimitPerPlayer` |
| See your own open orders, jobs and auctions | `/eco market mine` | Any player | |
| See your own vault, orders, rules, offers and holdings | The Economy Hub, or the companion app | Any player | Your own data only |
| Mute offers and trade requests | `/trade mute <true\|false>` (or `/eco offer mute <true\|false>`) | Any player | One switch for both. Removed on erasure |
| See supply totals | `/eco admin supply` | Permission level 3 | Aggregates only: circulation, Reserve, escrow, exchange, minted and burned today. Names no account |
| See your own balance | `/eco balance` | Any player | |
| Look up another player's balance | `/eco balance <player\|uuid>` | Permission level 2 | Operators can see any known player's balance |
| Change a balance | `/eco admin give\|take\|set <player\|uuid> <amount>` | Permission level 3, and `adminMutationsEnabled` | Recorded as a transaction row with an admin reason |
| Export | `/eco export <player\|uuid>` | Permission level 3 | See [Export](#export) |
| Erase | `/eco erase <player\|uuid>` | Permission level 3 | See [Erasure](#erasure) |

There is no wealth leaderboard: no command, screen, API or companion-app section ranks players by
balance or lists balances. A balance is visible to its owner, to operators as above, and to anyone
with access to the world files. Other server-side mods can read and change balances through
Neroland Core's currency API; what they do with them is governed by those mods.

## Export

`/eco export <player|uuid>` writes one JSON file to `<world>/neroeconomy-exports/<uuid>.json`,
replacing any earlier export for the same player. It covers every section of the combined store
that holds anything about the player and is not cut at `historyLimitPerPlayer`. It contains:

- `player_uuid`, `account_reference` (the player's own pseudonym), `currency`, `balance`,
  `transaction_logging_opt_out`, `handbook_given`;
- `credit_chip_redemptions`: the counter's day number and count;
- `starting_balance_vesting`: the credits of the starting balance still locked, and whether the
  account has vested;
- `transactions`: every retained row that names the player, with reason, amount, currency, time,
  source and destination. The other party of a two-sided row is replaced by the word
  `counterparty`;
- `market`: the player's shop `listings`, `credit_claim`, `credit_claim_by_reason` and
  `recent_request_ids`;
- `book`: the player's open `sell_orders` and `buy_orders`;
- `offers`: the offers the player `sent` and `received` (the other side is not named), and
  `offers_muted`;
- `rules`: the player's auto-trade rules, `rules_paused`, and what the rules filled today;
- `trade_escrow`: what the player has on the table of a direct trade that is open right now;
- `obligations`: the `jobs` and `auctions` the player takes part in, each with the player's role
  (`issuer`, `claimant`, `bounty_target`, `seller` or `bidder`), and their `bounty_progress`;
- `shops`: the dimension and block position of every shop the player owns;
- `auto_traders`: where the player's Auto-Trader blocks stand;
- `vault`: the player's vault, as item id and count;
- `exchange`: the player's shares and index units (units, cost and time bought per lot), Savings
  Bonds, last dividend, and their `practice` portfolio;
- `merchant_houses`: what the player traded with the Merchant Houses today, and what of their
  trade with other players counted toward reference prices today;
- `remote_spend`: what the player spent, and the value of goods they sent away, through the
  companion app in the current economy day;
- `sibling_payouts`: the economy day and the credits other mods paid the player in it
  (`credits_from_other_mods_today`); empty when the per-player cap is off or nothing was paid today;
- `milestones`: the milestones the player has reached, the gates still waiting to open, and the count
  of their settled sales (`settled_sales_counted`, up to 100);
- `economy_time` and `wall_time`: the economy clock and the calendar clock at the moment of export.
  Every other time in the file is economy time and can be read relative to these two.

Nobody else is identified in an export. The other side of a job, auction or offer is left out, and
a bounty the player posted on another player is exported without that player's id. Items are
exported as an item id and a count; the full item data (a custom name, enchantments, contents)
stays in the store. The world's simulation secret, the Reserve and other accounts' balances are
never part of an export.

The file is created readable and writable by the server's own user only, and its directory is
restricted in the same way, where the file system supports POSIX permissions. On other file systems
(Windows) the platform defaults apply. The file sits inside the world folder, so it is included in
any copy or backup of that folder made while it exists, and its name is the player's UUID. Handing
the file to the player, and deleting copies made elsewhere, is the operator's task.

## Erasure

`/eco erase <player|uuid>` calls Neroland Core's shared `data.PlayerDataErasure` hook, so one
request reaches every installed Nero mod, not just this one. The same hook, with the same effects,
is called by Core's own commands (in Core 1.13.0: `/neroland data eraseme`, which any player can
run for themselves, and `/neroland data erase <uuid>` at permission level 2) and by Core's
inactivity purge (see below). A player who erases themselves loses their balance, escrow and
claims exactly as described here, and Core 1.13.0's command asks for no confirmation.

Before the steps below, two small sections are cleared first (their erasers are registered ahead of
all others): the player's daily payout count from other mods (`sibling`) and their milestones
(`milestones`). Nothing else about them is held there.

NeroEconomy's part of an erasure runs in this order. Each step also refreshes Core's recovery
backup of the combined store, so the erased rows do not survive in it.

1. **Ledger.** The balance, initialised flag, starting-balance lock, handbook flag, opt-out flag,
   Credit Chip counter and every transaction row that names the player's pseudonym are removed. A
   two-party row is one row, so the other party loses it from their history too.
2. **Shop ownership.** The player's rows are removed. The shop blocks stay in the world without an
   owner, and anyone can then break them.
3. **Obligations.**
   - A job the player issued is removed, together with its escrowed payout.
   - A job the player had claimed is reopened for anyone to claim.
   - A bounty on the player is closed and its payout is refunded to the issuer's credit claim. If
     the refund cannot be booked at once, the bounty is kept under a target that names nobody until
     it expires and refunds.
   - An auction the player was selling is removed, together with the lot. A standing bid from
     another player is refunded to that bidder's credit claim first.
   - An auction on which the player holds the highest bid continues with no bid; the player's
     escrowed bid is destroyed.
   - Settled jobs that name the player, and the player's bounty progress, are removed. A settled
     auction the player won is kept with the bidder blanked.
4. **Direct trade.** A trade the player has open ends first, and the other player's goods go back
   to that player's vault. What the erased player had on the table is destroyed.
5. **Order book.** The player's sell and buy orders are removed. The goods in them are destroyed and
   the credits escrowed for their buy orders move to the Reserve. A sale already settled to another
   player is not undone: that player's proceeds stay in their claim.
6. **Offers.** Offers the player sent are removed; the goods are destroyed and the credits move to
   the Reserve. Offers sent *to* the player are refunded to their senders: the credits as a credit
   claim and the goods to the sender's vault. If a refund cannot be booked at once, the offer is
   kept under the sender's own name, with nothing of the erased player in it, until it expires.
   The player's mute flag is removed.
7. **Rules and Auto-Traders.** The player's rules are removed, and a buy rule's unspent budget moves
   to the Reserve. Their Auto-Trader blocks stay in the world without an owner.
8. **Merchant Houses.** What the Houses recorded about the player today, and their share of the
   reference-price tally, is removed.
9. **Exchange.** The cost of the player's shares, the principal of their bonds and the interest
   reserved for them move from the exchange account to the Reserve. Their holdings and their
   practice portfolio are removed, and their exchange rate limiters are cleared.
10. **Market.** The player's shop listings, credit claims and request ids are removed.
11. **Vault.** The player's vault is removed and the goods are destroyed. Prototype stacks that only
    the player's goods referred to are dropped with them.
12. **Export file.** Deleted.
13. **Companion-app spend.** The player's remote-spend row is removed.
14. **Memory.** The alert limiter, the companion-app caches, the rate limiters and any open trade
    request or Hub state for the player are cleared.

In short: everything the erased player owned in the economy is **destroyed or returned to the
Reserve**, not paid out to anyone: the balance, escrowed goods and credits, uncollected claims,
the vault, holdings and auction lots. What other players are owed because of the erasure (an
offer sent to the erased player, a bounty on them, a bid against them, the other side of a direct
trade) is **refunded** to those players.

### The erasure tombstone

Erasure leaves one thing behind in the ledger: the erased account's salted pseudonym. Without it,
the next balance read would treat the account as new and pay the starting balance again. If the
player returns, their account opens with a balance of zero. The tombstone is kept for the life of
the world and is not removed by a second erasure.

### What erasure does not reach

- Transaction rows written while the player was opted out. They hold no reference to the player;
  they age out under retention.
- Copies of the world folder or of an export file that the operator made outside the server.
- Data held by the game itself or by other mods that do not use Core's erasure hook.

### Core's inactivity purge

Neroland Core has an optional retention sweep. When Core's `dataRetentionDays` setting is above 0
(the default is 0, which turns it off), an operator at permission level 2 can run
`/neroland data purge-inactive`, which erases every player who has been inactive for longer than
that through the same hook, with the effects described above.

## Counterparties and rankings

A player is never told who the other side of a trade was, unless they addressed that person
themselves. Order-book rows, shop listings, offers and the Hub show a seller or buyer only as "a
player", "a shop" or a Merchant House. The book view in the companion app is price levels and
counts. An offer shows its recipient only to the sender, who typed the name. A direct trade is the
exception by design: both players chose each other.

There is no wealth leaderboard. No command, screen, API, alert or companion-app section ranks
players by balance, net worth, portfolio or trade volume, and `/eco admin supply` and the Admin tab
show totals, never an account.

## Server log

No log line written by NeroEconomy carries a player name, UUID, pseudonym, amount, item or
transaction. An administrative balance change logs its reason code only, at debug level. Nothing about a trade, order, offer, rule, deposit, withdrawal or share purchase is logged at info level. Warnings
about a failed export deletion, a failed companion-app request or a failing price modifier name the
operation or the exception class, never the player.

The game, the mod loader, other mods and hosting panels keep their own logs, which this mod does
not control.

## Crash telemetry

NeroEconomy embeds the Sentry client for crash reporting. Stated precisely:

- **It is inert in every published build.** The DSN (the address reports are sent to) is a
  placeholder compiled into the mod, and there is no config key for it. While the placeholder is in
  place the client is never started and nothing is sent. The rest of this section describes what a
  build with a real DSN would do.
- **On by default, opt-out.** In a build with a real DSN, reporting runs unless you set
  `telemetryEnabled=false` in `config/neroeconomy.properties`. The key defaults to `true`, is read at
  start-up (a change takes effect on restart) and is the one setting that belongs to each
  installation rather than to the server.
- **Scope.** Only failures whose stack trace includes `za.co.neroland.neroeconomy` are considered.
- **Cap.** At most 10 events per session, and one per distinct failure.
- **What an event would contain.** The mod release (`neroeconomy@<version>`), the environment name
  (`development` or `production`), a fixed operation label, the exception class, its message and
  its stack trace, plus whatever runtime information the Sentry client attaches by default.
- **Scrubbing.** The user and server name are cleared, absolute stack-frame paths are dropped, and
  home-directory paths and anything shaped like a UUID are removed from messages. A failed
  export-file operation reports only the exception class, because its real message contains the
  file path and the file name is the player's UUID. A failure thrown by another mod's currency
  listener is likewise reported by class only.
- **Not attached.** `sendDefaultPii` is off, and the mod attaches no player identifier, name,
  balance, item or transaction value. As with any network request, the receiving service would see
  the network address of the machine that sent the report.

Once a published build carries a real DSN, the store pages will carry this notice:

> **Telemetry notice:** NeroEconomy sends anonymous error reports (stack trace + mod/game versions
> only — never IPs, usernames, UUIDs, or world data) to the developers via Sentry (EU servers) so
> crashes can be fixed. On by default — opt out any time by setting `telemetryEnabled = false` in
> `config/neroeconomy.properties`. Full details:
> [PRIVACY.md](https://github.com/Neroland/neroeconomy/blob/main/PRIVACY.md).

## Companion app (NeroLink)

If NeroLink is installed, NeroEconomy registers a **player-scoped** Core Link module (link schema 2;
schema 1 keys are kept for one release). Every snapshot and action runs on the server for the
requesting player only, and a reply never names another player.

- **Sections** (read-only snapshots of the player's own data): `balance`, `transactions`, `market`,
  `summary`, `orders`, `vault`, `stocks`, `portfolio`, `offers`, `jobs`, `auctions`.
  - `balance`: the currency, the player's balance, up to 10 of their own recent rows (time, amount,
    reason code), and up to 20 balance samples worked out from their own recent rows.
  - `transactions`: the player's own retained rows, up to `historyLimitPerPlayer`: reason code,
    amount, currency, time, and a fixed description of the kind of row. No account reference,
    counterparty, item or quantity is sent; the transaction log does not store the last two.
  - `market`: open orders and shop listings: the row's id, the item id, the item's base name, the
    quantity, the unit price and whether the stack is unmodified. A row never says who posted it or
    where. A custom (renamed) item name is never sent. With `view=book`, one item's order book as
    price levels (at most 10 a side): counts only, no owner or order id.
  - `summary`, `orders`, `vault`, `offers`, `stocks`, `portfolio`: the player's own counts, orders,
    vault lines, offers (the other side shown as "A player"), prices and holdings. `vault` also
    says whether the player is in game and whether live vault actions can run now, as plain
    booleans; nothing about where the player is.
  - `jobs` and `auctions`: open jobs and auctions, and the player's own. An issuer, bidder or
    bounty target is shown as "A player".
- **Actions** (each needs a request id; each is re-validated and re-priced on the server): `trade`,
  `order_place`, `order_cancel`, `rule_set`, `rule_delete`, `rules_pause`, `stock_buy`,
  `stock_sell`, `bond_buy`, `pay`, `offer_send`, `offer_accept`, `offer_decline`, `offer_cancel`,
  `claim_collect`, `vault_deposit`, `vault_withdraw`. All can be switched off together with
  `remoteTradeEnabled=false`. All except the two vault moves work while the player is offline. A
  `pay` reply never echoes the recipient. The credits and the goods value an account may send away
  through the app are capped per economy day (`remoteDailySpendLimit`, `remoteDailyGoodsLimit`);
  the day's totals are kept in the `remote_spend` section described above.
- **Live topics:** `balance`, `orders`, `offers`, `portfolio` (each a full snapshot of the player's
  own section) and `alerts`. They are sent to that player's app at most once per second and topic,
  and only while the app has asked this module for something in the last 120 seconds.
- **Alerts.** Fourteen fixed sentences, for example "An economy job was fulfilled.", "You were
  outbid.", "You received a trade offer.", "One of your market orders was filled.", "Your vault is
  full. Take some items out before more can arrive.". An alert never contains a name, amount, item
  or place. Alerts are stored and erased by Neroland Core. Push notifications for them carry no
  amounts or item names.

Nothing about another player is sent: no name, UUID, pseudonym, balance, history or location.
Market rows are other players' orders, shown without their owners. The only thing the module keeps
on disk is the `remote_spend` row described under [What is stored](#what-is-stored); what it holds
in memory is listed under [Data held in memory only](#data-held-in-memory-only). NeroLink, its
relay and the app are separate software with their own notices.
