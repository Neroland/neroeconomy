# Configuration and administration

Core creates `config/neroeconomy.properties`. Every key is server-authoritative except
`telemetryEnabled`, which belongs to each installation. A value outside its bounds is clamped to
the nearest bound, and a value that cannot be read falls back to the default. Settings are read
each time they are used, so a change applies as soon as Core has reloaded the file. Data that
needs a reload (commodity spreads, companies, Houses) is read again on `/reload`; see
[Data packs](Data-Packs.md).

Amounts are whole credits. "Economy day" and "economy time" are the mod's own clock; see
[The economy clock](#the-economy-clock).

## Keys

Every key the mod reads is listed below, with its default, its bounds and its meaning.

### Accounts, the Reserve and faucets

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `startingBalance` | 100 | 0 to 1,000,000 | Credits paid from the Reserve when a player account is first opened; less, or nothing, when the Reserve cannot cover it. |
| `startingBalanceVestMinutes` | 30 | 0 to 10,080 | Play time, in minutes, before a starting balance can be paid, offered or bid to another player. Until then it only pays fees and system counterparties. 0 turns the lock off. |
| `reserveInitial` | 250,000 | 0 to 1,000,000,000,000 | Credits minted into the Reserve once, when a world's economy is first created or migrated. |
| `reserveDailyMint` | 5,000 | 0 to 1,000,000,000 | Credits minted into the Reserve once per economy day; skipped while the Reserve is at or above reserveCap. At most three missed days are made up. |
| `reserveCap` | 2,000,000 | 0 to 1,000,000,000,000 | The daily top-up stops when the Reserve holds this much; fees may still take it higher. |
| `npcDailyBudget` | 20,000 | 0 to 1,000,000,000 | Most the Merchant Houses may pay out to players per economy day. |
| `exchangeDailyBudget` | 15,000 | 0 to 1,000,000,000 | Most the exchange may pay in gains, dividends and newly reserved bond interest per economy day. |
| `siblingDailyBudget` | 5,000 | 0 to 1,000,000,000 | Most that deposits made by other mods without a reason of their own may draw from the Reserve per economy day; a deposit past it is refused. |
| `systemJobsEnabled` | true | true or false | Allow server-funded jobs, whose payouts come out of the Reserve; false refuses every new one. |
| `systemJobDailyPayoutCap` | 5,000 | 0 to 1,000,000,000 | Total payout of the server-funded jobs that may be posted per economy day. The payout is moved from the Reserve into escrow when the job is posted. |
| `creditChipValue` | 100 | 1 to 1,000,000,000 | Credits the Reserve pays for an account's first Credit Chip of the economy day. Each further chip that day pays less: 80, 60, 40, then 20 percent of this. |
| `creditChipDailyLimit` | 5 | 0 to 1,000 | Credit chips one account may redeem per economy day; 0 disables redemption. |
| `creditChipDailyBudget` | 3,000 | 0 to 1,000,000,000 | Credits the Reserve pays for Credit Chips per economy day, all accounts together, on top of creditChipDailyLimit per account. A chip refused because it is spent is kept. 0 stops chip payouts. |

### Fees

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `marketFeePercent` | 2.0 | 0.0 to 50.0 | Fee on the seller's proceeds of every Market Board sale, shop sale and filled buy offer, rounded up and at least 1 credit; 0 charges nothing. Paid to the Reserve. |
| `listingFee` | 1 | 0 to 1,000 | Flat fee for posting a Market Board listing or a buy offer; not refunded. Paid to the Reserve. |
| `auctionFeePercent` | 5.0 | 0.0 to 50.0 | Fee on the winning bid, taken from the seller's proceeds, rounded up and at least 1 credit; 0 charges nothing. Paid to the Reserve. |
| `exchangeFeePercent` | 1.0 | 0.0 to 50.0 | Fee on every share and index purchase and sale, rounded up and at least 1 credit; 0 charges nothing. Savings Bonds carry no fee. Paid to the Reserve. |
| `tradeTaxPercent` | 0.0 | 0.0 to 50.0 | Tax on the credits side of a direct trade or an accepted offer, rounded up and at least 1 credit; 0 charges nothing. Paid to the Reserve. |

### Orders, vault, jobs and auctions

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `defaultListingDurationHours` | 48 | 1 to 720 | Default market offer lifetime. |
| `maxListingsPerPlayer` | 64 | 0 to 1,024 | Market Board sell listings one account may have open; 0 refuses new ones. Player Shop listings are bounded by placed shops and are not counted. |
| `maxBuyOffersPerPlayer` | 32 | 0 to 1,024 | Standing buy offers one account may have open; 0 refuses new ones. |
| `vaultStacks` | 54 | 9 to 54 | Stacks a player may keep in their vault, in whole rows of nine (a value in between is rounded down). Goods bought while away, refunds and expired listings are delivered there. Refunds and expiry always book, even past this; deposits and purchases to the vault are then refused until it is back under it. |
| `maxClaimStacksPerPlayer` | 256 | 16 to 4,096 | Stacks one account's vault may hold before purchases, bids, fills and cancels that would deliver more to it are refused. The smaller of this and vaultStacks applies, so it only matters when set below vaultStacks. Refunds and expiry always book. |
| `shopListingMaxIdleDays` | 90 | 0 to 3,650 | A shop listing with no owner interaction and no sale for this long expires into the owner's vault; 0 never expires one. |
| `maxStackDataBytes` | 8,192 | 256 to 1,048,576 | Largest encoded size, in bytes of NBT, of a stack that may be listed, auctioned or put in a shop. |
| `maxJobsPerPlayer` | 16 | 0 to 256 | Unsettled contracts, deliveries and bounties one account may have posted; 0 refuses new ones. |
| `maxAuctionsPerPlayer` | 8 | 0 to 256 | Running auctions one account may have; 0 refuses new ones. |
| `jobClaimTimeoutMinutes` | 120 | 0 to 10,080 | A claimed job reopens after this long without fulfilment; 0 never releases a claim. |
| `auctionAntiSnipeSeconds` | 60 | 0 to 3,600 | Late-bid auction extension window. |
| `auctionMinIncrementPercent` | 5 | 0 to 100 | A bid must beat the standing bid by at least this percentage, and always by 1 credit. |
| `playerBountiesEnabled` | false | true or false | Allow bounties on a named player. Posting one also requires permission level 2. |
| `maxPriceMultiplier` | 4.0 | 1.0 to 100.0 | Maximum market-shock multiplier after all modifiers. |
| `minPriceMultiplier` | 0.25 | 0.01 to 1.0 | Minimum market-shock multiplier after all modifiers. |
| `executionPriceTolerance` | 0.05 | 0.0 to 1.0 | Symmetric quote-to-execution tolerance kept for API callers of executionPriceAccepted. Purchases ignore it: a buyer is never charged above the confirmed price. |

### Direct trade, offers and auto-trade

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `tradeRangeBlocks` | 16 | 2 to 64 | How close two players must be, in blocks and in the same dimension, to trade directly with each other. Two players who are each at an economy terminal can trade from any distance. |
| `offerDurationHours` | 48 | 1 to 720 | Hours an offer sent to another player stays open. An unanswered offer then expires and everything in it goes back to the sender. |
| `maxOffersPerPlayer` | 16 | 0 to 256 | Open offers one account may have sent at a time; 0 refuses new ones. Separately, an account can have at most 64 offers waiting for its answer. |
| `maxRulesPerPlayer` | 8 | 0 to 64 | Auto-buy and auto-sell rules one account may have; 0 refuses new ones. |
| `ruleDailyValueCap` | 10,000 | 0 to 1,000,000,000 | Credits' worth of trades all of one account's rules together may fill per economy day. Reaching it pauses that account's rules until the next day. |

### Simulation and Merchant Houses

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `autonomousEconomy` | true | true or false | Run the parts of the simulation that act in the market on their own (Merchant Houses, the exchange, system jobs). false leaves only housekeeping: the Reserve top-up, the day roll-over and expiry. |
| `simStepMinutes` | 10 | 1 to 1,440 | Length of one simulation step, in minutes of economy time. The economy advances in these steps whether or not anyone is online; it never follows game ticks, the day/night cycle, /tick, /time or sleeping. |
| `simCatchUpMaxHours` | 72 | 1 to 720 | After a pause or a shutdown the missed steps are replayed, a few per tick. At most this many hours' worth are replayed; anything older is skipped. |
| `commoditySpreadPercent` | 10.0 | 2.0 to 75.0 | How far above a commodity's reference price the Merchant Houses sell it and how far below they buy it, as a percentage, for a commodity whose data file gives no spread_percent. Applies from the next data-pack reload. |
| `arbitrageGuardDepth` | 3 | 1 to 6 | How many recipes long a chain the arbitrage guard follows when it checks that goods bought from the Merchant Houses cannot be crafted and sold back to them at a profit. |
| `referenceDriftStepPercent` | 1.0 | 0.0 to 10.0 | The most a commodity's reference price may move toward what players pay each other in one simulation step, as a percentage of the reference price in its data file. 0 keeps every reference price where the data puts it. |
| `referenceBandMinPercent` | 50.0 | 1.0 to 100.0 | The lowest a commodity's reference price may drift to, as a percentage of the reference price in its data file. |
| `referenceBandMaxPercent` | 200.0 | 100.0 to 1000.0 | The highest a commodity's reference price may drift to, as a percentage of the reference price in its data file. |
| `referenceMinTradeValue` | 16 | 0 to 1,000,000,000 | A sale between two players worth less than this many credits does not count toward reference prices. |
| `referenceAccountDailyCap` | 2,000 | 0 to 1,000,000,000 | Credits of one player account's sales and purchases with other players that count toward reference prices per economy day, so a few accounts trading with each other cannot move a price far. 0 counts none. |
| `referenceMinPairs` | 3 | 1 to 64 | How many different pairs of players must have traded a commodity with each other since the last simulation step before its reference price moves at all, so two accounts trading with each other cannot move it. No one pair counts for more than the pair that ranks this many places from the top. |
| `referenceDriftDayPercent` | 10.0 | 0.0 to 100.0 | The most a commodity's reference price may move in one economy day, up or down from where the day began, as a percentage of the reference price in its data file. 0 keeps every reference price where the day began. |
| `npcAuctionsPerDay` | 4 | 0 to 64 | Auctions the Merchant Houses start from their own stock per economy day, server-wide: a few rare or valuable goods, or a bundle of a staple. 0 switches them off. |
| `npcAuctionHours` | 24 | 1 to 168 | How long, in hours of economy time, an auction a Merchant House starts runs. |
| `houseJobPremiumPercent` | 15.0 | 0.0 to 100.0 | What a Merchant House's contract or delivery job pays above the House's current buying price for the goods, as a percentage. The payout comes from the Reserve within systemJobDailyPayoutCap. |
| `houseJobsPerHouse` | 2 | 0 to 8 | Open contract and delivery jobs one Merchant House may have for goods it is short of; 0 switches House jobs off. |
| `houseJobHours` | 24 | 1 to 168 | How long, in hours of economy time, a Merchant House's job or a server bounty stays open. An expired one returns its payout to the Reserve. |
| `systemBountiesPerDay` | 2 | 0 to 32 | Server-funded bounties on hostile mobs (listed in the bounty data files) posted per economy day, and the most open at once. Mob farms complete any kill bounty, so keep this small; 0 switches them off. |
| `debugFastSim` | false | true or false | Development only: one simulation step per second of real time. Ignored, with a warning in the log, outside a development environment. |

### The exchange

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `exchangeEnabled` | true | true or false | Run the exchange: the prices of the listed companies move once per simulation step, and shares, the index fund and Savings Bonds can be bought and sold. false leaves every price where it is and refuses every purchase and sale; a Savings Bond can still be cashed in and still pays out at maturity. autonomousEconomy=false stops the prices and the dividends, but not trading. |
| `exchangeStepLimitSteadyPercent` | 2.0 | 0.1 to 50.0 | Most a steady share's price may move in one simulation step, as a percentage. |
| `exchangeStepLimitRiskyPercent` | 6.0 | 0.1 to 50.0 | Most a risky share's price may move in one simulation step, as a percentage. |
| `exchangeDayLimitSteadyPercent` | 10.0 | 0.5 to 90.0 | Most a steady share's price may be above or below its price at the start of the economy day, as a percentage. A share that gets there is halted. |
| `exchangeDayLimitRiskyPercent` | 40.0 | 0.5 to 90.0 | Most a risky share's price may be above or below its price at the start of the economy day, as a percentage. A share that gets there is halted. |
| `exchangeHaltSteps` | 6 | 0 to 1,008 | Simulation steps a share is halted for after reaching its daily limit: its price stands still and it cannot be bought or sold. 6 steps are one hour at the default step length. 0 never halts. |
| `exchangeReversionPercent` | 0.05 | 0.0 to 50.0 | How much of the gap between a share's price and its anchor closes in one simulation step, as a percentage. Higher keeps prices closer to their trend. |
| `exchangeVolumeNudgeCapPercent` | 0.5 | 0.0 to 5.0 | Most that trade in a company's linked items may move its share price in one simulation step, up or down, as a percentage. 0 turns the link off. |
| `exchangeVolumeScale` | 2,000 | 1 to 1,000,000,000 | Credits of counted trade above (or below) the usual level, in one simulation step, that move a share price by the whole of exchangeVolumeNudgeCapPercent. |
| `exchangeVolumePairCap` | 250 | 0 to 1,000,000,000 | Credits of trade between the same two player accounts that count toward share prices per simulation step, so two accounts trading back and forth cannot move a price. |
| `exchangeVolumeAccountCap` | 1,000 | 0 to 1,000,000,000 | Credits of one player account's trade that count toward share prices per simulation step. |
| `exchangeShockStepPercent` | 1.0 | 0.0 to 10.0 | How much of an active market shock on a company or its linked items reaches its share price in one simulation step, as a percentage. 0 keeps shocks out of the exchange. |
| `exchangeFloorPercent` | 5.0 | 0.0 to 100.0 | A share price never falls below this percentage of the company's start price, nor below 0.01 credit. |
| `bondRate1DayPercent` | 0.8 | 0.0 to 100.0 | Interest a 1-day Savings Bond pays for its whole term, as a percentage of what was put in. |
| `bondRate3DayPercent` | 3.0 | 0.0 to 100.0 | Interest a 3-day Savings Bond pays for its whole term, as a percentage of what was put in. |
| `bondRate7DayPercent` | 8.0 | 0.0 to 100.0 | Interest a 7-day Savings Bond pays for its whole term, as a percentage of what was put in. |
| `exchangeSpreadSteadyPercent` | 1.0 | 0.1 to 20.0 | Gap between what a steady share is bought at and what it is sold at, as a percentage of its price: half goes on top of the price when buying and half comes off it when selling. Buying and selling at once therefore always loses. |
| `exchangeSpreadRiskyPercent` | 2.0 | 0.1 to 20.0 | The same gap for a risky share. |
| `exchangeSpreadIndexPercent` | 1.0 | 0.1 to 20.0 | The same gap for the index fund. |
| `shareHoldMinutes` | 60 | 0 to 10,080 | Minutes of economy time before shares or index units that were just bought can be sold. 0 turns the holding period off. |
| `maxPositionPerStock` | 5,000 | 0 to 1,000,000,000 | Most credits one account may have in one stock or in the index fund, counted at what was paid for the units still held; 0 refuses every purchase. |
| `maxPortfolio` | 20,000 | 0 to 1,000,000,000 | Most credits one account may have on the exchange altogether - shares, index fund and Savings Bonds - counted at what was paid in. |
| `maxBondsPerPlayer` | 8 | 0 to 64 | Savings Bonds one account may hold at a time; 0 refuses new ones. |
| `practiceStartingCredits` | 10,000 | 0 to 1,000,000,000 | Play credits a practice portfolio starts with and is reset to. They are not credits: nothing converts between practice and the real economy. |
| `exchangeActionsPerMinute` | 20 | 1 to 600 | Buys, sells and bond actions one account may make on the exchange per minute; practice is counted separately, at the same rate. |

### Companion app

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `remoteTradeEnabled` | true | true or false | Allow validated NeroLink trade actions. |
| `remoteDailySpendLimit` | 5,000 | 0 to 1,000,000,000,000 | Credits one account may spend per economy day through companion-app actions, all of them together: purchases, buy-order escrow, rule budgets, shares, bonds, payments and offers. It is the most a stolen app token can cost a player in a day. 0 allows no spending from the app. |
| `remoteDailyGoodsLimit` | 5,000 | 0 to 1,000,000,000,000 | Credits' worth of goods that may leave one account per economy day through companion-app actions, all of them together: goods in offers, sell orders and sell rules from the vault, goods an accepted offer asks for. Counted apart from remoteDailySpendLimit, so the two together are the most a stolen app token can cost a player in a day. 0 lets no goods leave through the app. |
| `remoteGoodsFloorValue` | 10 | 1 to 1,000,000,000 | Credits one unit of unmodified goods that are no commodity counts as against remoteDailyGoodsLimit. A commodity counts at its reference price. |
| `remoteModifiedGoodsValue` | 500 | 1 to 1,000,000,000 | Credits one unit of modified goods (renamed, enchanted, or holding contents such as a filled shulker box) counts as against remoteDailyGoodsLimit, or its item's reference price if higher. |

### Privacy and records

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `transactionRetentionDays` | 90 | 0 to 3,650 | Audit retention in days; 0 keeps no records. |
| `maxTransactionRecords` | 10,000 | 0 to 1,000,000 | Hard server-wide audit record bound. |
| `historyLimitPerPlayer` | 100 | 1 to 1,000 | Maximum records returned to one player. |

### Operators, onboarding and telemetry

| Key | Default | Bounds | Meaning |
| --- | ---: | --- | --- |
| `adminMutationsEnabled` | true | true or false | Allow the permission-level-3 grant/take/set commands, accepting a fresh start for a frozen economy, and the admin gallery. |
| `giveHandbookOnFirstJoin` | true | true or false | Give each player one Trader's Handbook the first time they join (never in spectator mode, never twice); with a full inventory it goes to their vault. The book is craftable either way. |
| `telemetryEnabled` | true | true or false | Send anonymous NeroEconomy-only crash reports (Sentry, EU servers). On by default; set false to opt out (client-local, takes effect on restart). No player data is sent. See PRIVACY.md. |
Back up the world before changing economic policy on a live server.

## Blocking items from trade

Items in the item tag `neroeconomy:untradeable` cannot be listed, auctioned, put in a shop,
requested by a job or buy order, offered, traded, deposited into the vault, or used to fill any of
these. The tag is empty by default. Add items with a datapack file at
`data/neroeconomy/tags/item/untradeable.json`:

```json
{
  "replace": false,
  "values": ["minecraft:bedrock"]
}
```

## Operator commands

[Credits and commands](Credits-and-Commands.md) lists every command with its permission level. In
short: looking up another player's balance and posting a player bounty need level 2; the `admin`
commands, `export`, `erase` and the gallery need level 3. Everything that changes the economy under
`admin` also needs `adminMutationsEnabled=true`.

| Command | What it does |
| --- | --- |
| `/eco admin give\|take\|set <player\|uuid> <amount>` | Changes a balance. `give` and `set` upward create credits, `take` and `set` downward remove them. Recorded under a fixed admin reason and counted on the Admin tab. |
| `/eco admin supply` | Circulation, Reserve, escrow, exchange, and credits minted and burned today. Aggregates only; names no account. |
| `/eco admin stocks`, `/eco admin houses` | Aggregate views of the exchange and the Merchant Houses. |
| `/eco admin reload-check` | Re-reads the data and re-runs the arbitrage guard, listing every skipped file, clash and failing commodity. |
| `/eco admin health` | Shows whether the economy store is healthy or frozen. `/eco admin health accept` accepts a fresh start for a frozen economy. |
| `/eco admin market close` / `open` | Closes the market (player trading, the Houses and the exchange refuse; reads, collecting and refunds continue) or reopens it. |
| `/eco admin sim` | Shows how far the simulation has got. `/eco admin sim step [n]` runs steps by hand. |
| `/eco export <player\|uuid>`, `/eco erase <player\|uuid>` | Data export and erasure; see below. |

The **Admin** tab of the Economy Hub (level 3) shows the same views and the counts of operator
actions. Operators are outside what a mod can defend against; see [Fair play](Fair-Play.md).

## Export and erasure

- `/eco export <player|uuid>` writes `<world>/neroeconomy-exports/<uuid>.json`. The file covers every
  section of the economy store that holds anything about the player, and it is not cut at
  `historyLimitPerPlayer`. Where the file system supports it, the file and its directory are
  readable by the server's own user only. Export files are deleted after the lesser of
  `transactionRetentionDays` and 7 days, checked when the server starts and daily, and when the
  player is erased. Giving the file to the player is up to you.
- `/eco erase <player|uuid>` erases the player from every Nero mod through Core. Everything the
  player owned in the economy is destroyed or returned to the Reserve; what other players are owed
  because of it is refunded to them.

[Privacy, history, export and erasure](Privacy-and-Erasure.md) describes both in full.

## Where the data lives, and backups

NeroEconomy keeps **one** store in the overworld's `data` folder, `neroeconomy:economy`, and a tiny
marker store (`neroeconomy:economy_marker`) that records that an economy exists. The store holds
every section: the ledger, the market (shop listings, credit claims, request ids), obligations (jobs
and auctions), shop owners, goods, the vault, the order book, offers, rules, direct-trade escrow,
Auto-Trader owners, the exchange, the Merchant Houses, the admin counts, the gallery's sandbox, the
companion app's spend and the simulation. The whole economy is one saved-data file, so a save can
never persist half an operation.

The store is loaded through Neroland Core's recovery guard, which keeps a last-known-good copy
beside the primary file. If the file cannot be read when it is loaded, Core restores it from the
backup instead of starting empty. Erasing a player refreshes the backup in the same request.

- **Upgrading from 0.2.** The four 0.2 stores (ledger, market, obligations, shop owners) are read once,
  adopted as sections of the new store, and emptied on disk only after the new store has been saved
  and read back, so a crash during the first save cannot lose both copies. Item claims become vault
  contents, Market Board listings and buy offers become orders on the book, and credit escrow that
  0.2 had written off the ledger is restored to the escrow account. See the changelog.
- **Freezing.** If the marker says an economy exists but the store is missing or unreadable (and no
  backup could be restored), or the store was written by a newer version, the economy **freezes**:
  every value-moving operation is refused and operators are told. Nothing is written over the file
  or the backup. Restore the store and restart, or run `/eco admin health accept` to start fresh.
- **Restoring a world.** When you restore a world from your own backup, restore the whole world
  folder from one point in time, with the player data, or credits and goods can go missing or be
  duplicated. Restoring a backup made before a player was erased brings that player's data back.
- **Saving.** The economy store is saved with the game's own save: players, chunks and saved data
  together, on autosave, `/save-all` and a clean shutdown. Items moved between an inventory and the
  store are therefore as safe as the items in any inventory. A hard kill loses what happened
  since the last save, and the player's data and the store roll back together, so an item is left in
  exactly one place. Only a crash in the instant of a save carries the same tiny risk as any item in
  vanilla Minecraft.

## The economy clock

Expiry times, order and offer lifetimes, auction ends, claim timeouts, retention, the Credit Chip's
daily limit and the whole simulation are measured on one economy clock that is saved with the
ledger:

- it never runs backwards, so setting the system clock back does not stall anything;
- it moves forward by at most 72 hours between two readings, so a jump forward, or a server that was
  shut down for a long time, does not expire everything at once. After a shutdown of more than 72
  hours, only 72 hours of it count against open orders, jobs, auctions and retention.

The simulation replays the steps it missed after a pause or shutdown, a few per tick, up to
`simCatchUpMaxHours`. Times shown to players and sent to the companion app are converted back to
ordinary clock time.

## Logging

NeroEconomy writes no log line that contains a player name, a UUID, a pseudonym, an amount, an item
or a transaction, and nothing about a trade at info level. An `admin` balance change logs only its
reason code, at debug level. Warnings name the operation that failed or the exception class.

## Telemetry

The mod contains a crash reporter that is inert in published builds: it has no address to report
to until a build is made with one. Once it has one, reporting is on by default and opt-out:
`telemetryEnabled=false` in `config/neroeconomy.properties` opts an installation out regardless.
[Privacy](Privacy-and-Erasure.md) describes what a report would contain.
