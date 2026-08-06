# Credits and commands

Credits are Core's `nerolandcore:credits` whole-unit currency. NeroEconomy supplies its persistent
provider; balances cannot become negative and every mutation is server-authoritative.

## Player commands

- `/eco balance` shows your balance; operators may add a player or UUID.
- `/eco pay <player|uuid> <amount>` atomically sends positive credits.
- `/eco history` shows only your retained reason, amount, and timestamp rows.
- `/eco privacy logging <true|false>` controls non-essential transaction recording.

## Administrator and data-subject commands

- `/eco admin give|take|set <player|uuid> <amount>` mutates without overflow or overdraft.
- `/eco export <player|uuid>` writes that subject's data as JSON under the world folder.
- `/eco erase <player|uuid>` invokes Core's shared erasure registry across Nero mods.

Administrative mutations require the configured permission and feature switch. Logs include mutation
type and amount, never player name, UUID, or transaction counterpart.
