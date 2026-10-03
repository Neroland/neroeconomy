# Contracts and delivery

A supply contract asks for a quantity of an item, or of any item in an item tag, for a payout. A
delivery job is the same, except that the goods must be handed over at a destination.

Posting is done by command for now. Claiming and fulfilling can be done by command or from the jobs
tabs of a Trading Terminal or Market Board.

## Posting

```text
/eco contract post item <item id> <quantity> <payout>
/eco contract post tag <tag id> <quantity> <payout>
/eco delivery post item <item id> <quantity> <dimension> <x> <y> <z> <payout>
/eco delivery post tag <tag id> <quantity> <dimension> <x> <y> <z> <payout>
```

For example:

```text
/eco contract post item minecraft:iron_ingot 64 200
/eco contract post tag minecraft:planks 128 60
/eco delivery post item minecraft:bread 32 minecraft:overworld 120 64 -40 150
```

- Type ids in full, with the namespace. A tag id is typed without the `#`.
- The item must exist, the tag must contain at least one item, and the dimension must exist. A
  target is at most 64 characters. Items in the `neroeconomy:untradeable` tag cannot be requested.
- `quantity` is 1 to 2304 and `payout` is at least 1 credit.
- The payout leaves your balance when you post and is held in escrow. If you cannot afford it, the
  job is not posted.
- You can have `maxJobsPerPlayer` unsettled jobs at once (16 by default), counting contracts,
  deliveries and bounties together.
- A job lasts `defaultListingDurationHours` (48 by default).

The reply gives the job's id. `/eco contract list` and `/eco delivery list` show open and claimed
jobs.

## Claiming and releasing

- `/eco contract claim <id>` (or `delivery claim`) reserves an open job for you. You cannot claim
  your own job, and a job has one claimant at a time.
- A claim holds nothing of yours in escrow.
- `/eco contract release <id>` (or `delivery release`) gives the job up, and it is open again.
- A claim that has not been fulfilled after `jobClaimTimeoutMinutes` (120 by default) is dropped
  automatically and the job is open again. `0` turns the timeout off.

## Fulfilling

`/eco contract fulfill <id>` (or `delivery fulfill`) settles a job you have claimed. On the screen,
select the job and press Act.

- The goods are taken from your main inventory and hotbar.
- **Only unmodified items count.** A stack that is renamed, enchanted, filled or damaged is not
  accepted. If you carry enough items but some are modified, the message says so.
- For a delivery you must be in the destination's dimension and within 4 blocks of it.
- The payout becomes a credit claim for you, and the goods become an item claim for the issuer.
  Collect at a Trading Terminal, Market Board or Bank Terminal.
- If the issuer's claim list is full, the job cannot be fulfilled until they collect.

Every refusal happens before anything is taken from your inventory, and the message says what is
missing.

## Expiry and cancelling

- When a job expires, the payout is refunded to the issuer's credit claim.
- The issuer can cancel a job that nobody has claimed with `/eco market cancel <id>`; the payout
  goes to their credit claim. A claimed job cannot be cancelled, because a worker is relying on it.

## Jobs from other mods

NeroLogistics may post delivery jobs through a string-keyed runtime seam. Its absence is a no-op.

A mod can also post a **server-funded** delivery job that has no player issuer. Nothing is escrowed for it:
the payout is created when the job is fulfilled, and the delivered goods are consumed. Operators
control this with `systemJobsEnabled` (on by default) and `systemJobDailyPayoutCap` (5000 credits of
such jobs may be posted per day by default).
