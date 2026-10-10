# NeroEconomy runtime smoke test

Test the build whose version is `mod_version` in `gradle.properties` (`0.3.0-beta.1` at the time of
writing) in a **disposable world**, with the
Neroland Core version pinned by `nerolandcore_version` in the same file (`1.13.0`). Repeat the
essential flows on Fabric, Forge and NeoForge and cover Minecraft 26.1.2, 26.2 and 26.3 across the
nine-cell matrix. Use **two players** (call them A and B) for sync and settlement, and an operator
account (permission level 3) for the admin steps. Each section is one flow per feature; tick it only
when every step behaves as written.

For a fast loop, shorten the economy: set `simStepMinutes=1`, `shareHoldMinutes=1`,
`startingBalanceVestMinutes=1` and `defaultListingDurationHours=1` in `config/neroeconomy.properties`,
and put the values back before the final pass.

## 1. Startup, the Reserve and privacy basics

- Confirm startup, config creation and no service or payload errors in the log, and that the log holds
  no player name, UUID or amount.
- Join as A and B: each gets one starting balance and one Trader's Handbook (`giveHandbookOnFirstJoin`).
  The starting balance is **locked** until `startingBalanceVestMinutes` of play time: before then,
  `/eco pay` to the other player and an offer of credits are refused with a plain message, but a
  purchase from a Merchant Stall is allowed. After the time has passed, paying works.
- `/eco admin supply` (operator) shows circulation, Reserve, escrow, exchange, and minted and burned
  today, and names no account. `/eco admin health` says the economy is healthy.
- Pay by command and at the Bank Terminal; click and replay rapidly and verify exactly one settlement.
- Stop the server, delete `neroeconomy:economy` from `<world>/data` (and its backup), start it, and
  verify the economy **freezes**: value-moving actions are refused with a clear message and
  `/eco admin health` says so. Restore the file, restart, and confirm it runs again. (Alternatively
  use `/eco admin health accept` to start a fresh economy.)

## 2. The Economy Hub, demos and the Handbook

- Open each block (Bank Terminal, Trading Terminal, Market Board, Exchange Terminal, Merchant Stall,
  Auto-Trader, Player Shop) and verify the right tab opens. `/eco hub` and `/eco hub invest` open the
  hub without a block. A non-operator cannot see the Admin tab.
- Press **?** on every tab, run the **Show me** walkthrough and verify the DEMO banner is always
  visible, Back and Next work, and no balance, order or inventory changes. Close the demo with Escape.
- Open the Trader's Handbook: read a page of every chapter, press Open demo, close the demo, and verify
  it returns to the same page. Confirm the book is craftable from a book and a gold nugget.
- Every control has a tooltip, every empty list says what to do next, and every confirm step restates
  what is spent and received. Typing `e` or a hotbar digit into any text box types the character.
- As a spectator, open each terminal and confirm that no button has any effect.

## 3. The Vault

- At the Bank Terminal, deposit a stack of cobblestone, a renamed sword and an enchanted book (if it
  is tradeable), then withdraw them: names and enchantments survive, and the renamed sword stays
  its own line.
- Shift-click, drag, number-key swap and double-click collect all end with the vault and the screen
  equal. Items in the `neroeconomy:untradeable` tag are refused.
- Set `vaultStacks=9`, fill the vault, and verify a player-started purchase is refused **before any
  credit moves**, deposits are refused, withdrawals work, and the alert "Your vault is full" appears.
  A refund or expiry still lands in the vault past the limit.
- In creative or spectator mode, deposits are refused and the message says why.

## 4. Orders, shops and the Market

- A posts a sell order for 64 oak logs from the main hand and a second from the vault. B posts a
  crossing buy order. Verify fills happen at the resting order's price, B's overbid is refunded, A's
  proceeds arrive as a credit claim less `marketFeePercent`, and `listingFee` is charged per order.
- B's buy order for an item nobody sells rests and escrows its credits; A fills it later while B is
  offline and B finds the goods in the vault on return.
- A cannot match their own order. A modified stack can be listed and bought, but a buy order does not
  match it.
- Search and paginate the Trading Terminal; verify a price that rose after the quote is rejected at
  execution and one that fell is charged at the lower price. Sellers are never named.
