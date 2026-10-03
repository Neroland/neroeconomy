# Privacy, history, export and erasure

This page summarises what NeroEconomy stores about players and how it is exported and erased. The
complete notice is the file `PRIVACY.md` in the repository.

All of this data is held by the server you play on, in its world folder. The operator of that
server is responsible for it, and is the person to ask for an export or an erasure. Questions about
the mod itself go to `info@neroland.co.za`.

## What is stored

- **Balances and account flags**, keyed to your Minecraft UUID: your balance, whether your account
  has been opened, your logging opt-out choice, and a counter of Credit Chips redeemed today.
  Player names are never stored.
- **Transaction history.** Each row holds a reason code, an amount, the currency, a time, and a
  source and destination reference. The references are **not** UUIDs: they are salted, one-way
  hashes that are specific to the world. Rows hold no names, items, chat or free text. They are
  pseudonymous rather than anonymous, because the salt is stored in the same file.
- **Market state**, keyed to your UUID: your sell listings and buy offers, the items and credits
  waiting in your claim, and the ids of your requests from the last 10 minutes.
- **Jobs and auctions**, keyed to your UUID: the contracts, deliveries, bounties and auctions you
  posted, claimed or bid on, and your bounty kill counts.
- **Shop ownership**: which Player Shop positions you own.
- **Block coordinates.** The position of each owned shop, the shop or board a listing was posted
  at, and the destination of a delivery job. No movement or location history is kept.

An item you put into escrow or are owed is stored as the game stores it, which can include a name
you gave it on an anvil.

No IP address is read, stored or logged. There is no wealth leaderboard: nothing ranks players by
balance or lists balances.

## How long it is kept

- Transaction rows are kept for `transactionRetentionDays` (90 by default) and are capped at
  `maxTransactionRecords` rows server-wide. Both limits are enforced when the world loads and daily.
- Settled jobs and auctions are kept for the same number of days after they end.
- Open listings, offers and jobs last until they are sold, filled, cancelled or expired. Claims
  wait until you collect them.
- Export files are deleted once they are 7 days old (sooner if `transactionRetentionDays` is
  lower), at the next daily check or server start.
- Balances, flags and shop ownership are kept until you are erased.

Ages are measured on the server's economy clock, which counts at most 72 hours for any period the
server was shut down, so data can last longer in calendar time than the configured number of days.

## History and the logging opt-out

`/eco history` shows only your own rows: reason, amount and time.

`/eco privacy logging true` opts your account out. This does not stop a row being written. The row
is still recorded with its reason, amount and time, but your side of it is left blank, so it no
longer refers to you; in a payment between two players, the other player's reference is kept. Rows
written before you opted out keep your reference until they age out. Rows written while you are
opted out do not appear in your history or in an export, and an erasure cannot find them; they age
out like any other row. Your balance and your trades work exactly as before.

## Who can see a balance

You can see your own. An operator with permission level 2 can look up any player's balance with
`/eco balance <player|uuid>`. Other players cannot.

## Export

An operator with permission level 3 runs `/eco export <player|uuid>`. It writes one JSON file to
`<world>/neroeconomy-exports/<uuid>.json` containing:

- your UUID, your own account reference, currency, balance and opt-out choice;
- your Credit Chip counter;
- every retained transaction row that refers to you, not just the newest `historyLimitPerPlayer`;
- your listings, buy offers, item claims, credit claims and recent request ids;
- the jobs and auctions you take part in, with your role, and your bounty progress;
- the position of every shop you own.

Nobody else is identified: the other party of a transaction is written as `counterparty`, and the
other side of a job or auction is left out.

Where the file system supports it, the file can be read only by the server's own user. It is
deleted after the lesser of `transactionRetentionDays` and 7 days, and when you are erased.

## Erasure

An operator with permission level 3 runs `/eco erase <player|uuid>`. This calls Core's shared
erasure registry, so the request reaches every installed Nero mod. Core's own data commands and its
optional inactivity purge use the same registry and have the same effect here. In Core 1.13.0 that
includes `/neroland data eraseme`, which any player can run to erase their own data; it destroys
your balance, escrow and claims as described below.

NeroEconomy then:

- removes your balance, account flags, Credit Chip counter and every transaction row that refers to
  you;
- removes your shop ownership; the shop blocks stay in the world without an owner, and anyone can
  break them;
- removes the jobs you posted and the auctions you were selling;
- reopens jobs you had claimed, and clears a bid you were holding;
- closes any bounty that was on you and refunds it to whoever posted it;
- removes your listings, buy offers, item claims and credit claims;
- deletes your export file and clears what the mod held about you in memory;
- rewrites its backup files, so the erased data does not survive there.

Everything you owned in the economy is **destroyed**, not paid out: your balance, the goods and
credits you had in escrow, your claims and your auction lots. What other players are owed because
of your erasure, such as a bid on your auction, is refunded to them.

One thing remains: a salted hash of your account, kept so that the starting balance is not minted
again if you return. If you do return, your account opens with a balance of zero.

Erasure cannot reach rows written while you were opted out (they do not refer to you), copies of
the world or of an export file made outside the server, or data held by the game or by other mods.

## Companion app

If the server runs NeroLink, the companion app can show your own balance and history and the open
market, and can trade for you. It is never sent another player's name, UUID, balance, history or
location; market rows are shown without their owners, and a renamed item is shown under its
ordinary name. Alerts are a small set of fixed sentences. The module stores nothing on disk.

## Server log and telemetry

The mod writes no log line that contains a player, an amount or a transaction.

The mod contains a crash reporter that is inert in published builds, because it has no address to
report to. If a build were made with one, a report would hold the mod version, an operation label,
the exception class, its message and its stack trace, with home-directory paths and anything shaped
like a UUID removed, at most 10 reports per session. `telemetryEnabled=false` opts an installation
out.
