# Bounties and auctions

## Bounties

A bounty pays for kills. Posting is done by command for now.

```text
/eco bounty post entity <entity type id> <payout> [quantity]
/eco bounty post player <player> <payout> [quantity]
```

For example, `/eco bounty post entity minecraft:zombie 50 10` pays 50 credits for ten zombie kills.

- `quantity` is the number of kills needed, 1 if left out. A bounty pays once, when one player
  reaches the full quantity, not per kill.
- The entity type must exist. Entity ids are plain strings, so a creature from another mod, such as
  NeroCreatures, can be a target without any dependency on that mod.
- The payout is held in escrow from the moment of posting, and the bounty counts towards
  `maxJobsPerPlayer`. A bounty lasts `defaultListingDurationHours` (48 by default); if it expires,
  the payout is refunded to the issuer's credit claim.
- Kills are counted on the server from the damage source, so only a kill credited to you counts.
  The issuer's own kills never count.
- Claiming is optional. While nobody has claimed a bounty, every player's kills are counted
  separately and the first to reach the quantity is paid. `/eco bounty claim <id>` reserves it, so
  that only your kills count; `/eco bounty release <id>` gives it up, and an unfulfilled claim
  lapses after `jobClaimTimeoutMinutes` (120 by default).
- The payout becomes a credit claim. Collect it at a terminal.
- A **server bounty** has no player issuer: the server posts a few per day on hostile mobs (see
  `systemBountiesPerDay`), funded from the Reserve within `systemJobDailyPayoutCap`. An expired one
  returns its payout to the Reserve.
- The issuer can cancel an unclaimed bounty with `/eco market cancel <id>`.

### Bounties on players

Bounties on a named player are off by default. Posting one needs **both** `playerBountiesEnabled=true`
and permission level 2.

- The target must be online when the bounty is posted, and you cannot post a bounty on yourself.
- `minecraft:player` is not accepted as an entity target, because that would be a bounty on every
  player.
- The player a bounty is on cannot claim it.
- No list, screen or message shows the target's name or UUID. The issuer sees "the player you
  named", the target sees "you", and everyone else sees "a player".
- If the target's data is erased, the bounty is closed and its payout is refunded to the issuer.

## Auctions

An auction sells a lot to the highest bidder when its time runs out. Creating one is done by
command for now; bidding can be done by command or from the auctions tab of a Trading Terminal or
Market Board.

```text
/eco auction create <quantity> <minimumBid> <minutes> [interplanetary]
/eco auction bid <id> <amount>
/eco auction list
```

- The lot is `quantity` (1 to 64) of the stack in your main hand. It leaves your inventory and is
  held in escrow. `minutes` is 1 to 10080 (seven days). You can run `maxAuctionsPerPlayer` auctions
  at once (8 by default).
- The first bid must be at least the minimum bid. After that, each bid must beat the standing bid
  by at least `auctionMinIncrementPercent` (5 % by default, rounded up) and always by at least 1
  credit. A bid that is too low is refused with the lowest amount that would be accepted.
- On the screen, Act bids exactly the "next bid" amount shown on the row. If the auction has moved
  on since the row was sent, the bid is refused rather than raised.
- A bid is held in escrow. When you are outbid, your bid is refunded to your credit claim and you
  get an "outbid" alert in the companion app. Raising your own bid takes only the difference.
- A bid placed within `auctionAntiSnipeSeconds` of the end (60 by default) moves the end to that
  many seconds after the bid.
- You cannot bid on your own auction, and your [Vault](Vault.md) must have room for the lot.
- When the auction ends, the winner receives the lot in their Vault and the seller receives the
  winning bid as a credit claim, less the auction fee (`auctionFeePercent`, 5 % by default, rounded
  up and paid to the Reserve). With no bid, the lot returns to the seller's Vault.
- The seller can cancel an auction that has no bid with `/eco market cancel <id>`. Once anyone has
  bid, the lot is committed.

### Interplanetary auctions

`interplanetary true` asks for an interplanetary auction, which uses Core's `reached_orbit`
progression gate. If the seller has not opened that gate, the auction is created as a local one
instead. An interplanetary lot is hidden from, and cannot be bid on by, a player who has not opened
the gate.
