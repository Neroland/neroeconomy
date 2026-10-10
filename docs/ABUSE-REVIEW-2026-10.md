# Abuse review, October 2026

The 0.3 abuse review: what was attacked, what held, what was fixed and what is accepted. It answers
the list in [DESIGN-0.3](DESIGN-0.3.md#abuse-rules) (the section 13 list in the build notes) line by
line. For the 0.2 baseline see [AUDIT-2026-10](AUDIT-2026-10.md), which this review mostly supersedes.

Status: all findings that were fixed are in the working tree with a test; the full nine-cell build
matrix (three loaders by three Minecraft versions) and the unit tests were green after the last fix
(1009 tests on the 26.2 node). Nothing here could be played end to end: items that need a running game are
listed under [Not verifiable without a running game](#not-verifiable-without-a-running-game).

## Method

1. **Independent red team.** A reviewer who had not seen the implementation was given the design
   document, the public wiki, the source tree, the companion-app API and the abuse list, and told
   to break the economy by reading. Every attempt was written down as attack, trace through the
   code, result and severity, and ranked. It was read-only: nothing was executed, so a result of
   "works" means "works by reading the code", and each such result was reproduced as a failing
   test before it was fixed.
2. **Coverage map.** A second reader mapped each line of the abuse list to the tests that assert
   it, opened and read each test, and graded the cover: *strong* (a test asserts the property
   directly, including the failure side), *partial* (the pure rule is tested but the game wiring,
   a sub-case or a combination is not) or *none*. It also recorded the gaps and the test that
   would close each one.
3. **Fix passes.** Two writer passes took the findings in severity order. For each fix a test was
   written first and shown to fail against the old code, then the fix was applied and the test
   passed; the quick build set and, at the end of each pass, the full matrix were run.
4. **This document** records the outcome now, after both fix passes, and re-grades the coverage map
   against the tests that exist today.

Outcome words used below:

- **Blocked**: the attack fails, and says by what.
- **Fixed**: the attack worked and no longer does; the fix and its test are named.
- **Accepted**: the attack works or cannot be ruled out, the cost or the damage is bounded, and the
  reason is written down.
- **Open**: not done in this release; listed again at the end.

## Findings at a glance

| # | Finding | First verdict | Now |
| --- | --- | --- | --- |
| R1 | A stolen app token moves the whole vault out | High, works | Fixed |
| N1 | Two alternate accounts walk a Merchant House reference price to the band edge | High, likely works | Fixed |
| D1 | A crash after an inventory move duplicates or loses goods | High on crashable servers | Fixed, with a tiny residual |
| C1 | A creative player redeems Credit Chips | Medium to high, works | Fixed |
| A1 | Starting-balance lock laundered through bonds and shares | Medium, works | Fixed, with an accepted House residual |
| N2 | Recipe arbitrage opened by drift lives until the daily check | Medium, likely works | Fixed |
| A2 | Credit Chips across many accounts out-drain the daily mint | Medium, works as designed | Fixed |
| E4 | Linked-commodity volume used to pump a held share | Low, unsure | Accepted, calibration test open |
| A4 | Alternate accounts monopolise the exchange budget with bonds | Low, griefing | Accepted |
| C3 | Gallery blocks are real, minable blocks | Low, operator-initiated | Open |
| F1 | A victim's incoming offers filled at no cost | Low, griefing | Fixed (pair limit), mute remains |
| F2 | Audit rows rotated out by flooding | Low, unsure | Fixed |
| F3 | House account rows exhausted so newcomers are unserved | Low, in theory | Accepted |
| R2 | Request-id window | Low, blocked | Blocked, documented |

The coverage review found three more defects beyond the red team's list, all fixed: typed
administrator credit calls from outside the admin code were not counted (O1), a vault slot that
grew kept the extra goods without the entry check (D2 in the red team's numbering), and the
wallet-pay action passed an unbounded amount to the payment call (found by the new extreme-number
test, see I4).

## Every attempt, by area

Each entry gives the attack, the outcome now, and what holds it. Test names are in
`common/src/test/java/za/co/neroland/neroeconomy/`. Letters are the red team's own numbering.

### Duplication

**D1. A crash right after an inventory move (fixed).** Attack: deposit a stack, put it in a trade
slot, list it from the hand or offer it, wait a second, then crash the server (a lag or memory
exploit, or a host crash) before the next autosave of player data. The store had been saved about a
second after the move but the player's file had not, so after the restart the goods were in both
places; the reverse path (withdraw) lost them. This was the one place the "one store, one save"
principle did not reach. Now: nothing in the mod saves the store on its own schedule. The store is
saved only by the game's own save, which writes player data first and then chunks and saved data in
one pass, so a hard kill rolls the inventory and the store back to the same save. Fixed by removing
every early-save request (about twenty call sites and the save-cooldown machinery in the currency
provider). Test: `text/SaveOrderAuditTest#nothingWritesTheEconomyStoreAheadOfTheGamesOwnSave`, a
source scan that fails if any main code requests, schedules or joins a store save. Residual,
accepted: a crash in the instant of a save, while the player files are written and the store is
still being written. That window is milliseconds and is the same class of risk as any item in
vanilla Minecraft.

**D2. Vault menu tricks: shift-click, number keys, drag, clone, creative top-up (blocked, then
tightened).** Every path that raises a vault slot's count goes through the vault's entry check
(cap, goods-source rule, tradeable rule), and the creative set-slot packet only reaches the
player's own inventory menu. Blocked by reading, but fragile: a slot holding the same goods kept
any larger count without re-checking. Fixed: `vault/VaultSlotRule` now lets a grown slot keep only
the part the vault would have accepted and hands the surplus back. Test:
`vault/VaultSlotRuleTest#aSlotThatGrowsKeepsTheAddedGoodsOnlyIfTheyMayEnterTheVault`.

**D3. Two players on one listing in the same tick, double-click storms, replayed and reordered
packets (blocked).** Every value move runs on the server thread and the stores are synchronised;
request ids are spent in the persisted request ledger; a direct-trade confirmation carries a
revision and any change clears both confirmations. Tests include
`direct/TradeSessionTest#aRandomRunOfPacketsNeverSwapsAnythingButWhatBothSidesConfirmed`
(400 runs of 60 random packets, stale revisions among them),
`market/RequestLedgerTest#aReplayedIdIsRefusedForTheSamePlayerOnly`,
`book/BookPropertyTest#anySequenceOfOrdersLeavesNoCrossAndConservesEverything`,
`economy/OperationFuzzTest#aCrashAtAnyPointLosesNothingAndTheRunCarriesOnExactlyAsIfItHadNotHappened`
and `economy/OperationFuzzTest#aHundredThousandMixedOperationsKeepEveryInvariantAtEveryStep`.
Disconnect, death, dimension change, chunk unload, full inventory and full vault each have a
test for the rule (see the coverage table).

### Input

**I1. Stale container id, menu not open, spectator, dead, rate limits (blocked).** The open-menu
guard requires the payload's container id to equal the open menu's, the player to be alive, not a
spectator, in reach, and within a per-player budget shared with the app. Now the id comparison is a
pure rule, `network/IntentRules.addressed`, with tests for the wrong id, the wrong menu kind and no
menu (`network/IntentRulesTest#aPayloadIsAddressedOnlyToTheOpenMenuOfItsKindByItsCurrentId`,
`#aBurstOfPacketsInOneTickIsHandledAtMostTheBudgetsBurstTimes`), and a source scan checks that
every serverbound payload is declared through the guard and that nothing else makes a handler
(`text/IntentWiringAuditTest#everyServerboundPayloadIsDeclaredThroughTheGuardAndNothingElseMakesAHandler`).

**I2. Negative, zero, `Long.MAX`, NaN (blocked; one hole found and fixed).** Player-supplied numbers
are whole numbers and are range-checked at every entry; the ledger refuses negative legs; NaN can
only come from configuration, data files or geometry and every such place is covered. A new
table-driven test sends the extreme values through every hub action and every companion-app
action: `hub/HubRouterExtremesTest#extremeNumbersInEveryActionAreRefusedOrNeverPassedOnUnbounded` and
`link/OfflineTradeProofTest#everyNumberAnOfflineActionReadsIsRefusedAtTheExtremesAndMovesNothing`.
It found that the wallet-pay action checked only that the amount was at least 1 and passed
`Long.MAX_VALUE` on to the payment call, where the ledger refused it. Fixed: the action now applies
the same 1 to maximum-credits range as the others.

### Arithmetic

**AR1. Fee rounding, order splitting, overflow (blocked).** A fee is `ceil(total x percent / 100)`,
computed without 64-bit overflow, at least 1 credit and at most the total, on the order total, so
singles pay at least as much as one order. House prices use exact multiplication and force
ask above reference above bid. Link numbers are capped at 10^12 and quantities at the order
maximum. Tests: `economy/FeesTest#aFeeIsRoundedUp`,
`#splittingAnOrderNeverPaysLessFeeThanTheWholeOrder`, `#theArithmeticIsExactAtTheLargestTotals`,
`exchange/ExchangeMathTest#hugeNumbersNeitherOverflowNorWrap`,
`#splittingAnOrderIntoSinglesNeverDoesBetterThanTheWholeOrder`. Residual: the order book multiplies
with plain `*` behind a prior overflow check on each path traced; nothing enforces that. See open
items.

### Alternate accounts

**A1. Starting-balance lock laundered through the exchange (fixed).** Attack: a new account holds
100 locked credits. Locked credits may be spent with system counterparties, and a spend shrinks the
lock, but credits coming back from a system account were never re-locked. So buy a bond with the
locked balance, redeem it early (principal returns at once, no fee) and pay the main account, with
no wait and no cost; shares did the same after the 60-minute hold, which runs on wall time while
vesting runs on play time. Now: share and bond principal is taken only from credits the account
has earned (`LedgerPlan.movePlayerFacing`); the exchange fee may still come from locked credits
because nothing returns it. A purchase that would need locked credits is refused with a plain
message. Tests: `exchange/LockedStartingCreditsTest#aBondBoughtWithLockedCreditsAndCashedInEarlyUnlocksNothing`,
`#sharesBoughtPartlyWithLockedCreditsAndSoldUnlockNothing`,
`#earnedCreditsStillInvestAndTheFeeMayComeFromTheLockedPart`, and the rewritten
`exchange/ShareTradingTest#theStartingBalanceLockStopsAPurchaseButMayPayTheFee` (the old test had
asserted the exploitable rule). Accepted residual: goods bought from a Merchant House with locked
credits. A House sells above its reference and buys below it, so the goods return at most their bid
value, less than was spent, and no more than the starting balance (default 100) per account;
goods carry no provenance in the vault or the book, and blocking their resale would need taint
tracking across every store. Vesting already accepts House purchases and fees for the same reason.

**A2. Credit Chips across many accounts (fixed).** Attack: each alternate account crafts and
redeems its daily chips (300 credits a day at default steps) from the Reserve; about 17 accounts
out-drain the daily mint of 5000. The cap was per account only. Now: all accounts together may draw
at most `creditChipDailyBudget` (3000) a day, through the Reserve's existing budget machinery,
shown in the supply report like the other budgets. Test:
`economy/CreditChipRulesTest#chipsAcrossManyAccountsDrawAtMostTheServerWideDailyBudget` (40
accounts, 3000 drawn).

**A3. Wash trades, shill bids, self-matching (blocked).** The book never matches an account with
itself; two accounts washing a trade pay the market fee and the listing fee on every round trip, and
both go to the Reserve. A seller cannot bid on their own lot, and a bid cannot be funded from
locked credits; an alternate account shilling is bounded by the 5 % auction fee on the inflated
win. Tests: `book/BookSectionTest#anAccountNeverMatchesItselfAndItsOwnOrderDoesNotBlockTheNextOne`,
`book/TradedVolumeTest#fillsBetweenTheSameTwoPlayersCountUpToThePairCapWhicheverOfThemSold`,
`rules/RulesSectionTest#twoOpposedRulesTradingForManyDaysNeverComeOutAhead`,
`obligation/ObligationRulesTest#aLaterBidMustBeatTheStandingOneByThePercentage`. The self-bid
refusal itself is in code only (see open items). Moving a reference price with alternate accounts
was the open question here: it is attempt N1 below.

**A4. Alternate accounts monopolise the exchange budget with bonds (accepted).** Attack: right after
the day roll, many accounts buy bonds so the reserved interest uses up `exchangeDailyBudget`
(15000) and real players' gains and dividends are cut that day. This is griefing, not profit
beyond ordinary bond interest. Bounded by the per-account bond and portfolio caps and by the
global budget by design; gains the budget cannot cover are refused unless the player accepts the
reduced amount, and principal always returns in full. Not changed.

### Exchange

**E1. Beating the holding period with `/tick`, `/time`, sleeping or the clock (blocked).** The
economy clock is wall time passed through a monotonic offset, never game time, so none of those
move it. A host clock jumped forward does advance economy time; only an operator can do that, and
it is documented under Operators. Tests: `simulation/SimClockTest#theSameTimeAlwaysNamesTheSameStepWhateverElseIsGoingOn`,
`economy/EconomyClockTest#backwardsJumpIsAbsorbedSoEconomyTimeNeverRunsBackwards`,
`exchange/HoldingTest#aLotCannotBeSoldUntilTheHoldingPeriodHasPassed`.

**E2. Position limits by splitting or by index fund (blocked).** The position and portfolio caps count
cost across shares, the index and bonds. Across alternate accounts the limits multiply (the caps are
per account); the damage is bounded by `exchangeDailyBudget` on gains. Tests:
`exchange/ShareTradingTest#aPositionIsCappedAtCostPerStock`,
`#thePortfolioIsCappedAtCostAcrossSharesIndexAndBonds`.

**E3. Predicting the next price (blocked).** Each random term is a keyed hash of a per-world secret,
the stock and the step. The secret is 32 bytes from `SecureRandom`, lives in the simulation
section, and is read only by the simulation. Tests:
`simulation/SimulationSectionTest#theSecretIsMadeOnceIsThirtyTwoBytesAndSurvivesASave`,
`#theSecretIsOnlyInTheSimulationSectionOfTheSavedStore`. Not yet asserted: that no hub page, link
snapshot or export carries it (open items).

**E4. Moving a share price through linked-commodity volume (accepted).** Attack: hold a steady
stock, wash-trade its linked commodity between alternate accounts for six steps, sell after the
hold. The nudge is at most half a percent a step and counted volume is capped per pair (250) and per
account (1000) per step; on default settings the best case is about 3 % an hour on a position of at
most 5000 credits, about 150, against the spread, two 1 % fees and a 2 % market fee on every wash
fill. Unprofitable on defaults; a calibration test with four accounts is an open item.

### NPC liquidity

**N1. Two alternate accounts walk a Merchant House reference price (fixed).** Attack: each step,
account A lists one unit of a commodity at 16 credits and B buys it, at a cost of about 2 credits in
fees a step. The per-step clamp, not volume, was the binding limit, so one 16-credit wash per step
gave the full +1 %; after about 17 hours the reference doubled, and the attacker sold real goods to
the House at the doubled bid, taking most of the day's NPC budget out of the Reserve. Now: a
reference moves only when at least `referenceMinPairs` (3) distinct pairs of accounts traded
the commodity since the last step, each pair is clipped to the value of the pair ranked that many
places from the top, and the pair tally is kept in memory only, as one-way hashes, cleared every
step. Over a day a reference stays within `referenceDriftDayPercent` (10 %) of where the day
began. Tests: `house/ReferenceDriftTest#tooFewPairsCountForNothingAndNoPairOutweighsTheOneRankedAtTheMinimum`,
`#aDayNeverTakesTheReferenceFurtherThanItsReachFromWhereTheDayBegan`,
`house/HouseSectionTest#twoAltsWashTradingEveryStepForTwoHundredStepsMoveNoReference`. The old test
had asserted that two accounts did move the reference.

**N2. Recipe arbitrage opened by drift, checked once a day (fixed).** The arbitrage guard now
re-checks in every step in which any reference moved, as well as in the daily run, so a
profitable chain is switched off in the same step, not the next day. Test:
`house/HouseSectionTest#aReferenceThatMovesIsCheckedByTheGuardTheSameStepNotTomorrow`.

**N3. Reserve below zero (blocked).** A ledger plan refuses any leg out of the Reserve larger than
its balance before anything moves, and budgets are checked in the same all-or-nothing step. Tests:
`economy/ReserveTest#theReserveNeverGoesNegativeUnderAnyRunOfPayments` (20 000 random payments),
`#aPaymentTheReserveCannotCoverIsRefusedBeforeAnythingMoves`.

**N4. House caps by many small fills (blocked).** Allowances are computed from per-account and
per-day totals under the store's lock; across accounts the per-commodity demand and the global
`npcDailyBudget` bound the total. Test:
`house/HouseSectionTest#aFarmBotSellingTheMostItCanEveryStepForAWeekNeverDrawsMoreThanTheBudgetADay`.

### Vault and items

The red team found no direct attack here beyond the vault-menu paths in D2 above. The coverage
review found that the size-bomb and `untradeable` rules were tested as pure rules but not at each
entry. Now `text/GoodsEdgeAuditTest#everyEdgeAsksWhetherAStackIsTradeableBeforeItBecomesALot` reads
the eight entry points (vault deposit, hand sell, direct-trade slot, offer send, book, Auto-Trader
input, shop stock and the app paths) and fails if one turns a stack into a lot without asking
`MarketSavedData.tradeable`, and checks that the unchecked conversion `lotOf(` is called only from
system delivery and migration paths. Renamed-item scams in direct trade are blocked by the
confirmation rule (any change clears both confirmations, then a three-second freeze) and by the
tooltip that shows the true item id; the tooltip builder is client code without a test (open items).
Modified stacks never merge with unmodified ones:
`vault/VaultTest#aModifiedStackNeverMergesWithAnUnmodifiedOne`,
`market/LotsTest#mergingNeverMixesAModifiedStackWithAnUnmodifiedOne`.

### Remote (companion app)

**R1. A stolen token empties the victim's vault (fixed).** Attack: with the victim's bearer token,
send `offer_send` with up to nine vault lots to the thief's account, repeat per batch; or place a
sell order at 1 credit and buy it from the thief's account; or set a vault-backed sell rule at 1;
or have the thief offer the victim an exchange that asks for the victim's goods and accept it. The
remote limit counted credits only, so the whole vault (54 stacks, shulker contents included) could
move in about six offers, bounded only by the per-action rate limit. Now: goods leaving an account
through the app count against a separate daily limit, `remoteDailyGoodsLimit` (5000 credits' worth),
checked before the action and recorded after it succeeds. Counted: goods in `offer_send`, sell
orders from the vault, sell rules (and resuming a paused one), goods an accepted offer asks for, and
a sell from the hand in the older trade action. A unit counts the higher of its data reference and
its live House reference; an unmodified non-commodity counts `remoteGoodsFloorValue` (10); a
modified one counts `remoteModifiedGoodsValue` (500) or its item's reference if higher. Goods are
valued at full worth, not by the price asked, so a sell order at 1 counts the same as one at fair
price. The two daily limits are counted apart and together are what a stolen token can cost in a
day. Tests: `link/OfflineTradeProofTest#aStolenTokenCannotEmptyTheVaultWithGoodsOnlyOffers`,
`#aStolenTokenCannotSellTheVaultForNothingOrGiveItAwayByRuleOrAcceptedOffer`,
`link/RemoteGoodsValueTest` (three tests: reference, floor and modified values, saturation),
`link/RemoteSpendSectionTest#goodsAreCountedApartFromCreditsAndBoundedTheSameWay` and
`#aGoodsOnlyRowSurvivesASaveAndAnOlderRowLoadsWithNoGoods`. The goods row is part of the per-player
export and is erased with the player.

**R2. `requestId` replay (blocked).** The app server's in-memory duplicate check runs on the network
thread and records after the server-thread run, so two identical requests sent at once both pass
it. Every value-moving path also spends the id in the persisted request ledger on the server
thread, which refuses the second. The ledger keeps the latest 128 ids per player for 10 minutes;
an id older than that, or pushed out by 128 newer ones, is accepted again. Only the owner or token
holder can produce that many requests and a replay gains them nothing a fresh id would not, so this
is accepted and documented in the wiki. Test:
`link/LinkPayloadsRequestTest#aReplayedRequestIsReportedAsADuplicateByEveryStore`.

**R3. Remote spend maths (blocked for credits, now for goods too).** Bounds are the price ceiling
times quantity for purchases, quantity times unit price plus the fee for buy orders, the rule's
total for buy rules, the principal for bonds and the amount for payments; the check is overflow
safe. Test: `link/RemoteSpendSectionTest#aStolenTokenCanSpendAtMostTheDailyLimitWhateverItSends`
(50 rounds of 1 up to `Long.MAX_VALUE`).

**R4. `readOnly` and `actionsDisabled` (blocked).** NeroLink refuses the action before dispatch and
the economy re-checks `remoteTradeEnabled` and the frozen state in its own gate. Test:
`link/LinkGateTest#aFrozenEconomyAndTheRemoteSwitchStopEveryActionFirst`. The `readOnly` and
`actionsDisabled` refusals themselves live in NeroLink and have no test in either repository (open
items).

### Flooding

**F1. Filling a victim's incoming offers (fixed for cost, mute remains).** Attack: four accounts fill
a victim's inbox of 64 for 48 hours with one-item offers, at no fee. Now one sender may keep at
most four open offers waiting with the same recipient (`TradeSettings.MAX_OFFERS_PER_RECIPIENT`),
so filling a 64-offer inbox takes at least 16 accounts. The refusal says the same thing as a muted
recipient or a full inbox, so it reveals nothing about the recipient. A recipient can still mute
offers. Test:
`offers/OffersSectionTest#oneSenderMayKeepOnlyAFewOffersWaitingWithOneRecipientSoAFewAltsCannotFillAnInbox`.

**F2. Audit rows rotated out by flooding (fixed).** The server-wide bound (`maxTransactionRecords`,
10 000) dropped the oldest row whatever it was, so a flood of one-credit payments could push an
earlier suspicious trade or an administrator's change out within hours. Now `economy/AuditRetention`
drops the oldest ordinary rows first; minting and burning rows (administrator grants, takes and
sets, Reserve mints, migration) and rows of 10 000 credits or more go only when they alone exceed the
bound. Retention by age is unchanged. Tests: `economy/AuditRetentionTest#aFloodOfSmallRowsDropsTheOldestSmallRowsAndNeverAnAdminRow`,
`#adminMintBurnAndLargeRowsAreTheOnesKept`, `#keptRowsGoOnlyWhenTheyAlonePassTheBoundOldestFirst`.

**F3. Request-id ledger, remote spend table, House rows (accepted).** All three are bounded with
tests. The House table holds at most 32 768 rows of account by commodity per economy day; a row
exists only after a real House fill, which pays the spread and the market fee inside the
per-account and per-commodity caps, and every row is purged at the day roll. Exhausting it takes, for
example, 100 accounts making real trades in all 328 commodities in one day, at which point newcomers
get no House service until the next day. Accepted for that cost. The wider question, stores that
are bounded by accounts times a per-account cap, is in the coverage table (F8).

### Creative and gallery

**C1. A creative player redeems Credit Chips (fixed).** Attack: take chips from the creative tab and
redeem them at a Bank Terminal; the item never called the goods-source rule, and each account drew
300 credits a day from the Reserve. Now `economy/CreditChipRules` refuses a creative, spectator or
endless-supply player, after the disabled check. Test:
`economy/CreditChipRulesTest#aCreativeSpectatorOrEndlessSupplyPlayerCannotRedeemAChip`.

**C2. Creative goods through containers (fixed in part, rest accepted).** Attack: a creative player
fills a chest, a hopper feeds an Auto-Trader, and a sell rule lists the goods; or a creative player
drops items for a survival account. The Auto-Trader menu already refused a creative player putting
goods in by hand and applying a sell rule. Now a sell rule is given an inventory that supplies
nothing while its owner is online and is not a goods source, so it lists nothing and moves nothing to
the vault; buy rules are unaffected and goods already listed stay listed. Test:
`trader/TraderRulesTest#aSellRuleTakesNothingFromTheSlotsWhileItsOwnerIsInCreativeAndListsAgainOnceTheyAreNot`
and `#aBuyRuleStillDeliversIntoTheSlotsWhileItsOwnerIsInCreative`. Accepted residual: a creative
owner who fills the slots by hopper and then logs out (the game mode of an offline player is not
known), and creative items handed over by any world means to a survival player. A mod cannot
defend either; creative mode is an operator grant, and this is written under Operators in the
[wiki](../wiki/Fair-Play.md).

**C3. Gallery blocks are real, minable blocks (open).** The operator-only gallery places real iron
blocks, chests and terminals that a survival player could mine. It needs an operator to build it,
it is counted (`GALLERY_BUILD`), its demo shops are flagged before any stock goes in and their stock
is destroyed rather than claimed, and nothing in it reaches the market, the Reserve or the link
snapshots (`economy/GallerySandboxIsolationTest#buildingAGalleryChangesNothingTheEconomyCanSee`).
Not changed in this release.

### Operators

**Admin mutations are counted and reason-coded (fixed).** Administrator credit changes were counted
in the admin code only, so the public typed deposit and withdraw calls, which any sibling mod can make
with an administrator reason, minted and burned without a count. Now the count is taken where the
ledger applies the change, after it succeeded and only for an amount above zero, so `/eco admin`,
the Admin tab and the provider's typed calls are all counted exactly once; a no-op set is counted
by the admin code. Tests: `admin/AdminMutationCountTest#aMutationThroughTheLedgerIsCountedOnceAndOnlyWhenItSucceeds`,
`#theProvidersPublicTypedCallsAreCountedToo`. "Reason-coded" means a fixed transaction type
(`ADMIN_GRANT`, `ADMIN_TAKE`, `ADMIN_SET`); there is no free-text reason, by design, because rows
never carry free text.

**Operators and `online-mode=false` servers are outside what a mod can defend (documented).** The
public [Fair play](../wiki/Fair-Play.md) page now carries the text: operators, offline-mode
servers, sizes under many accounts, and the app token. The design document's claim that this was
documented is now true.

**Privacy (checked briefly, no leak found).** `pay` and `offer_send` tell the caller whether a typed name has an account
here, which a payment screen needs; replies never echo the recipient; `/eco balance <player>` needs
level 2. Telemetry and alert payloads were not traced by the red team; the project rule is that they
carry no player name or identifier.

## Coverage of the abuse list

Every line of the list, graded as in the method: **strong** (a test asserts it directly, including the
failure side), **partial** (the pure rule is tested, the wiring or a sub-case is not) or **none**.
The first column is the grade on the day of the coverage review, the second is the grade now, after
the two fix passes and their new tests. A word in brackets is the reason a row is still partial.

### Duplication

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Disconnect | partial | partial | `direct/TradePresenceTest`, `TradeSessionTest#aLatePacketAfterACancelIsIgnored...`, `TradeEscrowSectionTest#releaseHandsEverythingOverExactlyOnce` (no test drives the live trade through a logout mid-freeze) |
| Death | partial | partial | same verdict tests; a swap whose receiver died between lock and swap is untested |
| Dimension change | strong (rule) | strong | `TradePresenceTest`, `network/IntentRulesTest#theSameCoordinatesInAnotherLevelDoNotCount`; the menu closing is vanilla's |
| Chunk unload | strong | strong | `trader/TraderRulesTest` unload tests, `TraderInventoryTest#anUnloadedBlockGivesNothingTakesNothingAndIsNeverWritten` |
| Full inventory | strong | strong | `market/MarketLimitsTest`, `direct/TradeSwapTest#whatDoesNotFitTheInventoryGoesToTheVaultAndIsNeverLost` |
| Full vault | strong | strong | `book/BookSectionTest`, `vault/VaultTest`, `direct/TradeEscrowSectionTest#recoveryBooksEvenIntoAVaultThatIsFull` |
| Crash between stores | strong (store), partial (inventory) | strong | `economy/OperationFuzzTest`, `text/SaveOrderAuditTest`; the store saves only with the game's save; residual is a crash in the instant of a save |
| Replayed and reordered packets | strong | strong | `RequestLedgerTest`, `direct/TradeSessionTest`, `hub/HubRouterTest`; the 10-minute, 128-id window is documented |
| Double-click storms | partial | strong | `network/IntentRulesTest#aBurstOfPacketsInOneTickIsHandledAtMostTheBudgetsBurstTimes`, `text/IntentWiringAuditTest`, `RateLimiterTest`; vanilla slot clicks and chat commands are outside this budget |
| Two players, one listing, one tick | partial | partial | `book/BookPropertyTest`, `economy/EconomySavedDataTest#synchronizedMutationSequenceConservesCredits` (the named two-buyers case is untested; the app server's timeout reply is open) |

### Input

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Negative quantities and prices | strong | strong | `book/BookSectionTest#malformedOrdersAreRefused`, `economy/EconomySavedDataTest#rejectsBadAmountsOverdraftAndOverflow` |
| Zero | strong | strong | same tests, `economy/FeesTest` |
| Overflow | strong | strong | `exchange/ExchangeMathTest#hugeNumbersNeitherOverflowNorWrap`, `market/CreditClaimsTest`, `economy/LedgerOutcomeTest` |
| `Long.MAX` quantities and prices | partial | strong | `hub/HubRouterExtremesTest`, `link/OfflineTradeProofTest#everyNumberAnOfflineActionReadsIsRefusedAtTheExtremesAndMovesNothing` (found and fixed the wallet-pay hole) |
| NaN | strong (with a note) | strong | `FeesTest`, `TradePresenceTest`, `IntentRulesTest`; a NaN fee percent in configuration is fail-open, so it relies on the config range check (open) |
| Stale `containerId` | partial | strong | `network/IntentRules.addressed` and its test |
| Acting without the menu open | strong | strong | `IntentRulesTest`, `HubRouterTest#anActionForAnotherTabIsRefused` |
| Out of range | strong | strong | `IntentRulesTest#aRemovedBlockOrOneOutOfReachClosesTheMenu`, `HubRouterTest#aRowThatWasNeverSentOrIsOfTheWrongKindIsRefused` |
| Spectator or dead | strong | strong | `IntentRulesTest`, `GoodsSourceTest`, `HubRouterF1bTest#goodsFromAnInventoryAreRefusedInCreativeOrSpectatorMode`; chat commands are not routed through the rule (open) |
| Rate limit on every intent packet | partial | strong | `text/IntentWiringAuditTest`, `RateLimiterTest` (11 tests); chat commands rely on the permission system only |
| Rate limit on every link action | strong | strong | `link/LinkGateTest#eachActionHasItsOwnLimitOnTopOfTheSharedBudget` |

### Arithmetic

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Exact multiply everywhere | partial | partial | `FeesTest`, `ExchangeMathTest`, House prices use exact multiplication; the order book multiplies behind an overflow check on each path read, with no scan that enforces it |
| Fees round up | strong | strong | `FeesTest#aFeeIsRoundedUp`, `direct/TradeSwapTest#theTaxIsTakenOnEachCreditSideRoundedUp` |
| Order splitting gains nothing | strong | strong | `FeesTest#splittingAnOrderNeverPaysLessFeeThanTheWholeOrder`, `exchange/ExchangeMathTest`, `exchange/ShareTradingTest#buyingOrSellingInSinglesNeverDoesBetterThanTheWholeOrderThroughTheDesk` |

### Alternate accounts

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Starting-balance farming | none (defeated) | strong | `exchange/LockedStartingCreditsTest` (3), `VestingTest`, `economy/CreditChipRulesTest#chipsAcrossManyAccountsDrawAtMostTheServerWideDailyBudget`; House goods bought with locked credits is an accepted, bounded residual |
| Wash trades to move prices | strong | strong | `book/TradedVolumeTest`, `house/ReferenceDriftTest`, `house/HouseSectionTest#twoAltsWashTradingEveryStepForTwoHundredStepsMoveNoReference` |
| Shill bids: a seller cannot bid on their own lot | partial | partial | book side tested; the auction self-bid refusal is code only |
| Minimum increment | strong | strong | `obligation/ObligationRulesTest` |
| Two alts' auto rules feeding each other | strong | strong | `rules/RulesSectionTest#twoOpposedRulesTradingForManyDaysNeverComeOutAhead`, `BookSectionTest#twoAccountsTradingTheSameGoodsBackAndForthUnderALargeDiscountNeverGainCredits` |
| Never store or compare IP addresses | partial | partial | a search of every source folder finds no address API; no scan guards against a regression |

### Exchange

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Holding period | strong (shares), partial (bonds) | strong | `exchange/HoldingTest`, `ShareTradingTest`; bond principal can no longer come from locked credits, which was the only thing the missing hold allowed |
| Position limits | strong | strong | `ShareTradingTest#aPositionIsCappedAtCostPerStock`, `BondTest#anAccountMayHoldOnlySoManyBonds` |
| No RNG leak | partial | partial | `simulation/SimulationSectionTest` (secret made once, 32 bytes, only in its own section); no scan of pages, snapshots and exports for the secret |
| Immunity to `/tick`, `/time`, sleeping | strong | strong | `simulation/SimClockTest#theSameTimeAlwaysNamesTheSameStepWhateverElseIsGoingOn` (no tick count is an input) |
| Clock jumps | strong | strong | `economy/EconomyClockTest`, `SimClockTest#clockJumpsNeverRunAStepTwiceAndNeverReplayMoreThanTheCap` |
| Breaker | strong | strong | `exchange/PriceModelTest` halt tests |
| Floor | strong | strong | `PriceModelTest#aPriceNeverFallsBelowTheFloorHoweverLongItFalls` |

### NPC liquidity

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Daily caps | strong | strong | `house/HouseSectionTest`, `economy/ReserveTest`, `house/HouseDealsTest` |
| Elasticity | strong | strong | `house/HousePricingTest`, `HouseSectionTest#buyingRaisesTheAskSellingLowersTheBidAndBothRecover` |
| Arbitrage guard | strong | strong | `guard/ArbitrageGuardTest` (19), `ShippedCommoditiesTest`, `HouseSectionTest#aReferenceThatMovesIsCheckedByTheGuardTheSameStepNotTomorrow`; recipes the reader cannot parse are skipped and counted, and a sibling mod's custom machine is invisible to the guard |
| Reserve can never go negative | strong | strong | `economy/ReserveTest#theReserveNeverGoesNegativeUnderAnyRunOfPayments`, `OperationFuzzTest`; an administrator take on the Reserve account has no test of its own |

### Vault and items

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Size-bomb stacks | partial | partial | `market/MarketLimitsTest`, `text/GoodsEdgeAuditTest` (every edge asks `tradeable`); no 1 MB fixture, and the size measure itself is untested |
| The `untradeable` tag | partial | partial | same; no test of a tagged item refused at each edge |
| Renamed-item scams in direct trade | partial | partial | `TradeSessionTest#everyChangeByEitherSideClearsBothConfirmations`, `LotsTest`; the tooltip is client code without a test |

### Remote

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Stolen-token damage bounded by the daily remote limit | partial (goods unbounded) | strong | `link/RemoteSpendSectionTest`, `OfflineTradeProofTest` (2 new), `RemoteGoodsValueTest` (3) |
| `actionsDisabled` honoured | partial | partial | enforced in NeroLink, untested in either repository |
| `readOnly` honoured | partial | partial | same |
| No value-moving action without a `requestId` | strong | strong | `link/LinkGateTest#aRequestIdIsRequiredByEveryAction`, `hub/HubRouterTest#anIntentWithoutARequestIdOrActionIsRefused`; the shop-buy and Auto-Trader payloads have no null-id test of their own |

### Flooding

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Listing fee | strong | strong | `book/BookSectionTest#theListingFeeIsChargedForEveryOrderAndAnOrderThatCannotPayItIsRefused` |
| Per-account cap on listings | strong (book) | partial | `BookSectionTest#thePerAccountCapsRefuseOneOrderTooMany`; job and player-auction caps and the number of shop blocks per player are untested |
| Per-account cap on rules | strong | strong | `RulesSectionTest#thePerAccountRuleCapAndMalformedRulesAreRefused` |
| Per-account cap on offers | strong | strong | `OffersSectionTest#nobodyCanSendMoreThanTheirCapOrFloodOneRecipient`, the new pair-limit test |
| Per-account cap on orders | strong | strong | `BookSectionTest`, `ShareTradingTest#anAccountMayMakeOnlySoManyExchangeActionsAMinute` |
| Bounded search cost | partial | partial | inputs are bounded (`HubRouterTest#badTextIsRefused`, `MarketLimitsTest`); no timing test at a full book |
| Snapshot size caps | strong | strong | `hub/HubPageTest`, `link/LinkV2ContractTest#historiesAndListsAreCutToTheirBounds` |
| A hard bound and a purge on every store | partial | strong | `text/StoreBoundsAuditTest#everySectionOfTheStoreIsBoundedAndSaysHow` classifies every section as hard-bounded or bounded by accounts times a per-account cap, with its purge; `AuditRetentionTest`. No store-wide cap was added to the ledger, vault, book, offers or rules: refusing deliveries or new accounts is worse than the bound, and on `online-mode=false` the account count is outside a mod's defence |

### Creative and gallery

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Nothing a creative player does puts value in | partial | partial | `CreditChipRulesTest`, `TraderRulesTest` (2 new), `GoodsSourceTest`, router tests; no per-call-site refusal assertion at every entry |
| Nothing the gallery does puts value in | strong | strong | `economy/GallerySandboxIsolationTest`, `GallerySandboxTest`, `DemoScriptTest`; no test that a demo shop refuses a real buy |

### Operators

| Item | Review | Now | What covers it now |
| --- | --- | --- | --- |
| Admin mutations are counted | partial | strong | `admin/AdminMutationCountTest` (2), `hub/HubRouterAdminTest` |
| Admin mutations are reason-coded | strong | strong | `economy/LedgerSupplyTest`, `hub/AdminPagesTest#theSupplyPageCarriesTotalsBudgetsAndReasonsOnly`; a fixed transaction type, no free text |
| Ops and `online-mode=false` are documented | none | strong | the public [Fair play](../wiki/Fair-Play.md) page; the README does not yet carry it (open) |

### Totals

63 items (the `/tick`, `/time` and sleeping row counts as three). Review: 40 strong, 21 partial, 2 none. Now: 48 strong, 15 partial, 0 none. The 15 partial
rows are disconnect, death, two players on one listing, exact multiply, shill bids, IP addresses,
no RNG leak, size-bomb stacks, the `untradeable` tag, renamed-item scams, `actionsDisabled`,
`readOnly`, per-account listing caps, bounded search cost and the creative call sites. Each has its
missing test named in the notes above, and each is an open item below.

## Open items after 0.3.0-beta.1

None of these is a known way to take value out of the economy. They are missing tests, a missing
document line and two places in the companion app's server that this repository does not own.

1. **NeroLink timeout (two players, one listing).** When the server thread is busy, the app server
   answers "server busy, try again" after its timeout while the queued action still runs later. A
   retry with the same request id is safe; the shipped app retries with a new id after a timeout, so
   the action can run twice. Needed in NeroLink: a timeout reply that says the outcome is unknown and
   to retry with the same id, an app-side test that a retry reuses the id, and a dispatcher test with
   a stalled executor.
2. **NeroLink `readOnly` and `actionsDisabled`.** Add dispatcher tests: `readOnly` refuses every
   action and still serves snapshots; `actionsDisabled` refuses only the named action,
   case-insensitively.
3. **Live trade through a disconnect or a death.** A test with a fake presence: both sides end whole,
   a later swap is a no-op, and a receiver who died between lock and swap gets the goods in the
   vault. Plus the named two-buyers case on one order (the second gets "not found" or "out of stock"
   and is untouched).
4. **Auctions, jobs and shops.** Extract the auction eligibility rule and test seller equals bidder;
   test the job and player-auction caps; decide a cap or a purge for shop blocks and for shop
   listings that never expire.
5. **Source scans.** No address API (`InetAddress`, `getRemoteAddress` and similar) anywhere in main
   sources; no bare `long * long` in the money packages without an overflow check or exact
   multiplication.
6. **No secret or future price in any payload.** Build every hub page, link snapshot and the player
   export for a store with a known secret and assert it appears in none; assert two fresh stores
   have different secrets.
7. **Size-bomb and `untradeable` fixtures.** A stack with 1 MB of custom data and a tagged item,
   refused at deposit, hand sell, direct-trade slot, offer send, Auto-Trader input and shop stock;
   a test of the size measure; a pure builder and test for the renamed-item tooltip, and the same
   marker on a renamed lot in an offer row.
8. **Chat commands.** `/trade`, `/eco pay` and the other value-moving commands rely on the
   permission system only: add a dead and spectator source check and a per-player budget, or write
   the reason they need none.
9. **Configuration NaN.** Assert that the configuration refuses NaN for the fee percentages, or make
   the fee rule fail closed.
10. **Search at a full book.** A timing test with the book filled to its caps for 500 accounts.
11. **Calibration tests.** Four accounts pumping a linked commodity against default data (E4), and 50
    pairs of alternate accounts washing a reference for a week (should stay inside the daily reach).
12. **Gallery.** Use decorative blocks, or make gallery-recorded blocks drop nothing when broken (C3);
    test that a demo shop refuses a real buy; add one creative-player refusal assertion per goods
    entry point.
13. **Smaller tests.** An administrator take on the Reserve account cannot go below zero; the
    request-id ledger's window pinned by a test; null-request-id tests for the shop-buy and
    Auto-Trader payloads; surface the arbitrage guard's skipped-recipe count in
    `/eco admin reload-check`.
14. **README.** Add the operators and offline-mode text to the README's operator section, with a
    check that the heading exists.
15. **Accepted risks to revisit with real play data:** House goods bought with locked credits (A1),
    linked-commodity pumping (E4), bonds crowding out the exchange budget (A4), the House row bound
    (F3), and creative items reaching the economy through the world (C2).

## Not verifiable without a running game

These are covered by pure-rule tests and code reading only. Each needs a step in the smoke test
with a real client or a hard kill:

- The crash fix: deposit to the vault, wait two seconds, kill the server process, restart: the
  stack is in exactly one place. Repeat for a withdraw, a direct-trade slot, a sell from the hand, an
  offer from the hand and an Auto-Trader slot.
- Menus closing on a dimension change, and an Auto-Trader fed by a hopper while its owner is in
  creative mode.
- Redeeming a Credit Chip taken from the creative tab.
- The direct-trade tooltip for a renamed stack, and every client screen.
- A real NeroLink token against the goods limit: send an offer of vault goods past the limit and see the
  plain refusal.
