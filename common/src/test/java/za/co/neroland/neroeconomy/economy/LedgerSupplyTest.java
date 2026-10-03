package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/** System accounts, the mint and burn rules, and the supply accounting they keep balanced. */
class LedgerSupplyTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PlayerAccountId ALICE_ACCOUNT = new PlayerAccountId(ALICE);
    private static final SystemAccountId HOUSE = SystemAccountId.house("ironworks");

    private static EconomySavedData roundTrip(EconomySavedData data) {
        JsonElement encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        return EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }

    @Test void systemAccountsHoldBalancesAndSurviveTheRoundTrip() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.ESCROW, 40L,
                TransactionType.MARKET_ESCROW)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.RESERVE, SystemAccountId.EXCHANGE,
                7L, TransactionType.FEE_EXCHANGE)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.RESERVE, HOUSE, 500L,
                TransactionType.MARKET_PAYOUT)));
        EconomySavedData restored = roundTrip(data);
        assertEquals(40L, restored.balance(SystemAccountId.ESCROW));
        assertEquals(7L, restored.balance(SystemAccountId.EXCHANGE));
        assertEquals(500L, restored.balance(HOUSE));
        assertEquals(Ledgers.RESERVE - 100L - 7L - 500L, restored.balance(SystemAccountId.RESERVE));
        assertEquals(60L, restored.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertTrue(restored.supplyBalanced());
        assertTrue(restored.reserveSeeded());
        assertEquals(data.totalBalancesForTest(), restored.totalBalancesForTest());
        // Saved under the system kind and their stable names, never as a player.
        JsonObject json = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        boolean reserveRow = false;
        for (JsonElement element : json.getAsJsonArray("balances")) {
            JsonObject row = element.getAsJsonObject();
            // A player row leaves the kind out, as every save before system accounts did.
            if (row.has("account_kind") && "system".equals(row.get("account_kind").getAsString())
                    && "reserve".equals(row.get("player").getAsString())) reserveRow = true;
        }
        assertTrue(reserveRow);
        // Their audit rows name them in the clear; the player stays a pseudonym.
        assertTrue(restored.rowsForTest().stream().anyMatch(row -> row.type() == TransactionType.MARKET_ESCROW
                && row.destinationAccount().equals("system:escrow") && !row.sourceAccount().contains(ALICE.toString())));
        assertTrue(restored.rowsForTest().stream().anyMatch(row -> row.destinationAccount().equals("system:house/ironworks")));
    }

    @Test void systemAccountsGetNoStartingBalanceNoHistoryAndNoTombstone() {
        EconomySavedData data = Ledgers.seeded();
        assertEquals(0L, data.getBalance(SystemAccountId.ESCROW, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.balance(HOUSE));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.RESERVE, HOUSE, 10L,
                TransactionType.MARKET_PAYOUT)));
        // Exactly what was moved: opening a system account pays it nothing.
        assertEquals(10L, data.balance(HOUSE));
        assertEquals(Ledgers.RESERVE - 10L, data.balance(SystemAccountId.RESERVE));
        // Rows between system accounts are nobody's history and nobody's export.
        assertTrue(data.history(ALICE, 100).isEmpty());
        assertTrue(data.export(ALICE).transactions().isEmpty());
        assertFalse(data.unvested(ALICE));
        // An account kind this build does not know is not an account at all.
        AccountId stranger = new AccountId() {
            @Override public String kind() { return "faction"; }
            @Override public String value() { return "red"; }
        };
        assertEquals(LedgerResult.INVALID, data.apply(LedgerPlan.of(SystemAccountId.RESERVE, stranger, 1L,
                TransactionType.MARKET_PAYOUT)));
        assertEquals(0L, data.getBalance(stranger, NeroEconomyCommon.CREDITS));
        // The same account named through another class is the same account.
        AccountId reserveByName = new AccountId() {
            @Override public String kind() { return "system"; }
            @Override public String value() { return "reserve"; }
        };
        assertEquals(data.balance(SystemAccountId.RESERVE), data.getBalance(reserveByName, NeroEconomyCommon.CREDITS));
        assertEquals(null, SystemAccountId.parse("bank"));
        assertEquals(null, SystemAccountId.parse("house/"));
        assertEquals(null, SystemAccountId.parse("house/Bad Name"));
        assertEquals(HOUSE, SystemAccountId.parse("house/ironworks"));
        assertTrue(HOUSE.isHouse());
        assertFalse(SystemAccountId.RESERVE.isHouse());
    }

    @Test void onlyTheMintingReasonsCanCreateCredits() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        long supply = data.totalBalancesForTest();
        for (TransactionType type : TransactionType.values()) {
            boolean minted = data.deposit(ALICE, NeroEconomyCommon.CREDITS, 10L, type);
            boolean allowed = type == TransactionType.ADMIN_GRANT || type == TransactionType.ADMIN_SET;
            assertEquals(allowed, minted, type.name());
            if (minted) supply += 10L;
            assertEquals(supply, data.totalBalancesForTest(), type.name());
            assertTrue(data.supplyBalanced(), type.name());
        }
        // Each minting reason has its own accounts: the Reserve's mint cannot land on a player,
        // nor a migration anywhere but in escrow.
        assertFalse(data.deposit(ALICE_ACCOUNT, NeroEconomyCommon.CREDITS, 10L, TransactionType.RESERVE_MINT));
        assertFalse(data.deposit(ALICE_ACCOUNT, NeroEconomyCommon.CREDITS, 10L, TransactionType.MIGRATION));
        assertFalse(data.deposit(SystemAccountId.ESCROW, NeroEconomyCommon.CREDITS, 10L, TransactionType.RESERVE_MINT));
        assertFalse(data.deposit(SystemAccountId.ESCROW, NeroEconomyCommon.CREDITS, 10L, TransactionType.ADMIN_GRANT));
        assertFalse(data.deposit(SystemAccountId.EXCHANGE, NeroEconomyCommon.CREDITS, 10L, TransactionType.ADMIN_GRANT));
        assertFalse(data.deposit(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS, 10L, TransactionType.MIGRATION));
        assertTrue(data.deposit(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS, 10L, TransactionType.RESERVE_MINT));
        assertTrue(data.deposit(SystemAccountId.ESCROW, NeroEconomyCommon.CREDITS, 10L, TransactionType.MIGRATION));
        assertEquals(supply + 20L, data.totalBalancesForTest());
        assertTrue(data.supplyBalanced());
        // A transfer can never carry a minting or burning reason either.
        for (TransactionType type : TransactionType.values()) {
            if (!type.mints() && !type.burns()) continue;
            assertEquals(LedgerResult.INVALID, data.apply(LedgerPlan.of(SystemAccountId.RESERVE, ALICE_ACCOUNT, 1L, type)),
                    type.name());
        }
    }

    @Test void onlyTheBurningReasonsCanDestroyCredits() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        long supply = data.totalBalancesForTest();
        for (TransactionType type : TransactionType.values()) {
            boolean burned = data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 5L, type);
            boolean allowed = type == TransactionType.ADMIN_TAKE || type == TransactionType.ADMIN_SET;
            assertEquals(allowed, burned, type.name());
            if (burned) supply -= 5L;
            assertEquals(supply, data.totalBalancesForTest(), type.name());
            assertTrue(data.supplyBalanced(), type.name());
        }
        assertEquals(90L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        // Escrow and the exchange hold what is owed to somebody: an operator cannot burn it.
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.ESCROW, 20L,
                TransactionType.MARKET_ESCROW)));
        assertFalse(data.withdraw(SystemAccountId.ESCROW, NeroEconomyCommon.CREDITS, 1L, TransactionType.ADMIN_TAKE));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 71L, TransactionType.ADMIN_TAKE));
        assertTrue(data.withdraw(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS, 1L, TransactionType.ADMIN_TAKE));
        assertTrue(data.supplyBalanced());
    }

    @Test void setBalanceMintsUpwardAndBurnsDownwardUnderOneReason() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        assertTrue(data.setBalance(ALICE, 250L, TransactionType.ADMIN_SET));
        assertEquals(150L, data.supplyForTest().mintedByReason().get(TransactionType.ADMIN_SET));
        assertTrue(data.setBalance(ALICE, 40L, TransactionType.ADMIN_SET));
        assertEquals(210L, data.supplyForTest().burnedByReason().get(TransactionType.ADMIN_SET));
        assertTrue(data.setBalance(ALICE, 40L, TransactionType.ADMIN_SET));
        assertEquals(40L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        // No other reason may set a balance.
        assertFalse(data.setBalance(ALICE, 1_000L, TransactionType.ADMIN_GRANT));
        assertFalse(data.setBalance(ALICE, 1_000L, TransactionType.MARKET_PAYOUT));
        assertFalse(data.setBalance(ALICE, -1L, TransactionType.ADMIN_SET));
        assertTrue(data.supplyBalanced());
        SupplyReport report = data.supplyReport(0L);
        assertEquals(Ledgers.RESERVE + 150L, report.mintedAllTime());
        assertEquals(210L, report.burnedAllTime());
        assertEquals(report.mintedAllTime(), report.mintedToday());
        assertEquals(report.burnedAllTime(), report.burnedToday());
        assertEquals(40L, report.circulation());
        assertEquals(Ledgers.RESERVE - 100L, report.reserve());
        assertEquals(report.mintedAllTime() - report.burnedAllTime(), report.total());
        assertTrue(report.supplyBalanced());
        assertTrue(report.escrowBalanced());
        assertTrue(report.healthy());
    }

    @Test void theSupplyReportNamesNoAccountAndChecksEscrowAgainstWhatIsOwed() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.ESCROW, 30L,
                TransactionType.MARKET_ESCROW)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.RESERVE, HOUSE, 900L,
                TransactionType.MARKET_PAYOUT)));
        assertEquals(LedgerResult.APPLIED, data.payFromReserve(BudgetKind.NPC, new PlayerAccountId(BOB), 25L,
                TransactionType.MARKET_PAYOUT));
        SupplyReport report = data.supplyReport(30L);
        assertEquals(70L + 125L, report.circulation());
        assertEquals(30L, report.escrow());
        assertEquals(900L, report.houses());
        assertEquals(0L, report.exchange());
        assertEquals(30L, report.obligations());
        assertEquals(25L, report.budgetUsedToday().get(BudgetKind.NPC));
        assertEquals(0L, report.budgetUsedToday().get(BudgetKind.SIBLING));
        assertFalse(report.budgetUsedToday().containsKey(BudgetKind.UNBUDGETED));
        assertTrue(report.escrowBalanced());
        assertTrue(report.healthy());
        // One credit owed that escrow does not hold, and the check fails.
        assertFalse(data.supplyReport(31L).escrowBalanced());
        assertFalse(data.supplyReport(31L).healthy());
        assertFalse(report.toString().contains(ALICE.toString()));
        assertFalse(report.toString().contains(BOB.toString()));
        assertEquals(Ledgers.RESERVE, report.mintedByReason().get(TransactionType.RESERVE_MINT));
    }

    @Test void supplyCountersAndBudgetUseSurviveTheRoundTrip() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 500L, TransactionType.ADMIN_GRANT));
        assertTrue(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 100L, TransactionType.ADMIN_TAKE));
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 12L));
        EconomySavedData restored = roundTrip(data);
        SupplyReport before = data.supplyReport(0L);
        SupplyReport after = restored.supplyReport(0L);
        assertEquals(before.mintedAllTime(), after.mintedAllTime());
        assertEquals(before.burnedAllTime(), after.burnedAllTime());
        assertEquals(before.mintedToday(), after.mintedToday());
        assertEquals(before.burnedToday(), after.burnedToday());
        assertEquals(before.mintedByReason(), after.mintedByReason());
        assertEquals(before.burnedByReason(), after.burnedByReason());
        assertEquals(12L, after.budgetUsedToday().get(BudgetKind.SIBLING));
        assertEquals(restored.budgetUsed(BudgetKind.SIBLING), data.budgetUsed(BudgetKind.SIBLING));
        assertTrue(after.supplyBalanced());
        // Seeded once: a second fill after a reload mints nothing.
        assertEquals(0L, restored.seedReserve(Ledgers.RESERVE));
        assertEquals(before.mintedAllTime(), restored.supplyReport(0L).mintedAllTime());
    }

    @Test void aStartingBalanceIsATransferFromTheReserveAndPaysOnlyWhatItHolds() {
        EconomySavedData data = new EconomySavedData();
        data.seedReserve(150L);
        // A read reports what the first write would pay, and opens nothing.
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        Ledgers.openLocked(data, ALICE);
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(50L, data.balance(SystemAccountId.RESERVE));
        // The Reserve can only part-pay the next one, and the one after gets nothing; both still open.
        assertEquals(50L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        Ledgers.openLocked(data, BOB);
        assertEquals(50L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.balance(SystemAccountId.RESERVE));
        UUID carol = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertEquals(0L, data.getBalance(carol, NeroEconomyCommon.CREDITS));
        Ledgers.openLocked(data, carol);
        assertEquals(0L, data.getBalance(carol, NeroEconomyCommon.CREDITS));
        assertEquals(150L, data.totalBalancesForTest());
        assertTrue(data.supplyBalanced());
        // Opening is once: the Reserve refilled later does not pay a second grant.
        assertTrue(data.deposit(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS, 1_000L, TransactionType.RESERVE_MINT));
        Ledgers.openLocked(data, carol);
        assertEquals(0L, data.getBalance(carol, NeroEconomyCommon.CREDITS));
    }

    @Test void erasureSweepsTheBalanceToTheReserveInARowThatNamesNobody() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        assertTrue(data.transfer(BOB, ALICE, NeroEconomyCommon.CREDITS, 30L));
        String alice = data.export(ALICE).accountReference();
        long supply = data.totalBalancesForTest();
        data.forgetPlayer(ALICE);
        assertEquals(supply, data.totalBalancesForTest());
        assertTrue(data.supplyBalanced());
        assertEquals(Ledgers.RESERVE - 200L + 130L, data.balance(SystemAccountId.RESERVE));
        assertEquals(0L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.locked(ALICE));
        TransactionRecord sweep = data.rowsForTest().stream().filter(row -> row.type() == TransactionType.ERASURE_SWEEP)
                .findFirst().orElseThrow();
        assertEquals(130L, sweep.amount());
        assertEquals("", sweep.sourceAccount());
        assertEquals("system:reserve", sweep.destinationAccount());
        // Nothing left anywhere in the ledger names the erased account.
        for (TransactionRecord row : data.rowsForTest()) {
            assertFalse(row.sourceAccount().equals(alice));
            assertFalse(row.destinationAccount().equals(alice));
        }
        // Erasing somebody the ledger never knew changes no balance.
        data.forgetPlayer(UUID.fromString("00000000-0000-0000-0000-00000000000f"));
        assertEquals(supply, data.totalBalancesForTest());
        // The other sections sweep the escrow that was the erased player's the same way.
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(new PlayerAccountId(BOB), SystemAccountId.ESCROW,
                40L, TransactionType.MARKET_ESCROW)));
        assertEquals(40L, data.sweepErasedEscrow(40L));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        // It can never move more than escrow holds.
        assertEquals(0L, data.sweepErasedEscrow(5L));
        assertEquals(supply, data.totalBalancesForTest());
        assertTrue(data.supplyBalanced());
    }

    @Test void aPlanAppliesEveryLegOrNone() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        PlayerAccountId bob = new PlayerAccountId(BOB);
        long reserve = data.balance(SystemAccountId.RESERVE);
        int rows = data.transactionCount();
        // The third leg cannot be paid, so the first two do not happen either.
        LedgerPlan failing = LedgerPlan.builder()
                .move(ALICE_ACCOUNT, SystemAccountId.ESCROW, 60L, TransactionType.MARKET_PURCHASE)
                .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, 2L, TransactionType.FEE_MARKET)
                .move(ALICE_ACCOUNT, bob, 41L, TransactionType.PLAYER_PAYMENT).build();
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, data.apply(failing));
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        assertEquals(reserve, data.balance(SystemAccountId.RESERVE));
        assertEquals(rows, data.transactionCount());
        // With the last leg affordable, all three happen, one audit row each.
        LedgerPlan passing = LedgerPlan.builder()
                .move(ALICE_ACCOUNT, SystemAccountId.ESCROW, 60L, TransactionType.MARKET_PURCHASE)
                .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, 2L, TransactionType.FEE_MARKET)
                .move(ALICE_ACCOUNT, bob, 40L, TransactionType.PLAYER_PAYMENT).build();
        assertEquals(LedgerResult.APPLIED, data.apply(passing));
        assertEquals(0L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(58L, data.balance(SystemAccountId.ESCROW));
        assertEquals(reserve + 2L, data.balance(SystemAccountId.RESERVE));
        assertEquals(140L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(rows + 3, data.transactionCount());
        assertEquals(-100L, passing.net(ALICE_ACCOUNT));
        assertEquals(40L, passing.net(bob));
        assertEquals(58L, passing.net(SystemAccountId.ESCROW));
        // A leg can spend what an earlier leg of the same plan brought in.
        LedgerPlan chained = LedgerPlan.builder()
                .pay(BudgetKind.UNBUDGETED, ALICE_ACCOUNT, 10L, TransactionType.PRICE_ADJUSTMENT)
                .move(ALICE_ACCOUNT, bob, 10L, TransactionType.SHOP_PURCHASE).build();
        assertEquals(LedgerResult.APPLIED, data.apply(chained));
        assertEquals(150L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        // Invalid legs refuse the whole plan before anything is looked at.
        assertEquals(LedgerResult.INVALID, data.apply(null));
        assertEquals(LedgerResult.INVALID, data.apply(LedgerPlan.of(ALICE_ACCOUNT, bob, -1L, TransactionType.PLAYER_PAYMENT)));
        assertEquals(LedgerResult.INVALID, data.apply(LedgerPlan.of(ALICE_ACCOUNT, null, 1L, TransactionType.PLAYER_PAYMENT)));
        assertEquals(LedgerResult.INVALID, data.apply(LedgerPlan.of(ALICE_ACCOUNT, bob, 1L, null)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.builder().build()));
        assertTrue(data.supplyBalanced());
    }

    @Test void aPlanCountsReserveLegsAgainstTheirBudget() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        // The default npcDailyBudget is 20000.
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.pay(BudgetKind.NPC, ALICE_ACCOUNT, 19_990L,
                TransactionType.MARKET_PAYOUT)));
        // Two legs that fit one by one but not together: refused whole.
        LedgerPlan twoLegs = LedgerPlan.builder().pay(BudgetKind.NPC, ALICE_ACCOUNT, 6L, TransactionType.MARKET_PAYOUT)
                .pay(BudgetKind.NPC, SystemAccountId.ESCROW, 6L, TransactionType.MARKET_ESCROW).build();
        assertEquals(LedgerResult.BUDGET_SPENT, data.apply(twoLegs));
        assertEquals(19_990L, data.budgetUsed(BudgetKind.NPC));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.pay(BudgetKind.NPC, ALICE_ACCOUNT, 10L,
                TransactionType.MARKET_PAYOUT)));
        assertEquals(0L, data.budgetRemaining(BudgetKind.NPC));
        assertEquals(LedgerResult.BUDGET_SPENT, data.payFromReserve(BudgetKind.NPC, ALICE_ACCOUNT, 1L,
                TransactionType.MARKET_PAYOUT));
        // A plan can never take the Reserve below zero.
        assertEquals(LedgerResult.RESERVE_SHORT, data.apply(LedgerPlan.pay(BudgetKind.UNBUDGETED, ALICE_ACCOUNT,
                data.balance(SystemAccountId.RESERVE) + 1L, TransactionType.PRICE_ADJUSTMENT)));
        assertTrue(data.balance(SystemAccountId.RESERVE) > 0L);
        assertTrue(data.supplyBalanced());
    }

    @Test void aBatchFromTheReserveIsScaledToTheDaysBudget() {
        EconomySavedData data = Ledgers.seeded();
        Map<AccountId, Long> wanted = new LinkedHashMap<>();
        wanted.put(ALICE_ACCOUNT, 4_000L);
        wanted.put(new PlayerAccountId(BOB), 6_000L);
        // The default siblingDailyBudget is 5000: half of what was asked.
        Reserve.ProRata quote = data.quoteProRata(BudgetKind.SIBLING, wanted);
        Reserve.ProRata paid = data.payProRata(BudgetKind.SIBLING, wanted, TransactionType.SIBLING_PAYOUT);
        assertEquals(10_000L, paid.requested());
        assertEquals(5_000L, paid.paid());
        assertEquals(0.5D, paid.factor(), 1.0e-12D);
        assertEquals(quote.shares(), paid.shares());
        assertEquals(Ledgers.STARTING + 2_000L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(Ledgers.STARTING + 3_000L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.budgetRemaining(BudgetKind.SIBLING));
        assertTrue(data.supplyBalanced());
        JsonArray rows = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject()
                .getAsJsonObject("reserve").getAsJsonArray("budget_used");
        assertEquals(1, rows.size());
        assertEquals("SIBLING", rows.get(0).getAsJsonObject().get("name").getAsString());
    }
}
