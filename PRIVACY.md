# NeroEconomy privacy notice (POPIA / GDPR)

NeroEconomy has the largest player-data surface in the Neroland lineup, because an economy is by
definition a record of what players did with each other. This document describes exactly what is
stored, where, for how long, and how a player exercises their rights. Every statement below maps to
a config key in `config/neroeconomy.properties` or to a command in the `/eco` tree.

All storage is **server-side world data**. Nothing is sent to Neroland or to any third party except
the opt-out crash telemetry described at the end, which carries no personal data.

## What is stored

### Balances and account state

Keyed to an abstract account identifier; for a player that identifier is their Minecraft **UUID**.
Player names are never stored.

- The credit balance per account.
- Whether the account has been initialised (so the one-time `startingBalance` is minted once).
- The account's transaction-logging opt-out flag.

### Transaction history (pseudonymous)

Each retained row holds only:

- a **reason code** (an enum such as `PLAYER_PAYMENT`, `SHOP_PURCHASE`, `MARKET_ESCROW`,
  `AUCTION_PAYOUT`, `CREDIT_CHIP_REDEMPTION`);
- the **amount** and the **currency id**;
- a **timestamp**;
- a **source** and a **destination reference**.

The source/destination references are **not** UUIDs. They are the first 12 bytes of a
`SHA-256` digest of a per-world random salt plus the account's stable key, rendered as hex — a
one-way, world-local pseudonym. An empty reference denotes the server mint/burn account. No player
name, chat text, coordinates, IP address, or inventory contents is ever written to a row.

### Gameplay state that references a player

- **Shop ownership** — which Player Shop block belongs to which owner UUID.
- **Market offers and escrow** — sell listings (seller UUID + escrowed goods), standing buy offers
  (buyer UUID + escrowed credits), and per-player **claims** for proceeds and goods waiting to be
  collected, plus a bounded set of processed request ids used for idempotent retries.
- **Obligations** — contracts, delivery jobs, bounties and auctions carry the issuer UUID, the
  claimant UUID while a job is claimed, and bid/participation state.

### Data-subject export files

`/eco export` writes one JSON file per subject to `<world>/neroeconomy-exports/<uuid>.json`. It
contains that subject's UUID, currency, balance, opt-out flag, and their retained transaction rows.

## Retention

| Data | Retention | Key |
| --- | --- | --- |
| Transaction rows | `transactionRetentionDays` days (default 90; `0` keeps no records at all) | `transactionRetentionDays` |
| Transaction rows (hard cap) | Oldest rows dropped beyond `maxTransactionRecords` server-wide (default 10000) | `maxTransactionRecords` |
| Rows returned to one player | `historyLimitPerPlayer` (default 100) | `historyLimitPerPlayer` |
| Export files | The lesser of `transactionRetentionDays` and 7 days | `transactionRetentionDays` |
| Balances, ownership, escrow, claims, jobs | Kept while the gameplay object exists; removed on erasure | — |

Retention is enforced by a purge that runs at world load and daily thereafter, plus a bound check on
every append. Setting `transactionRetentionDays=0` or `maxTransactionRecords=0` disables transaction
logging server-wide.

## Player and operator controls

| Action | Command | Notes |
| --- | --- | --- |
| Stop optional transaction logging | `/eco privacy logging true` | Any player, for their own account. New rows naming that account are simply not written; existing rows still age out under retention. |
| Resume logging | `/eco privacy logging false` | |
| See your own retained rows | `/eco history` | Capped at `historyLimitPerPlayer`. |
| Subject-access export | `/eco export <player\|uuid>` | Operator (permission level 3). Writes the JSON described above. |
| Erasure | `/eco erase <player\|uuid>` | Operator (permission level 3). See below. |

`/eco erase` calls Neroland Core's shared **`data.PlayerDataErasure`** hook, so **one request purges
the player across every installed Nero mod**, not just this one. NeroEconomy registers five erasure
participants, before any gameplay store is exposed at init:

1. the ledger (balances, initialisation flag, opt-out flag, and every transaction row referencing
   that account's pseudonym);
2. shop ownership;
3. obligations — jobs the player issued are removed; jobs they had merely claimed are released back
   to `OPEN` so the counterparty is not harmed;
4. market listings, buy offers, item/credit claims and processed-request ids;
5. the on-disk export file, so an export can never outlive the rows it was taken from.

## Logging

No transaction detail, player name, or account identifier is written to the server console at info
level. Administrative mutations log the reason code only. Warnings for failed privacy operations
name the operation, never the subject.

## Crash telemetry

NeroEconomy embeds Sentry for crash reporting. It is:

- **opt-out** — set `telemetryEnabled=false` in `config/neroeconomy.properties` (this key is the one
  client-local setting; everything else is server-authoritative);
- **inert by default** — the shipped DSN is a placeholder, so nothing is sent until a real DSN is
  configured in a build;
- **scoped** — only events whose stack frames touch `za.co.neroland.neroeconomy` are considered;
- **capped** — at most 10 events per session, de-duplicated by fingerprint;
- **scrubbed** — the Sentry user and server name are cleared, absolute stack-frame paths are
  dropped, and user/home path fragments are stripped from messages and exception values;
- **PII-free** — `sendDefaultPii` is off and no player identifier, name, balance, or transaction
  value is attached. Only a version string, environment, and the scrubbed stack trace are sent.

## Companion app (NeroLink)

If NeroLink is installed, NeroEconomy registers a **player-scoped** Core Link module exposing three
read sections (`balance`, `transactions`, `market`) and one action (`trade`). Every snapshot and
action is executed on the server thread for the requesting player only; the bridge stores no economy
state, and a player can never read another player's balance or history through it. Remote trading can
be disabled server-side with `remoteTradeEnabled=false`.
