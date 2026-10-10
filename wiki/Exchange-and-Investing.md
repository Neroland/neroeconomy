# Exchange and investing

The exchange lets you put credits into fictional companies, an index fund and Savings Bonds. It is
a game system with fictional companies, not financial advice. The exchange is always your
counterparty: you buy from it and sell to it, and nothing you buy or sell moves a price.

Open it at an **Exchange Terminal** (the Invest tab of the Economy Hub), from the Hub anywhere with
`/eco hub invest`, or use the `/eco invest` commands. The companion app can buy and sell too.

## What you can buy

| Product | What it is | Risk |
| --- | --- | --- |
| **Steady shares** | Companies whose price moves slowly. Most pay a dividend. | Small moves; a losing week is rare but possible. |
| **Risky shares** | Companies whose price moves a lot, up or down. None pay a dividend. | Large moves either way. |
| **Index fund** | A basket of every listed company, weighted equally. | Averages out the companies; it still moves. |
| **Savings Bonds** | You lend credits for 1, 3 or 7 days and are paid fixed interest. | Cannot lose, but cashing in early forfeits the interest. |

Twelve fictional companies ship with the mod. Server owners and data packs can add, change or
remove companies; see [Data packs](Data-Packs.md).

| Company | Sector | Type | Start price | Dividend (per week) |
| --- | --- | --- | ---: | ---: |
| Greenfield Farms | farming | steady | 45 | 2.0 % |
| Lanternleaf Decor | decor | steady | 35 | 2.0 % |
| Deepcore Mining Co. | mining | steady | 120 | 1.5 % |
| Kindlewick Power | energy | steady | 150 | 1.5 % |
| Ductway Freight | logistics | steady | 70 | 1.0 % |
| Settlers' Row Supply | colonies | steady | 95 | 1.0 % |
| Bloomspice Traders | farming | risky | 28 | none |
| Glimmerstone Prospecting | mining | risky | 310 | none |
| Redpulse Dynamics | energy | risky | 85 | none |
| Farside Salvage | space | risky | 210 | none |
| Skylantern Launchworks | space | risky | 480 | none |
| Wildwhisker Menagerie | creatures | risky | 60 | none |

## How a price moves

The economy advances in steps of `simStepMinutes` (10 minutes by default, so 144 steps a day) of
**economy time**. It does not depend on game ticks, the day/night cycle, `/time` or sleeping, and
it catches up after a pause or a shutdown. Prices move once per step. The same inputs always give
the same price, and the next price cannot be worked out from anything a player can see: it depends
on a per-world secret that is kept on the server and never sent to a client or the app.

For one company, with `price` and `anchor` from the step before (a step is `stepMillis` long):

```text
μ       = ln(1 + drift / 100) × stepMillis / one week     the anchor grows by its drift
σ       = volatility / 100 × sqrt(stepMillis / one day)   the size of the random move
anchor' = anchor × e^μ
x       = ln(price / anchor)                              how far the price is from its anchor
x'      = x × (1 − reversion)                             the gap closes a little each step
        + σ × z                                           z: a random number drawn from a keyed hash
        + ln(1 + nudgeCap × v)                            v in [−1, 1]: traded volume (see below)
        + shockGain × ln(shock)                           an active market shock
price'  = anchor' × e^x'                                  then the limits below are applied
```

- `drift` is the company's expected growth in percent per week and `volatility` the typical daily
  move in percent; both come from the company's data file.
- `reversion` is `exchangeReversionPercent` (0.05 % of the gap closes per step).
- **Volume.** `v` compares the credits of counted player trade in the company's linked items
  (trades on the order book, between players and with the Merchant Houses) since the last step with that
  company's usual level. More trade than usual lifts the price, less lowers it, and steady trade
  does nothing. The most it can move a price in one step is `exchangeVolumeNudgeCapPercent`
  (0.5 %). Counted trade is capped per pair of accounts (`exchangeVolumePairCap`, 250 credits a
  step) and per account (`exchangeVolumeAccountCap`, 1000 credits a step), so two accounts trading
  back and forth cannot move a price. Trading on the exchange itself never counts.
- **Shock.** An active market shock on the company or its linked items (set by other mods through
  the API) reaches the price by `exchangeShockStepPercent` (1 %) of its size per step.

### The limits, in this order

1. **Per step.** At most 2 % (steady) or 6 % (risky) from the price before.
2. **Per day.** At most 10 % (steady) or 40 % (risky) from the price at the start of the economy day.
3. **Floor.** Never below 5 % of the company's start price, and never below 0.01 credit.
4. **Halt.** A price that reaches its daily limit is **halted** for 6 steps (one hour at the default
   step length). It does not move and it cannot be bought or sold. `exchangeHaltSteps` changes
   this; `0` never halts.

Each of these is a config key; see [Configuration](Configuration-and-Administration.md).

## Buying and selling

Prices are kept in hundredths of a credit and every rounding goes against the trader.

```text
ask   = ceil (price × (1 + spread / 2))     what a buyer pays per unit
bid   = floor(price × (1 − spread / 2))     what a seller receives per unit
cost     of n units = ceil (n × ask)        whole credits
proceeds of n units = floor(n × bid)        whole credits
fee   on a total    = ceil (total × fee %)  at least 1 credit while a fee applies
```

