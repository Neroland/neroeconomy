# Configuration and administration

Core creates `config/neroeconomy.properties`. Every key is server-authoritative except
`telemetryEnabled`, which belongs to each installation. A value outside its bounds is clamped to
the nearest bound, and a value that cannot be read falls back to the default. Settings are read
each time they are used, so a change applies as soon as Core has reloaded the file.

## Keys

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `startingBalance` | 100 | 0 to 1,000,000 | Credits minted once, when a player's account is first opened. Changing it does not re-mint existing accounts. |
| `transactionRetentionDays` | 90 | 0 to 3650 | How long transaction rows, and settled jobs and auctions, are kept. `0` keeps no transaction rows at all. Export files are kept for the lesser of this and 7 days. |
| `maxTransactionRecords` | 10000 | 0 to 1,000,000 | Server-wide cap on transaction rows; the oldest are dropped first. `0` keeps none. |
| `historyLimitPerPlayer` | 100 | 1 to 1000 | Most rows shown to one player by `/eco history` and the companion app. It does not limit `/eco export`. |
| `adminMutationsEnabled` | true | true or false | Enables `/eco admin give\|take\|set` and the gallery commands, which also need permission level 3. |
| `maxPriceMultiplier` | 4.0 | 1.0 to 100.0 | Highest combined price multiplier after all shocks and modifiers. |
| `minPriceMultiplier` | 0.25 | 0.01 to 1.0 | Lowest combined price multiplier after all shocks and modifiers. |
| `executionPriceTolerance` | 0.05 | 0.0 to 1.0 | Used only by the older API method `PricingEngine.executionPriceAccepted`. Purchases ignore it: a buyer is never charged above the price they confirmed. |
| `defaultListingDurationHours` | 48 | 1 to 720 | Lifetime of Market Board listings, buy offers, contracts, deliveries and bounties. |
| `auctionAntiSnipeSeconds` | 60 | 0 to 3600 | A bid this close to the end of an auction moves the end to this many seconds after the bid. `0` turns the extension off. |
| `auctionMinIncrementPercent` | 5 | 0 to 100 | A bid must beat the standing bid by at least this percentage, and always by at least 1 credit. |
| `jobClaimTimeoutMinutes` | 120 | 0 to 10080 | A claimed job reopens after this long without being fulfilled. `0` never releases a claim. |
| `shopListingMaxIdleDays` | 90 | 0 to 3650 | A Player Shop listing with no owner interaction and no sale for this long expires into the owner's item claim. `0` never expires one. |
| `maxListingsPerPlayer` | 64 | 0 to 1024 | Open Market Board sell listings per account. Player Shop listings are not counted. `0` refuses new ones. |
| `maxBuyOffersPerPlayer` | 32 | 0 to 1024 | Open buy offers per account. `0` refuses new ones. |
| `maxJobsPerPlayer` | 16 | 0 to 256 | Unsettled contracts, deliveries and bounties one account may have posted. `0` refuses new ones. |
| `maxAuctionsPerPlayer` | 8 | 0 to 256 | Running auctions per account. `0` refuses new ones. |
| `maxClaimStacksPerPlayer` | 256 | 16 to 4096 | Uncollected item-claim stacks per account. At the cap, purchases, fills, bids and cancels that would add more are refused. Refunds, expiry and settlement always book. |
| `maxStackDataBytes` | 8192 | 256 to 1,048,576 | Largest encoded size, in bytes, of a stack that may be listed, auctioned or put in a shop. |
| `creditChipValue` | 100 | 1 to 1,000,000,000 | Credits minted by one Credit Chip. |
| `creditChipDailyLimit` | 5 | 0 to 1000 | Credit Chips one account may redeem per day. `0` disables redemption. |
| `systemJobsEnabled` | true | true or false | Allows server-funded jobs posted by other mods, whose payouts are minted. `false` refuses every new one. |
| `systemJobDailyPayoutCap` | 5000 | 0 to 1,000,000,000 | Total payout of the server-funded jobs that may be posted per day. |
| `playerBountiesEnabled` | false | true or false | Allows bounties on a named player. Posting one also needs permission level 2. |
| `remoteTradeEnabled` | true | true or false | Allows the companion app's `trade` action. |
| `telemetryEnabled` | true | true or false | Crash reporting is on by default; set `false` to opt out (local to this installation, read at start-up). Telemetry is inert in published builds; see [Privacy](Privacy-and-Erasure.md). |

Back up the world before changing economic policy on a live server.

## Blocking items from trade

Items in the item tag `neroeconomy:untradeable` cannot be listed, auctioned, put in a shop,
requested by a job or buy offer, or used to fill one. The tag is empty by default. Add items with a
datapack file at `data/neroeconomy/tags/item/untradeable.json`:

```json
{
  "replace": false,
  "values": ["minecraft:bedrock"]
}
```

## Operator commands

[Credits and commands](Credits-and-Commands.md) lists every command with its permission level. In
short: looking up another player's balance and posting a player bounty need level 2; the `admin`
commands, `export`, `erase` and the gallery need level 3.

## Export and erasure

- `/eco export <player|uuid>` writes `<world>/neroeconomy-exports/<uuid>.json`. The file covers the
  ledger, the market, jobs and auctions, and shops, and it is not cut at `historyLimitPerPlayer`.
  Where the file system supports it, the file and its directory are readable by the server's own
  user only. Export files are deleted after the lesser of `transactionRetentionDays` and 7 days,
  checked when the server starts and daily, and when the player is erased. Giving the file to the
  player is up to you.
- `/eco erase <player|uuid>` erases the player from every Nero mod through Core.

[Privacy, history, export and erasure](Privacy-and-Erasure.md) describes both in full.

## Where the data lives, and backups

NeroEconomy keeps four stores in the overworld's `data` folder: the ledger, the market, the
obligations (jobs and auctions) and the shop owners. All four are loaded through Neroland Core's
recovery guard, which keeps a last-known-good copy of each beside the primary file:

- `neroeconomy_ledger_backup.dat`
- `neroeconomy_market_backup.dat`
- `neroeconomy_obligations_backup.dat`
- `neroeconomy_shop_owners_backup.dat`

If a store's file cannot be read when it is loaded, Core restores the store from its backup instead
of starting it empty. Erasing a player rewrites the backups in the same request.

The four stores are separate files. When you restore a world from your own backup, restore all of
them from the same point in time, together with the player data, or credits and goods can go
missing or be duplicated. Restoring a backup made before a player was erased brings that player's
data back.

After a claim is collected, the economy stores are written to disk within about a second rather
than waiting for the next world save.

## The economy clock

Expiry times, auction ends, claim timeouts, retention and the Credit Chip's daily limit are all
measured on one economy clock that is saved with the ledger:

- it never runs backwards, so setting the system clock back does not stall anything;
- it moves forward by at most 72 hours between two readings, so a jump forward, or a server that
  was shut down for a long time, does not expire everything at once. After a shutdown of more than
  72 hours, only 72 hours of it count against open listings, jobs, auctions and retention.

Times shown to players and sent to the companion app are converted back to ordinary clock time.

## Logging

NeroEconomy writes no log line that contains a player name, a UUID, an amount, an item or a
transaction. An `admin` balance change logs only its reason code, at debug level. Warnings name the
operation that failed or the exception class.

## Telemetry

The mod contains a crash reporter that is inert in published builds: it has no address to report
to until a build is made with one. Once it has one, reporting is on by default and opt-out:
`telemetryEnabled=false` in `config/neroeconomy.properties` opts an installation out regardless.
[Privacy](Privacy-and-Erasure.md) describes what a report would contain.