- Place a Player Shop, stock it, and sell from it while its owner is offline and its chunk is
  unloaded. Remove a stocked shop with an explosion and with `/setblock ... air`: the stock appears
  in the owner's **vault**, and the next shop placed there belongs to its placer.
- `/eco market mine` lists the caller's own orders, shops, jobs and auctions;
  `/eco market cancel <id>` and `/eco order cancel <id>` return goods to the vault and escrow to the
  claim. Another player's id answers exactly like an unknown id.
- Restart the server with open orders and expire some (`defaultListingDurationHours=1`) to verify
  refunds. Test full inventories and rapid clicks for loss or duplication.
- Verify the `neroeconomy:untradeable` tag blocks listing, offering and trading.

## 5. Direct trade

- A runs `/trade B` (and separately sneak-uses on B with an empty hand). B accepts. Both are within
  `tradeRangeBlocks`. Each side puts stacks and credits in; changing anything on either side clears both
  confirmations. After both confirm, the three-second freeze runs and the swap happens once.
- Walk out of range, die, disconnect, close the window and change dimension in separate runs: each
  cancels the trade and returns everything to its owner.
- With both players at an economy terminal, trade from any distance.
- Stop the server with a trade open (kill the process): on restart everything on the table is in its
  owner's vault.
- `/trade mute true` makes a request from the other player fail without telling them why.

## 6. Offers

- A sends B an offer of items and credits asking for other items in return. B accepts in game; verify the
  swap is atomic, `tradeTaxPercent` applies if set, and each side's goods land in the other's vault.
- A sends another and B declines, a third and A cancels, a fourth and it expires
  (`offerDurationHours=1`): in each case everything goes back to A (items to the vault, credits to the
  balance) and A gets an alert on expiry.
- Fill B's inbox: A cannot have more than four open offers with the same recipient, and a muted
  recipient gives the same answer as a full inbox.

## 7. Auto-trade

- A creates a buy rule from the vault (`/eco rule add buy item minecraft:wheat 4 64 500`): the budget is
  escrowed at once and an order appears on the book. B sells wheat to it; the goods land in A's vault.
  Delete the rule and verify the unspent budget returns to A's balance.
- A creates a sell rule for items in the vault and verify it lists them and sells to B's buy order.
- Place an Auto-Trader block next to a hopper, feed an input slot, and verify the sell rule lists the
  goods; verify the outputs can be extracted and the inputs cannot. Unload the chunk and verify the
  rule shows **Unreachable**. Break the block and verify the rule is deleted and nothing is lost.
- Put A in creative mode while a hopper feeds the Auto-Trader: the sell rule must not list goods.
- Verify rule statuses (price not met, out of budget, nothing to sell, daily cap with
  `ruleDailyValueCap=1`, paused) and the alerts on a stop or completion.

## 8. The exchange

- At the Exchange Terminal, buy shares of a steady and of a risky company, and the index fund. Verify
  the quote shows the ask, the fee, the total, and that a sale is refused during the holding period
  (`shareHoldMinutes`) with the time left.
- Run steps (`/eco admin sim step 6`) and verify prices move within the per-step and per-day limits,
  that a company at its daily limit is halted and refuses trades, and that nothing A buys or sells
  moves a price.
- Sell at a gain and at a loss: the principal comes back in full, the gain is paid from the Reserve.
  With `exchangeDailyBudget=1`, a sale with a gain is refused until A accepts the exact reduced
  amount shown.
- Buy a 1-day Savings Bond; cash it in early (principal only) and buy another and let it mature
  (principal plus interest, and the alert). With a locked starting balance, a share or bond purchase is
  refused with a plain message.
- Verify position limits (`maxPositionPerStock`, `maxPortfolio`, `maxBondsPerPlayer`).
- Run steps across a day roll and verify a dividend is paid to units held a full day, with the alert;
  with `exchangeDailyBudget` small, verify the dividend is scaled down and the factor is shown.
- Remove a company's data file, run `/reload`, and verify its holders are paid out and the alert appears.
- **Practice mode:** trade with play credits; `/eco invest practice reset` once; verify no real balance,
  transaction row or alert changes.
- `exchangeEnabled=false` refuses trades but still allows cashing in a bond.

## 9. Merchant Houses, simulation and an empty server

