# Autonomous economy

NeroEconomy runs a living economy that keeps going whether or not anyone is online: a Reserve that
backs every system payout, Merchant Houses that buy and sell, an exchange with moving prices, and
server-funded jobs, auctions and bounties. This page explains where the credits come from and the
rules that stop the system from being farmed.

## Where credits come from: the Reserve

Money never leaves the ledger. Escrow, claims, fees, the Houses' working capital and the exchange's
principal are balances of **system accounts**, not numbers written off and written back.

| Account | Holds |
| --- | --- |
| `player:<uuid>` | A player's spendable balance |
| `system:reserve` | The pool every system payout comes from and every sink pays into |
| `system:escrow` | Credits promised but not yet delivered: buy-order escrow, credit claims, job payouts, standing auction bids, rule budgets, offer credits |
| `system:exchange` | Principal of open share and bond positions, and interest reserved for bonds |
| `system:house/<id>` | A Merchant House's working capital |

Three checks hold at all times, and `/eco admin health` reports them: the sum of every balance equals
minted minus burned; the escrow account equals the sum of every escrow obligation; and the exchange
account equals open principal plus reserved bond interest.

**Only a few things create credits:** the Reserve's initial fill, its small daily top-up, the
one-time migration of escrow that existed before 0.3, and an operator's explicit grant (`/eco admin
give` and `set`). Everything else is a transfer with a typed reason. Credits are removed only by
operator `take` and `set`.

| Key | Default | Meaning |
| --- | ---: | --- |
| `reserveInitial` | 250,000 | Minted once when the economy is first created or migrated |
| `reserveDailyMint` | 5,000 | Minted once per economy day, skipped while the Reserve is at or above its cap. At most three missed days are made up |
| `reserveCap` | 2,000,000 | The top-up stops here; sinks may still push the Reserve higher |
| `npcDailyBudget` | 20,000 | Most the Merchant Houses may pay out to players per day |
| `exchangeDailyBudget` | 15,000 | Most the exchange may pay in gains, dividends and newly reserved bond interest per day |
| `siblingDailyBudget` | 5,000 | Most that deposits from other mods without a reason of their own may draw per day |
| `systemJobDailyPayoutCap` | 5,000 | Most server-funded jobs, auctions-for-jobs and bounties may escrow per day |

- A single payout that the Reserve or its daily budget cannot cover is **refused before anything
  moves**, and the player is told why in plain words.
- A batch payout (dividends, bond interest) is **scaled down pro rata** when the budget is short;
  the screen shows the factor.
- Another mod's untyped `CurrencyApi.deposit` is a transfer from the Reserve counted against
  `siblingDailyBudget`, and returns `false` when the budget or the Reserve is empty. An untyped
  `withdraw` is a transfer into the Reserve.
- Erasing a player moves their balance, escrow and exchange principal to the Reserve. Their goods
  are destroyed.
- Operators see circulation, Reserve, escrow, exchange, and minted and burned today with
  `/eco admin supply` and on the Admin tab. These are aggregates; no account is named.

### Faucets under control

- **Starting balance** (`startingBalance`, 100) is paid from the Reserve, so a new account gets what
  the Reserve can pay, possibly nothing. It is **locked** until the account has
  `startingBalanceVestMinutes` (30) of play time: locked credits can be spent with system
  counterparties (Merchant Houses and fees) but cannot be paid, offered, traded or used to buy from
  another player. Shares, the index fund and Savings Bonds need earned credits: buying them with
  locked credits is refused with a plain message, so the lock cannot be laundered through the
  exchange.
- **Credit Chip**: `creditChipDailyLimit` redemptions per account per day, with the value stepping
  down across the day (100 %, 80 %, 60 %, 40 %, 20 % of `creditChipValue`), paid from the Reserve and
  limited to `creditChipDailyBudget` credits a day for all accounts together.
- **System jobs** escrow their payout from the Reserve when posted and are refused when the daily cap
  or the Reserve cannot cover them. An expired one returns its payout to the Reserve.

### Sinks

Fees return credits to the Reserve; see [Trading](Trading.md) for the table and the rounding rules.
A price modifier from another mod changes only the buyer's price, and a discount can never exceed
the fee collected on the same sale.

## Merchant Houses

A **Merchant House** is a data-defined trader (see [Data packs](Data-Packs.md)). Each simulation
step, a House refreshes its orders on the order book: it sells from a slowly restocked stock and
buys within its working capital. A House appears in searches as a House, never as a player.

- **Prices.** A commodity has a reference price. A House sells at the reference plus the spread and
  buys at the reference less the spread (10 % by default, `commoditySpreadPercent`). Its ask is
  always above its own bid.
- **Pressure.** Buying from a House raises its ask and selling to it lowers its bid, by
  `elasticity × units / daily cap`. The pressure decays by the commodity's `recovery` each step.
- **Caps.** There is a daily supply and demand cap for each commodity across all players, and one
  per account. When a cap is reached the House stops buying or selling that commodity until the next
  economy day.
- **Money.** A House is funded from the Reserve within `npcDailyBudget`, and anything it holds above
  its working capital is swept back. Houses never mint.
- **Stock.** Restocking creates goods; goods a House buys beyond its stock cap are destroyed (a sink).

### Reference prices

A commodity's reference price drifts toward what players actually pay each other. Each step, the
volume-weighted price of player-to-player fills pulls the reference toward it, but:

- only counted trade moves it: a sale worth less than `referenceMinTradeValue` (16 credits) does not
  count, nor does a self-match or a House fill, and one account's trade counts only up to
  `referenceAccountDailyCap` (2000 credits) a day;
- the reference moves only when at least `referenceMinPairs` (3) different pairs of accounts
  traded, and no pair counts for more than the pair ranked third, so two accounts cannot wash-trade a
  price (the pairs are kept in memory only, as one-way hashes);
- a step moves it by at most `referenceDriftStepPercent` (1 %) of the data reference, within a band of
  `referenceBandMinPercent` to `referenceBandMaxPercent` (50 % to 200 %), and within a day it stays
  within `referenceDriftDayPercent` (10 %) of where the day began.

### Auctions, jobs and bounties

Each step also runs:

- **Auctions.** About `npcAuctionsPerDay` (4) times a day, never more, a House auctions a lot from its
  own stock for `npcAuctionHours` (24). The minimum bid is what the House would pay at its reference
  price. A lot of space goods is interplanetary. The goods return to stock if nobody bids.
- **House jobs.** A House short of a commodity it buys posts a contract for it, paying the
  quantity times its current bid plus `houseJobPremiumPercent` (15 %). At most `houseJobsPerHouse` (2)
  are open per House, for `houseJobHours` (24).
- **Server bounties.** About `systemBountiesPerDay` (2) times a day, a server-funded bounty on a
  hostile mob from the bounty data. Mob farms complete any kill bounty, so keep this small.

## The simulation clock

The economy advances in fixed steps (`simStepMinutes`, 10) of **economy time**, which follows the
wall clock and never depends on game ticks, day/night, `/tick`, `/time` or sleeping.

- **Empty server.** Steps are counted in economy time. After a pause or a shutdown, the missed steps
  are replayed a few per tick, never more than `simCatchUpMaxHours` (72) hours' worth. A forward jump
  within that cap is indistinguishable from real time passing, and a jump backwards never makes time
  run backwards.
- A restart in the middle of a catch-up carries on where it stopped; a step never runs twice.
- Randomness comes from a per-world secret kept on the server and never sent anywhere.
- `autonomousEconomy=false` leaves only housekeeping: the Reserve top-up, the day roll-over and
  expiry. `/eco admin sim` shows where the simulation has got to; `/eco admin sim step [n]` runs
  steps by hand (needs `adminMutationsEnabled`).

## The arbitrage guard

The guard walks every recipe it can read (vanilla types and the generic machine shapes, plus
data-defined conversions) up to `arbitrageGuardDepth` (3) recipes deep. It fails a commodity when
any chain turns goods a House **sells** into goods a House **buys** at a profit at reference prices
including the spread, or when a House's ask is not above its bid. A commodity that fails has its
buy side switched off until the data is fixed. It runs after every data load, daily at the drifted
prices, in the unit tests against the shipped data, and from `/eco admin reload-check`.

## Freezing

If the economy store is missing or unreadable (or was written by a newer version), the economy
**freezes**: every value-moving operation is refused and operators are told. `/eco admin health`
shows the state, and a level-3 operator can accept a fresh start. Separately, `/eco admin market
close` closes the market: player trading, the Houses and the exchange refuse, while reading,
collecting and refunds carry on; `open` reopens it. See
[Configuration and administration](Configuration-and-Administration.md).
