# Fair play

NeroEconomy is built to stay sound against players, alternate accounts and a stolen app token. This
page lists the rules that keep it fair, and says plainly what a mod cannot defend against.

## What the server checks on every action

Every value-moving request, from a screen, a command or the companion app, is checked on the server
for: a sign, zero or overflowing amount, a stale container, the menu being open, range, permission
level, the player being alive and not a spectator, and a per-player rate limit. Each carries a
request id so a replayed or reordered packet acts once, and the server re-validates and re-prices
everything when it runs. The server remembers a player's latest 128 request ids for 10 minutes,
across restarts; an id older than that, or pushed out by 128 newer ones, is no longer recognised. A client never computes a price or a balance.

- **Exact arithmetic.** Fees round up, so splitting an order into singles gains nothing. Hard caps
  bound every order, offer, rule and portfolio.
- **No credits or goods in two places or none.** Disconnecting, dying, changing dimension, a chunk
  unloading, a full inventory, a full vault, replayed packets and two players on one row in one tick
  all end with every credit and item in exactly one place. The economy store is saved with the
  game's own save, together with player data, so a crash rolls both back together.
- **Escrow before publish.** Goods and credits are held before an order, offer or job becomes visible.
- **Settlement is atomic.** A trade is one operation, every leg or none.
- **Refunds always book**, even into a full vault or past a limit.

## Limits that stop alternate accounts

An account is not trusted just because it is new. These limits make a farm of alternate accounts
unprofitable. NeroEconomy never stores or compares IP addresses.

- **Locked starting credits.** A new account's starting balance is locked for
  `startingBalanceVestMinutes` (30) of play time. Locked credits can pay fees and Merchant Houses
  but cannot be paid, offered, traded or used to buy from another player, nor put into shares or
  bonds.
- **Fees on everything.** A market fee, a listing fee and an exchange fee return credits to the
  Reserve on every trade, so trading back and forth between two accounts only ever loses credits.
- **Daily caps per account.** Merchant Houses cap how much one account may buy or sell per commodity
  per day. The Credit Chip is capped per account, steps down in value across the day and has a daily
  budget for all accounts together.
- **Reference prices resist wash trading.** A reference price moves only when several different pairs
  of accounts traded, no pair counts for more than a small share, and a reference never moves more
  than `referenceDriftDayPercent` (10 %) in a day. See
  [Autonomous economy](Autonomous-Economy.md).
- **Share prices cannot be pushed.** Nothing you buy or sell on the exchange moves a price. Volume
  from the order book nudges a price by at most half a percent a step and is capped per pair and per
  account.
- **Exchange limits.** A holding period, per-stock and total position limits, a price floor and
  daily limits with a halt always apply, and a round trip always loses the spread and both fees.
- **Rate limits.** Requests are limited per player; direct-trade requests have a cooldown; offers
  are capped per sender (16 open), at four waiting with the same recipient and 64 in one inbox, and
  can be muted.

## Creative mode and spectators

Nothing a creative-mode player or the gallery does can put value into the real economy. A creative
or spectator player cannot be a *source* of goods: they cannot list, offer, trade, deposit, stock a
shop, auction, fulfil a job or redeem a Credit Chip from items made for free. They can still buy,
receive and read. An Auto-Trader's sell rule does not list goods while its owner is in the game in
creative or spectator mode. The operator-only gallery builds sample rows that hold no value, can
never be bought and never reach the market, the Reserve, link snapshots or telemetry.

## Merchant Houses cannot be drained

Houses are bounded by daily supply and demand caps, per-account caps, elasticity (their prices move
against anyone who pushes), the arbitrage guard, a working-capital limit, and a Reserve that can
never go negative. See [Autonomous economy](Autonomous-Economy.md).

## The companion app and remote limits

A stolen app token can act as the player, but only within hard limits. Every action needs a request
id, is re-validated and re-priced on the server and is rate-limited per action.

| Limit | Key | Default |
| --- | --- | ---: |
| Credits one account may spend through the app per economy day (purchases, buy-order escrow, rule budgets, shares, bonds, payments, offers) | `remoteDailySpendLimit` | 5,000 |
| Credits' worth of goods that may leave one account through the app per economy day (goods in offers, sell orders and sell rules from the vault, goods an accepted offer asks for) | `remoteDailyGoodsLimit` | 5,000 |
| Value counted for one unit of unmodified goods that is no commodity | `remoteGoodsFloorValue` | 10 |
| Value counted for one unit of modified goods | `remoteModifiedGoodsValue` | 500 |

The two daily limits are counted apart, and together are the most a stolen token can cost a player in
a day. A commodity counts at its reference price. Moving items between a vault and an inventory is
not possible through the app while the player is offline. `remoteTradeEnabled=false` switches all
app actions off, and the player can revoke the token in NeroLink.

## What NeroEconomy cannot defend against

The economy is built to stay sound against players, alternate accounts and a stolen app token. It
cannot defend against the people who run the server, or against a server that does not know who its
players are.

- **Operators.** Anyone with permission level 3 can grant, take or set credits, close the market,
  run simulation steps and accept a fresh start. Every such change is counted and recorded under a
  fixed reason, and the counts are shown on the Admin tab and by `/eco admin supply`, but nothing
  stops an operator from using them. Operators can also give items, change game modes (a creative
  player's items can reach the economy through a survival player or a hopper), edit data packs
  (prices, Merchant Houses, companies), edit or replace the save file, or move the host clock (a
  forward jump within the catch-up cap looks like real time passing). Turn off
  `adminMutationsEnabled` to remove the in-game credit commands; nothing a mod does can remove file
  or console access.
- **`online-mode=false` servers.** With authentication off, a client can claim any name and so any
  account, and can create new accounts without limit. Locked starting credits, per-account caps, the
  daily remote limits and every per-player rate limit assume that one account is one person who
  cannot become someone else. On such a server, treat every balance as untrusted. NeroEconomy never
  stores or compares IP addresses, so it cannot and will not try to detect this.
- **Sizes under many accounts.** Several stores (balances, vaults, orders, offers, rules) are bounded
  by the number of accounts times a per-account cap, and the number of accounts is only as bounded
  as your server's player base. On an `online-mode=false` server that is not a bound at all.
- **The companion app's token.** A stolen token can act as the player within the daily remote limits
  above until the player revokes it in NeroLink.

## Privacy and fairness

No player name or identifier appears in a log, alert, broadcast, telemetry event or analytics event.
Counterparties are never named to each other unless a player addressed them. There is no wealth
leaderboard. See [Privacy](Privacy-and-Erasure.md).