- At a Merchant Stall, buy from and sell to a House. Verify the ask is above the bid, buying raises the
  ask and selling lowers the bid and both recover over steps, per-account daily caps stop further
  trade, and the House's money comes from the Reserve (`/eco admin houses`).
- Run `/eco admin reload-check` and verify it lists no failing commodity with the shipped data.
- **Empty-server catch-up.** Set `simStepMinutes=1`, let the server run, then stop it, move the system
  clock forward a few hours (or wait), and start it with nobody joining: `/eco admin sim` from the
  console shows the simulation catching up to the present (a few steps per tick), prices have moved, and
  the replay is never more than `simCatchUpMaxHours` of steps. A step never runs twice.
- Stop the server in the middle of a catch-up and start it again: it carries on where it stopped.
- Let two accounts trade the same item back and forth many times: its reference price must not move,
  while three or more different pairs moving it do (`referenceMinPairs`).

## 10. Jobs, bounties and auctions

- Post a contract end to end: `/eco contract post item minecraft:iron_ingot 16 50`, claim it as B,
  `/eco contract fulfill <id>`, collect the payout (B) and find the goods in A's vault.
- Post by tag without the `#`, post a delivery and fulfil it away from the destination (refused) and
  within four blocks (accepted), and post a bounty with a quantity that pays on the last kill only.
- Verify the refusals: unknown item, empty tag, target over 64 characters, a renamed or damaged stack at
  fulfilment, and the `maxJobsPerPlayer + 1`th job. `bounty post player` is absent below level 2.
- Claim a job, release it, and set `jobClaimTimeoutMinutes=1` to see it reopen by itself.
- Auction with two bidders: outbid refund, late extension, winner gets the lot in the vault, seller gets the
  winning bid less `auctionFeePercent`, and an interplanetary lot degrades to local without orbit
  progression.
- Merchant Houses and the server post jobs and bounties paid from the Reserve (set
  `systemBountiesPerDay=32` and step the simulation): a posted bounty's payout is escrowed at posting
  and an expired one returns to the Reserve.

## 11. Credit Chip

- Redeem one Credit Chip at a Bank Terminal: the first pays `creditChipValue`, the second 80 %, and so
  on down to 20 %; the sixth is refused and kept (`creditChipDailyLimit=5`).
- With a second and third account, verify the total drawn stops at `creditChipDailyBudget`. A creative
  player is refused.
- With Nerospace installed, confirm the chip appears in the exotic meteor material list.

## 12. Companion app and offline trading

- With NeroLink and the app: the summary, orders, vault, stocks, portfolio, offers, jobs and auctions
  screens load for A and show nothing about B except "A player".
- **Offline app trade.** Log A out. From the app, place a buy order, place a sell order from the vault,
  set a vault-backed rule, buy a share, buy a bond, send `pay` to B, send an offer, and collect
  claims. Each works with A offline; B's reply and fills land in A's vault and balance; a vault deposit
  or withdrawal from the app is refused because the player must be in game.
- Replay a request with the same request id: it acts once. Switch `remoteTradeEnabled=false`: every app
  action is refused.
- Spend past `remoteDailySpendLimit`, and send goods away past `remoteDailyGoodsLimit` (a sell order
  from the vault at price 1): both are refused with the limit and what is left.
- Change the balance in game while the app is open and confirm it updates without a refresh; raise an
  alert of each kind that is easy to trigger (order filled, offer received, vault full) and confirm
  the text names no player, amount or item.

## 13. Crash safety (inventory boundary)

The economy store is saved with the game's own save, together with player data and chunks. For each
case below, do the action, wait **two seconds**, kill the server process hard (`kill -9`, or end the
process in the task manager), restart, and verify the stack is in **exactly one place**: the
inventory it was in before the last save, not in two places and not lost.

- Deposit a stack to the vault.
- Withdraw a stack from the vault.
- Put a stack into a direct-trade slot.
- Post a sell order from the hand.
- Send an offer from the hand.
- Put a stack into an Auto-Trader slot.

Then do the same after `/save-all`: the state after the action must be there.

## 14. Fair play spot checks

- Create two alternate accounts with locked starting credits: neither can pay, offer or trade the credits to
  the other, and neither can put them into shares or bonds, though they can pay the Merchant Houses.
- Try to sell from the hand in creative mode, put an item in the vault, stock a shop, send an offer from the
  hand, and redeem a Credit Chip: each is refused.
