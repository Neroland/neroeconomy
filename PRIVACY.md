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

Four world stores hold everything. They are saved in the overworld's `data` folder. "UUID" means
the player's Minecraft account UUID. Player names are never stored.

### Inventory

| Store | Data and fields | Keyed by | Purpose | Retention | In `/eco export` | What erasure does |
| --- | --- | --- | --- | --- | --- | --- |
| Ledger (`neroeconomy:ledger`) | Balance: account kind, UUID, currency, amount | UUID | The player's credits | Until erasure | Yes (`balance`) | Removed; the credits are destroyed |
| Ledger | Initialised-account flag | UUID | Mint the starting balance once only | Until erasure | No separate field | Removed |
| Ledger | Transaction-logging opt-out flag | UUID | Remember the player's choice | Until erasure | Yes (`transaction_logging_opt_out`) | Removed |
| Ledger | Transaction rows: reason code, amount, currency, time, source reference, destination reference | Salted pseudonym, not UUID | Audit trail and the player's own history | `transactionRetentionDays` and `maxTransactionRecords` | Yes, every retained row that names the player (`transactions`) | Every row that names the player's pseudonym is deleted |
| Ledger | Credit Chip counter: day number, chips redeemed that day | UUID | Enforce `creditChipDailyLimit` | Dropped by the daily sweep once the day has passed | Yes (`credit_chip_redemptions`) | Removed |
| Ledger | Erasure tombstone: the pseudonym of an erased account | Salted pseudonym | Stop a starting balance being minted again | Life of the world | No | Created by erasure; never removed |
| Ledger | Pseudonym salt (one random value per world); economy-clock offset and last clock reading | Not per player | Compute pseudonyms; keep deadlines stable | Life of the world | No | Unchanged |
| Market (`neroeconomy:market`) | Sell listings: listing id, seller, source (board or shop), the item stack, unit price, expiry, location, last activity time, demo flag | UUID | Hold goods in escrow and sell them | Until sold, cancelled, expired or erased | Yes (`market.listings`) | Removed; the escrowed stock is destroyed |
| Market | Buy offers: offer id, buyer, item id, quantity wanted, unit price, credits in escrow, expiry, demo flag | UUID | Hold credits in escrow and buy goods | Until filled, cancelled, expired or erased | Yes (`market.buy_offers`) | Removed; the escrowed credits are destroyed |
| Market | Item claims: item stacks owed to the player | UUID | Deliver goods, refunds and returns | Until collected or erased | Yes, item id and count (`market.item_claims`) | Removed; the items are destroyed |
| Market | Credit claims: credits owed to the player, split by reason | UUID | Deliver proceeds, payouts and refunds | Until collected or erased | Yes (`market.credit_claim`, `market.credit_claim_by_reason`) | Removed; the credits are destroyed |
| Market | Processed request ids: a random id and the time it was handled | UUID | Refuse a replayed packet or app action | 10 minutes, at most 128 per player | Yes (`market.recent_request_ids`) | Removed |
| Obligations (`neroeconomy:obligations`) | Jobs (contracts, deliveries, bounties): id, type, issuer, claimant, target (an item, tag or entity id, or for a player bounty the target player's UUID), quantity, delivery destination, payout, expiry, state, claim time | UUID | Run the job and hold its payout | Open jobs until they settle; settled jobs for `transactionRetentionDays` after their expiry time | Yes, with the player's role (`obligations.jobs`) | See [Erasure](#erasure) |
| Obligations | Auctions: id, seller, the lot's item stack, minimum bid, highest bid, highest bidder, end time, interplanetary flag, state | UUID | Run the auction and hold the lot and the bid | Running auctions until they settle; settled auctions for `transactionRetentionDays` after their end time | Yes, with the player's role (`obligations.auctions`) | See [Erasure](#erasure) |
| Obligations | Bounty progress: job id, kill count | UUID | Pay a bounty only at its full quantity | Until the bounty settles, expires or is cancelled | Yes (`obligations.bounty_progress`) | Removed |
| Obligations | System-job budget: day number and total posted | Not per player | Enforce `systemJobDailyPayoutCap` | Overwritten daily | No | Unchanged |
| Shop owners (`neroeconomy:shop_owners`) | Dimension and block position of each Player Shop, and its owner | UUID | Decide who may stock, price and break a shop | Until the shop block is removed or the owner is erased | Yes (`shops`) | Rows removed; the blocks stay in the world without an owner |
| Recovery backups (`neroeconomy_ledger_backup.dat`, `neroeconomy_market_backup.dat`, `neroeconomy_obligations_backup.dat`, `neroeconomy_shop_owners_backup.dat`) | A second copy of the four stores above, with the same fields | As the store | Restore a store whose file cannot be read | Rewritten as the store changes | Same data as the stores | Refreshed in the same erasure request |
| Export files (`<world>/neroeconomy-exports/<uuid>.json`) | The export described under [Export](#export) | UUID, which is also the file name | Hand a player their data | The lesser of `transactionRetentionDays` and 7 days | It is the export | Deleted |

Each Player Shop block also stores its listing id, its price and a demo flag in the chunk. None of
those identifies a player.

### Transaction rows are pseudonymous

The source and destination references in a transaction row are **not** UUIDs. Each is the first 12
bytes of a SHA-256 digest of a per-world random salt plus the account's stable key, written as hex.
An empty reference means the server side of a mint or burn, or an opted-out account.

The rows are pseudonymous, not anonymous. The salt is stored in the same ledger file, so anyone who
holds that file and knows a UUID can work out that UUID's pseudonym.

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

## Retention

| Data | Retention | Key |
| --- | --- | --- |
| Transaction rows (age) | `transactionRetentionDays` days (default 90; `0` keeps no rows at all) | `transactionRetentionDays` |
| Transaction rows (count) | Oldest rows dropped beyond `maxTransactionRecords` server-wide (default 10000; `0` keeps none). Between sweeps the list may run up to 256 rows over the cap | `maxTransactionRecords` |
| Rows shown to one player in game and in the app | `historyLimitPerPlayer` (default 100). This limits display only; it does not limit the export | `historyLimitPerPlayer` |
| Settled jobs and auctions | Kept for `transactionRetentionDays` after their expiry or end time, then removed (`0` removes them on the next pass) | `transactionRetentionDays` |
| Market Board listings, buy offers, jobs | Expire after `defaultListingDurationHours` (default 48); escrow returns to the owner's claim | `defaultListingDurationHours` |
| Player Shop listings | Expire into the owner's item claim after `shopListingMaxIdleDays` without an owner interaction or a sale (default 90; `0` never) | `shopListingMaxIdleDays` |
| Item and credit claims | No time limit: kept until collected or erased | — |
| Processed request ids | 10 minutes | — |
| Credit Chip counter | Until the day it counts has passed | — |
| Export files | The lesser of `transactionRetentionDays` and 7 days; with `transactionRetentionDays=0`, deleted at the next sweep | `transactionRetentionDays` |
| Balances, opt-out flag, shop ownership | Until erasure | — |
| Erasure tombstones | Life of the world | — |

The transaction sweep runs when the world loads, when the server starts, and daily after that. The
export-file sweep runs when the server starts and daily after that. Market expiry runs when the
server starts and every 30 seconds.

Ages are measured on the mod's **economy clock**, not the calendar. The economy clock never runs
backwards and moves forward by at most 72 hours between two readings, so a server that was shut
down for longer than 72 hours treats the shutdown as 72 hours. Rows and deadlines can therefore
last longer in calendar time than the configured number of days. Export files are the exception:
they are aged by their file modification time.

Setting `transactionRetentionDays=0` or `maxTransactionRecords=0` turns transaction logging off for
the whole server.

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
| See your own open listings, buy offers, jobs and auctions | `/eco market mine` | Any player | |
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
replacing any earlier export for the same player. It covers every store and is not cut at
`historyLimitPerPlayer`. It contains:

- `player_uuid`, `account_reference` (the player's own pseudonym), `currency`, `balance`,
  `transaction_logging_opt_out`;
- `credit_chip_redemptions`: the counter's day number and count;
- `transactions`: every retained row that names the player, with reason, amount, currency, time,
  source and destination. The other party of a two-sided row is replaced by the word
  `counterparty`;
- `market`: the player's `listings`, `buy_offers`, `item_claims`, `credit_claim`,
  `credit_claim_by_reason` and `recent_request_ids`;
- `obligations`: the `jobs` and `auctions` the player takes part in, each with the player's role
  (`issuer`, `claimant`, `bounty_target`, `seller` or `bidder`), and their `bounty_progress`;
- `shops`: the dimension and block position of every shop the player owns;
- `economy_time` and `wall_time`: the economy clock and the calendar clock at the moment of export.
  Every other time in the file is economy time and can be read relative to these two.

Nobody else is identified in an export. The other side of a job or auction is left out, and a
bounty the player posted on another player is exported without that player's id. Items are
exported as an item id and a count; the full item data stays in the store.

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

NeroEconomy's part of an erasure runs in this order:

1. **Ledger.** The balance, initialised flag, opt-out flag, Credit Chip counter and every
   transaction row that names the player's pseudonym are removed. A two-party row is one row, so
   the other party loses it from their history too.
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
4. **Market.** The player's listings (shop listings included), buy offers, item claims, credit
   claims and request ids are removed.
5. **Export file.** Deleted.
6. **Memory.** The alert limiter and the companion-app caches for the player are cleared.

Each store's recovery backup is rewritten in the same request, so the erased rows do not survive in
it.

In short: everything the erased player owned in the economy is **destroyed**, not paid out — the
balance, escrowed stock and credits, uncollected claims and auction lots. What other players are
owed because of the erasure is **refunded** to their claims.

### The erasure tombstone

Erasure leaves one thing behind in the ledger: the erased account's salted pseudonym. Without it,
the next balance read would treat the account as new and mint the starting balance again. If the
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

## Server log

No log line written by NeroEconomy carries a player name, UUID, pseudonym, amount, item or
transaction. An administrative balance change logs its reason code only, at debug level. Warnings
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

If NeroLink is installed, NeroEconomy registers a **player-scoped** Core Link module. Every snapshot
and action runs on the server for the requesting player only.

- **`balance` snapshot.** The currency, the player's balance, up to 10 of their own recent rows
  (time, amount, reason code), and up to 20 balance samples worked out from their own recent rows.
- **`transactions` snapshot.** The player's own retained rows, up to `historyLimitPerPlayer`: reason
  code, amount, currency, time, and a fixed description of the kind of row. No account reference,
  counterparty, item or quantity is sent; the transaction log does not store the last two.
- **`market` snapshot.** Open listings and buy offers: the row's id, the item id, the item's base
  name, the quantity, the unit price and whether the stack is unmodified. A row never says who
  posted it or where. A custom (renamed) item name is never sent.
- **`trade` action.** Buys from a listing, fills a buy offer or posts a listing for the requesting
  player. It can be disabled with `remoteTradeEnabled=false`.
- **`balance` event.** The balance snapshot, sent to that player's app when their balance changes,
  at most once per second, and only while the app has asked this module for something in the last
  120 seconds.
- **Alerts.** Four fixed sentences ("An economy job was fulfilled.", "You were outbid.", "You won
  an auction.", "A player shop sold out."). An alert never contains a name, amount, item or place.
  Alerts are stored and erased by Neroland Core.

Nothing about another player is sent: no name, UUID, pseudonym, balance, history or location.
Market rows are other players' offers, shown without their owners. The module stores nothing on
disk; what it holds in memory is listed under [Data held in memory only](#data-held-in-memory-only).
NeroLink, its relay and the app are separate software with their own notices.
