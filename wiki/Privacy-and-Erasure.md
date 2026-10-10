# Privacy, history, export and erasure

This page summarises what NeroEconomy stores about players and how it is exported and erased. The
complete notice is the file `PRIVACY.md` in the repository.

All of this data is held by the server you play on, in its world folder. The operator of that
server is responsible for it, and is the person to ask for an export or an erasure. Questions about
the mod itself go to `info@neroland.co.za`.

## What is stored

Everything is one saved-data file in the world, `neroeconomy:economy`, made of sections. Each row is
keyed to your Minecraft UUID unless it says otherwise. Player names are never stored.

- **Balance and account flags:** your balance, whether your account has been opened, whether your
  starting balance has vested, whether you were given the Trader's Handbook, your logging opt-out
  choice, and a counter of Credit Chips redeemed today.
- **Transaction history.** Each row holds a reason code, an amount, the currency, a time, and a
  source and destination reference. The references are **not** UUIDs: they are salted, one-way
  hashes that are specific to the world. Rows hold no names, items, chat or free text. They are
  pseudonymous rather than anonymous, because the salt is stored in the same file.
- **Your Vault:** the goods the economy holds for you (item id, count, and the item's data for
  modified items).
- **Orders, offers and rules:** your open sell and buy orders, the offers you sent or were sent, your
  auto-trade rules, and the goods and credits held in escrow for them.
- **Credits you are owed:** sale proceeds, payouts and refunds waiting to be collected.
- **Market state:** your Player Shop listings and the ids of your requests from the last 10 minutes.
- **Jobs and auctions:** the contracts, deliveries, bounties and auctions you posted, claimed or bid
  on, and your bounty kill counts.
- **Exchange:** your shares and index units, Savings Bonds, last dividend, and your practice
  portfolio.
- **Merchant House counts:** what you traded with the Houses today. These are dropped each day.
- **Companion-app spend:** what you spent and sent away through the app today.
- **Block ownership:** the position of each Player Shop and Auto-Trader you own.
- **Block coordinates.** Also the shop or board a listing was posted at, and the destination of a
  delivery job. No movement or location history is kept.

An item you put into escrow or are owed is stored as the game stores it, which can include a name you
gave it on an anvil.

No IP address is read, stored or logged. Counterparties are never named to each other, unless you
addressed someone yourself. There is **no wealth leaderboard**: nothing ranks players by balance, net
worth or trade volume.

## How long it is kept

- Transaction rows are kept for `transactionRetentionDays` (90 by default) and are capped at
  `maxTransactionRecords` rows server-wide. Both limits are enforced when the world loads and daily.
- Settled jobs and auctions are kept for the same number of days after they end.
- Open orders, offers and jobs last until they are filled, answered, cancelled or expired. Credits you
  are owed wait until you collect them. Your Vault has no time limit.
- Holdings, bonds and rules are kept until you sell, cash in or delete them.
- Practice portfolios are dropped after 30 days without use. House counts and companion-app spend
  rows are dropped after their day.
- Export files are deleted once they are 7 days old (sooner if `transactionRetentionDays` is lower),
  at the next daily check or server start.
- Balances, flags and block ownership are kept until you are erased.

Ages are measured on the server's economy clock, which counts at most 72 hours for any period the
server was shut down, so data can last longer in calendar time than the configured number of days.

## History and the logging opt-out

`/eco history` shows only your own rows: reason, amount and time.

`/eco privacy logging true` opts your account out. This does not stop a row being written. The row is
still recorded with its reason, amount and time, but your side of it is left blank, so it no longer
refers to you; in a payment between two players, the other player's reference is kept. Rows written
before you opted out keep your reference until they age out. Rows written while you are opted out do
not appear in your history or in an export, and an erasure cannot find them; they age out like any
other row. Your balance and your trades work exactly as before.

## Who can see a balance

You can see your own. An operator with permission level 2 can look up any player's balance with
`/eco balance <player|uuid>`. Other players cannot. Operators see totals of the whole economy
(`/eco admin supply`) but never a ranking.

## Export

An operator with permission level 3 runs `/eco export <player|uuid>`. It writes one JSON file to
`<world>/neroeconomy-exports/<uuid>.json` containing everything above that concerns you: your
account data and every retained transaction row that refers to you (not just the newest
`historyLimitPerPlayer`), your shop listings and credits owed, your orders, offers and rules, your
Vault, the jobs and auctions you take part in with your role, your holdings and practice portfolio,
today's House counts and companion-app spend, and the positions of the blocks you own.

Nobody else is identified: the other party of a transaction is written as `counterparty`, and the
other side of a job, auction or offer is left out. Items are exported as an item id and a count.

Where the file system supports it, the file can be read only by the server's own user. It is deleted
after the lesser of `transactionRetentionDays` and 7 days, and when you are erased.

## Erasure

An operator with permission level 3 runs `/eco erase <player|uuid>`. This calls Core's shared erasure
registry, so the request reaches every installed Nero mod. Core's own data commands and its optional
inactivity purge use the same registry and have the same effect here. In Core 1.13.0 that includes
`/neroland data eraseme`, which any player can run to erase their own data; it destroys your balance,
escrow and holdings as described below.

NeroEconomy then:

- removes your balance, account flags, Credit Chip counter and every transaction row that refers to
  you;
- ends a direct trade you have open, returning the other player's goods to them;
- removes your orders, rules, offers and Player Shop listings; the goods in them are destroyed and
  the credits in escrow go back to the Reserve;
- returns offers other players sent to you to their senders (credits as a claim, goods to their
  Vault);
- moves the cost of your shares, the principal of your Savings Bonds and their reserved interest back
  to the Reserve, and removes your holdings and practice portfolio;
- removes your Vault and the goods in it, and your credits owed to you;
- removes your shop and Auto-Trader ownership (the blocks stay in the world without an owner, and
  anyone can break them);
- removes the jobs you posted and the auctions you were selling, reopens jobs you had claimed, clears
  a bid you were holding and closes any bounty that was on you, refunding it to whoever posted it;
- removes today's House counts and companion-app spend, deletes your export file and clears what the
  mod held about you in memory;
- refreshes its recovery backup, so the erased data does not survive there.

Everything you owned in the economy is **destroyed or returned to the Reserve**, not paid out: your
balance, the goods and credits you had in escrow, your Vault, your holdings, and your auction lots.
What other players are owed because of your erasure, such as a bid on your auction, an offer sent to
you or a bounty on you, is refunded to them.

One thing remains: a salted hash of your account, kept so that the starting balance is not paid again
if you return. If you do return, your account opens with a balance of zero.

Erasure cannot reach rows written while you were opted out (they do not refer to you), copies of the
world or of an export file made outside the server, or data held by the game or by other mods.

## Companion app

If the server runs NeroLink, the companion app can show your own balance, history, vault, orders,
offers, holdings and the open market, and can trade for you, even while you are offline. It is never
sent another player's name, UUID, balance, history or location; market rows are shown without their
owners, and a renamed item is shown under its ordinary name. Alerts are a small set of fixed
sentences. The only thing the module keeps on disk is the day's spend row. See
[Fair play](Fair-Play.md) for the limits on what a stolen app token can do.

## Server log and telemetry

The mod writes no log line that contains a player, an amount or a transaction.

The mod contains a crash reporter that sends anonymous error reports to Sentry (EU servers). A
report holds the mod version, an operation label, the exception class, its message and its stack
trace, with home-directory paths and anything shaped like a UUID removed, at most 10 reports per
session, and never a player, an amount or world data. Reporting is on by default and opt-out:
`telemetryEnabled=false` in `config/neroeconomy.properties` opts an installation out.