- Click Buy as fast as possible: purchases stop being accepted beyond the rate limit without error
  spam, and no credit or item is lost or duplicated.
- Send to a random UUID that has never joined: the terminal names an unknown recipient and opens no
  account. With `/eco admin set <player> 100000`, every screen shows 100,000.
- `adminMutationsEnabled=false` removes `admin give|take|set`, `admin market`, `admin sim step` and
  `gallery`, while `/eco admin supply` still works.

## 15. Privacy, export and erasure

- Create more than `historyLimitPerPlayer` transactions, an order, a buy order, an offer sent and an offer
  received, a rule, a share and a bond, a practice portfolio, an Auto-Trader, a shop, a job, an auction
  bid, vault contents, an uncollected claim, House trades and an app action. Run `/eco export A` and
  open `<world>/neroeconomy-exports/<uuid>.json`. Check that it holds every row inside the retention
  window and the `market`, `book`, `offers`, `rules`, `trade_escrow`, `obligations`, `shops`,
  `auto_traders`, `vault`, `exchange`, `merchant_houses`, `remote_spend`, `sibling_payouts` and
  `milestones` sections plus `starting_balance_vesting` and `handbook_given`, that the other party of every row reads
  `counterparty`, and that no other player is named and no simulation secret appears.
- On Linux, confirm the export directory is mode `700` and the file `600`. Restart with an export older
  than seven days and confirm it is deleted at start.
- Toggle `/eco privacy logging true` and verify new rows stop appearing in A's history.
- **`/eco erase A`: every store is emptied of A.** Verify with a second export of A (everything empty),
  and by checking that B's side is whole: B's offer to A was refunded (credits as a claim, goods in B's
  vault); a bid B held against A's auction was refunded; B's goods in an open trade with A were returned;
  A's orders, rules, offers, vault, holdings, practice portfolio, House counts, remote-spend row,
  Auto-Trader and shop ownership are gone (the blocks stay, ownerless, and anyone can break them); the
  escrow, exchange principal and rule budgets of A moved to the Reserve (`/eco admin supply`
  still balances); and rejoining does not pay a second starting balance or a second Handbook.
- Run `/neroland data eraseme` as another player and confirm the same.

## 16. Gallery and development tooling

- A creative-mode player below permission level 3 has no `gallery` command; a level-3 operator in
  survival is told to switch to creative; `adminMutationsEnabled=false` removes the command.
- `/eco gallery` builds the clusters. Sample rows cannot be bought or filled and are invisible to an
  ordinary player's search and to the companion app; the Auto-Trader and tickers are samples that
  affect nothing. Restart the server and run `/eco gallery clear`: exactly the blocks and label stands
  the gallery placed are removed, and a real shop inside the footprint is left standing.
- In a release jar there is no `/necgallery` command and no `neroeconomy:capture` dimension.

## 17. Clocks and persistence

- Stop the server, move the system clock forward by a week, start it, and confirm listings and auctions
  lost at most 72 hours of their remaining time. Move the clock back and confirm nothing stalls.
- Confirm the combined store and Core's backup of it, and the marker, are in the world's `data` folder.
- **Migration from 0.2** (use a copy of a 0.2 world with listings, buy offers and item claims): start
  with this build and verify item claims are now vault contents, listings and buy offers are orders
  with their old ids, escrow balances (`/eco admin supply` and `/eco admin health` report balanced),
  and the Reserve was filled once. Restart and confirm nothing is imported twice.

## 18. Cross-mod API (I1)

No test mod or data pack ships for this, so an owner checks it through what the economy shows and, where a
sibling mod that uses the API is installed (NeroQuests, NeroColonies, NeroFactions, NeroNotes or NeroEvents),
through that mod. Without one, do the first three bullets and the config checks, and record the rest as not
run.

- **Payouts and budgets.** With a sibling that pays credits installed, make it pay A (for example finish a
  quest). A's balance rises by the stated amount and the history row reads as a payout from another mod.
  `/eco admin supply` shows the Reserve falling by the same amount and the sibling budget line rising.
  Set `siblingPerPlayerDailyCap=50`, trigger a payout above it and verify A is refused with a plain
  message and no credit moves; set it back. Set `siblingCallerBudgets` to the mod's id with a small amount
  (`modid=100`) and verify it stops paying at that amount while the shared `siblingDailyBudget` is untouched.
  Repeat the same action with the same request (for example claim a reward twice) and verify it pays once.
