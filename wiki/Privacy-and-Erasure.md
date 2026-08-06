# Privacy, history, and erasure

The ledger stores player UUID account ids, balances, one-time initialization, opt-out choice, and a
bounded append-only history. Rows contain currency, amount, timestamp, source/destination UUID account
references, and a machine-readable reason. They contain no names, item text, chat, or free text.

Age and global-count bounds are enforced at load and daily. Logging opt-out suppresses non-essential
history without changing balance integrity. `/eco history` is private. `/eco export <player|uuid>`
writes subject-access JSON inside the world folder.

`/eco erase` calls Core's shared `PlayerDataErasure` registry. NeroEconomy's hooks remove the
subject's ledger/account data, ownership, listings/offers, claims, jobs, auctions, and request state.
Application info logs never contain transaction detail. Optional Sentry telemetry is scrubbed,
ten-event capped, sends no default PII, and is inert while configured with the placeholder DSN.
