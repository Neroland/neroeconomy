# NeroEconomy runtime smoke test

Test the build whose version is `mod_version` in `gradle.properties` (`0.2.0-alpha.1` at the time of
writing) in a disposable world, with the Neroland Core version pinned by `nerolandcore_version` in
the same file (`1.13.0`). Repeat essential block/network flows on Fabric, Forge and NeoForge and
cover Minecraft 26.1.2, 26.2 and 26.3 across the nine-cell matrix. Use two players for sync and
settlement.

## Startup, ledger, and privacy

- Confirm startup, config creation, one-time starting balance, and no service/payload errors.
- Pay by command and Bank Terminal; click/replay rapidly and verify exactly one settlement.
- Toggle logging opt-out, export the player, inspect bounded pseudonymous JSON, then erase. Confirm
  ledger, ownership, offers, claims, jobs, auctions, and preferences for that UUID are removed.

## Blocks and market

- Place/break every block and verify models, textures, loot, recipes, titles, and shop ownership.
- Sell from a Player Shop while its chunk/owner is unloaded; collect proceeds.
- Search/paginate the Trading Terminal. Verify that a price which rose after the quote rejects at
  execution, and that a price which fell is charged at the lower price.
- Post and fill Market Board sell and standing buy offers. Collect both sides; restart and expire open
  offers to verify refunds. Test full inventories and rapid clicks for loss/duplication.

## Obligations, chip, and Link

- Post/claim/fulfill/expire an item contract and location delivery; test wrong item/location.
- Complete a bounty. Auction with two bidders; verify outbid refund, late extension, winner/proceeds,
  countdown sync, and local degradation without orbit progression.
- Redeem one Credit Chip at a Bank Terminal and verify exactly one configured payout.
- With NeroLink present, verify scoped snapshots, paging/search, server-repriced trade, alert rate
  limits, and restart persistence for every in-progress system.

## Audit-remediation checks

These cover what the October 2026 remediation changed. They extend the sections above.

### Jobs and bounties

- Post a contract end to end: `/eco contract post item minecraft:iron_ingot 16 50`, confirm it in
  `/eco contract list` and in the Trading Terminal's jobs tab, claim it as a second player, run
  `/eco contract fulfill <id>`, then collect the payout (worker) and the goods (issuer).
- Post by tag without the `#`: `/eco contract post tag minecraft:planks 32 20`.
- Post a delivery: `/eco delivery post item minecraft:bread 8 minecraft:overworld 0 64 0 40`. Fulfil
  it away from the destination (refused), then within four blocks of it (accepted).
- Post a bounty with a quantity: `/eco bounty post entity minecraft:zombie 25 3`. Verify it pays on
  the third kill, not the first.
- Verify the refusals: an unknown item id, an empty tag, a target longer than 64 characters, a
  renamed or damaged stack at fulfilment, and a 17th open job (`maxJobsPerPlayer`).
- `bounty post player` is absent below permission level 2 and refused while
  `playerBountiesEnabled=false`. With both satisfied, verify the named player cannot claim the
  bounty on themselves and that no list shows their name or UUID.
- Claim a job, run `/eco contract release <id>`, and confirm it is open again. Set
  `jobClaimTimeoutMinutes=1`, claim a job, and confirm it reopens by itself.

### Cancel paths

- `/eco market mine` lists only the caller's own board listings, shop listings, buy offers, jobs
  and auctions.
- `/eco market cancel <id>` returns a board listing's stock and a bid-less auction's lot to the item
  claim, and a buy offer's or unclaimed job's escrow to the credit claim.
- A claimed job, an auction with a bid and a shop listing are refused as not cancellable. Another
  player's id answers exactly like an unknown id.

### Bank Terminal and large numbers

- Send to a random UUID that has never joined: the terminal names the unknown recipient, moves
  nothing, and `/eco balance <uuid>` as an operator still reports no such account.
- Send more than the balance: the terminal says so and opens no account for the recipient.
- `/eco admin set <player> 100000`: the Bank Terminal, Trading Terminal and Player Shop screens all
  show 100,000. Post a Market Board listing at a unit price of 40,000 and buy it from a Trading
  Terminal.

### Screens and packets

- Type `e` (the inventory key) and digits bound to hotbar slots into the search, recipient, amount,
  quantity and price boxes: the screen stays open and the characters appear. Escape still closes
  the screen and Tab moves focus.
- Click Buy as fast as possible: purchases stop being accepted beyond the rate limit without any
  error spam, and no credit or item is lost or duplicated.
- As a spectator, open each terminal and confirm that no button has any effect.

### Shops

- Remove a stocked shop with an explosion and with `/setblock ... air`: the stock appears in the
  owner's claim and the next shop placed there belongs to its placer.
- Erase a shop owner, then confirm any player can break the ownerless shop block.
- Set `shopListingMaxIdleDays=1` and confirm (or reason from the stored `last_activity`) that an
  untouched listing expires into the owner's claim.

### Faucets, clock and persistence

- Redeem six Credit Chips in one day with `creditChipDailyLimit=5`: the sixth is refused and kept.
- With Nerospace installed, confirm the Credit Chip appears in the exotic meteor material list.
- Confirm `neroeconomy_ledger_backup.dat`, `neroeconomy_market_backup.dat`,
  `neroeconomy_obligations_backup.dat` and `neroeconomy_shop_owners_backup.dat` appear in the
  world's `data` folder.
- Collect a claim, stop the server abruptly a few seconds later, and confirm the claim is not
  offered again after restart.
- Stop the server, move the system clock forward by a week, start it, and confirm listings and
  auctions lost at most 72 hours of their remaining time.

### Privacy

- Create more than `historyLimitPerPlayer` transactions, a listing, a buy offer, a job, an auction
  bid, a shop and an uncollected claim, then run `/eco export <player>`. Open
  `<world>/neroeconomy-exports/<uuid>.json` and check that it holds every transaction row inside the
  retention window, `credit_chip_redemptions`, and the `market`, `obligations` and `shops` sections,
  and that the other party of every row reads `counterparty`.
- On Linux, confirm the export directory is mode `700` and the file `600`.
- Restart the server with an export older than seven days present and confirm it is deleted at start.
- `/eco erase <player>`: confirm the export file, balance, listings, offers, claims and shop rows
  are gone, a counterparty's standing bid was refunded to their claim, a bounty on the erased
  player was closed and refunded to its issuer, and rejoining does not mint a second starting
  balance.

### Gallery and development tooling

- A creative-mode player below permission level 3 has no `gallery` command; a level-3 operator in
  survival is told to switch to creative; `adminMutationsEnabled=false` removes the command.
- After `/eco gallery`, demo rows cannot be bought or filled and are invisible to an ordinary
  player's search and to the companion app. `/eco gallery clear` leaves a real shop inside the
  footprint standing.
- In a release jar there is no `/necgallery` command and no `neroeconomy:capture` dimension.

### Companion app

- With NeroLink and the app: the market tab lists other players' rows, history loads and pages,
  `sell` on a row marked sell fills that buy offer from the main hand, and `buy` with the player
  logged out delivers to their item claim.
- Change the balance in game while the app is open and confirm it updates without a refresh.