- **Reserve and freeze.** Lower the Reserve with `/eco admin take` (or set `reserveAlertLow` above it) and
  verify a payout that the Reserve cannot cover is refused before anything moves. With the economy frozen
  (move `neroeconomy:economy` aside, as in section 1) verify no sibling can pay or charge.
- **Transfers and accounts.** If a sibling uses `transfer` (a player-to-player payment) verify the payee gets
  the amount less `siblingTransferFeePercent`, rounded up, the fee reaches the Reserve, and a starting balance
  that has not vested cannot be sent. If a sibling keeps accounts (a faction treasury), verify
  `/eco admin supply` lists a line per kind, that the line is marked as no longer claimed after that mod is
  removed, and that `/eco admin health` still says the economy is balanced.
- **Gates.** As A, make a first sale, a first purchase, a first Savings Bond, a first investment and have an
  offer accepted. Each opens the matching `neroeconomy:` gate (a quest pack with a gate objective, or any
  Core gate view, shows it within a second or two). Make a hundred sales (a loop at a Merchant Stall is
  enough) and verify `trader_100` opens once. Make A's first sale while A is offline (a resting order fills)
  and verify the gate opens on A's next join. `/eco export A` lists the reached milestones and no one else's.
- **Class-free channels.** With a sibling or a Core debug listener on `neroeconomy:reserve`,
  `neroeconomy:house_stock`, `neroeconomy:market_shock` and `neroeconomy:sector_index`, verify: the Reserve
  channel fires once going below `reserveAlertLow` and once on recovery (within about 30 seconds); a House that
  sells out fires `house_stock` once; a shock fires `market_shock` when it starts and ends; a sector index
  moving past the `sectorBands` level from its day open fires `sector_index` once per crossing.
- **Shock requests.** From an allowed mod (named in `shockSources`; NeroEvents by default) request a shock on
  an item. The price of that item moves for the requested time and no longer than `shockMaxMinutes`; a clear
  request ends it early. Remove the mod from `shockSources` and verify its requests do nothing. Past
  `maxActiveShocks` further requests are ignored. The log holds at most a debug line for an ignored request.
- **Commodity defaults.** `/eco admin reload-check` lists a sibling's default commodities with the source
  `code:<modid>`; a data pack entry for the same key replaces the default; an excluded item is not offered by
  any House.
- **Privacy.** The log shows no payment, amount, player name or UUID from any of the above at info level.
  Erase A (`/eco erase A`) and verify the second export has empty `sibling_payouts` and `milestones`, and that
  A's gates are not re-opened by the next sale.

## 19. Performance at scale (MSPT)

Measure on a dedicated server, not a dev client, with the same world for both runs. Use
[spark](https://spark.lucko.me) (`/spark tps` and `/spark profiler --timeout 120`) or, without it, the
vanilla debug screen (F3, the tick-time graph) on a client joined to that server. Record the average and the
95th percentile of the tick time (MSPT), not only TPS.

1. **Baseline.** Start the world with `autonomousEconomy=false` and no players trading; record MSPT for two
   minutes while two players stand still.
2. **200 accounts.** Create 200 accounts with a balance (give them credits with `/eco admin give` from a
   console script, or join test accounts). Re-run the measurement with the economy on (`simStepMinutes=10`)
   and note the cost of a simulation step: in spark, find the `neroeconomy` entries under the server tick.
3. **5,000 orders.** Fill the book with 5,000 resting orders across a spread of items and prices (a console
   script of `/eco order sell` and `/eco order buy` as test accounts, or Auto-Trader rules, kept under
   `maxListingsPerPlayer` and `maxRulesPerPlayer` per account). Repeat the measurement while one account
   places and cancels orders every second, and again across a simulation step.
4. **Suggested targets** (the owner sets the real pass mark; none is measured yet). The economy adds no sustained load above 1 ms a tick on average, no single tick above 10 ms
   outside a simulation step or save, and a simulation step stays well under the 50 ms tick budget (it may
   take several ticks if it spreads). Record the numbers in the test notes; if a target is missed, attach
   the spark report and the counts (accounts, orders, rules) it was taken at.
5. Stop the server cleanly and confirm the save time did not grow beyond what the store size explains.
