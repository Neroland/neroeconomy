# NeroEconomy runtime smoke test (0.1.0 feature set, shipping as 0.0.1-alpha.1)

Use a disposable world with Core 1.10.0. Repeat essential block/network flows on all loaders and cover
both Minecraft versions across the matrix. Use two players for sync and settlement.

## Startup, ledger, and privacy

- Confirm startup, config creation, one-time starting balance, and no service/payload errors.
- Pay by command and Bank Terminal; click/replay rapidly and verify exactly one settlement.
- Toggle logging opt-out, export the player, inspect bounded pseudonymous JSON, then erase. Confirm
  ledger, ownership, offers, claims, jobs, auctions, and preferences for that UUID are removed.

## Blocks and market

- Place/break every block and verify models, textures, loot, recipes, titles, and shop ownership.
- Sell from a Player Shop while its chunk/owner is unloaded; collect proceeds.
- Search/paginate the Trading Terminal and verify excessive price movement rejects at execution.
- Post and fill Market Board sell and standing buy offers. Collect both sides; restart and expire open
  offers to verify refunds. Test full inventories and rapid clicks for loss/duplication.

## Obligations, chip, and Link

- Post/claim/fulfill/expire an item contract and location delivery; test wrong item/location.
- Complete a bounty. Auction with two bidders; verify outbid refund, late extension, winner/proceeds,
  countdown sync, and local degradation without orbit progression.
- Redeem one Credit Chip at a Bank Terminal and verify exactly one configured payout.
- With NeroLink present, verify scoped snapshots, paging/search, server-repriced trade, alert rate
  limits, and restart persistence for every in-progress system.