- The **spread** is the gap between ask and bid: 1 % for steady shares, 2 % for risky shares and
  1 % for the index fund (`exchangeSpreadSteadyPercent`, `exchangeSpreadRiskyPercent`,
  `exchangeSpreadIndexPercent`). Half goes on top of the price when you buy and half comes off when
  you sell.
- The **fee** is `exchangeFeePercent` (1 %), charged on every share and index purchase and sale.
  Savings Bonds carry no fee. Fees go to the Reserve.
- Buying and selling at once therefore always loses the spread and both fees. Splitting an order
  into smaller ones never does better than the whole order.
- You can buy by **units**, or by a **credit amount** (the most whole units that amount covers, fee
  included). A confirm step shows exactly what leaves your balance and what you receive.

### Principal and gains

Your credits go into the exchange as **principal**, and a sale returns the principal in full when
the units fetch at least what they cost. A **gain** (proceeds above what you paid) is paid by the
Reserve, within `exchangeDailyBudget` (15,000 credits a day by default). A **loss** goes to the
Reserve. If the Reserve cannot pay a gain in full, the sale is refused unless you accept the exact
reduced amount, which is quoted to you first; the principal always comes back in full.

### Limits

| Limit | Key | Default |
| --- | --- | ---: |
| A lot cannot be sold for this long after it was bought | `shareHoldMinutes` | 60 minutes |
| Most credits in one stock or in the index fund, at cost | `maxPositionPerStock` | 5,000 |
| Most credits on the exchange altogether (shares, index and bonds) | `maxPortfolio` | 20,000 |
| Savings Bonds held at once | `maxBondsPerPlayer` | 8 |
| Buys, sells and bond actions per minute per account | `exchangeActionsPerMinute` | 20 |

An account can hold at most 8 lots younger than the holding period at once per stock; further
purchases wait until some have aged. Every limit applies in game and from the app.

## Dividends and splits

- **Dividends** are paid once per economy day, at the first step of the day, to units held for a
  full day. For each holding, a day's dividend is `units × price × yield / 7`, where the yield is
  the company's weekly dividend in percent, rounded down. The whole batch is paid by the Reserve
  within `exchangeDailyBudget`; when that is short, everyone is paid the same smaller share and the
  screen shows the factor.
- **Splits.** At the roll into a new economy day, a share priced at 2000 credits or more splits
  10-for-1, and the index fund splits at 20,000. The price falls by a tenth and your units are
  multiplied by ten, at the cost you had. Nothing is gained or lost.
- **Delisting.** If a company's data file is removed, its holders are paid out at its last bid
  price, whatever the age of their lots. The principal always comes back; a gain is paid as far as
  the Reserve allows.

## The index fund

The index fund is a basket of every listed company, equal-weighted. Each step it moves by the
average of its members' moves, so it has no randomness of its own and a company joining or leaving
never makes it jump. It starts at 100 credits and a halted member contributes no move.

## Savings Bonds

A bond lends a principal for **1, 3 or 7 days**. The interest for the whole term is set aside from
the Reserve when you buy, so the bond cannot fail to pay:

| Term | Interest for the whole term (default) | Key |
| --- | ---: | --- |
| 1 day | 0.8 % | `bondRate1DayPercent` |
| 3 days | 3 % | `bondRate3DayPercent` |
| 7 days | 8 % | `bondRate7DayPercent` |

- At maturity the principal and the interest are paid to your balance.
- Cashed in **early**, you get the principal back and the reserved interest returns to the Reserve.
- If the Reserve or the day's exchange budget cannot cover the interest, the bond is "sold out" for
  the day and your credits do not move.
- Savings Bonds are available while `exchangeEnabled` is `false` only for cashing in and maturity.

## Practice mode

Practice mode lets you trade the very same companies, index fund and bonds, at the very same prices,
with **play credits** (`practiceStartingCredits`, 10,000). It cannot touch the ledger: no operation
takes or returns a real credit, writes a transaction row or raises an alert, and nothing converts
between practice and real. The same spreads, fee, holding period, limits and refusals apply. Two
things differ because there is no Reserve behind it: a gain is always paid in full and a bond's
interest is always available.

You can start your practice portfolio afresh once per economy day (`/eco invest practice reset`). A
practice portfolio that has not been used for 30 economy days is dropped.

## Commands

All under `/eco` (also `/economy` and `/neroeconomy`):

| Command | What it does |
| --- | --- |
| `/eco invest list` | Lists the listed companies with prices, change and status. |
| `/eco invest buy <stock> <credits>` | Buys as many whole units as the credits cover, fee included. |
| `/eco invest sell <stock> <units>`, `/eco invest sell <stock> all` | Sells units (all that may be sold now). |
| `/eco invest portfolio` | Shows your holdings, bonds and limits. |
| `/eco invest bond buy <term> <credits>` | Buys a Savings Bond; `<term>` is `1d`, `3d` or `7d`. |
| `/eco invest bond redeem <id>` | Cashes a bond in. |
| `/eco invest practice ...` | The same commands for the practice portfolio, plus `reset`. |
| `/eco admin stocks` | Level 3. Aggregate view of the exchange: no account is named. |

`<stock>` is a company id such as `neroeconomy:greenfield_farms`.

## Switches

- `exchangeEnabled=false` leaves every price where it is and refuses every purchase and sale. A
  Savings Bond can still be cashed in and still pays out at maturity.
- `autonomousEconomy=false` stops the prices and the dividends but not trading at the current price.
- An operator can close the whole market with `/eco admin market close`; see
  [Configuration and administration](Configuration-and-Administration.md).
