package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/*
 * The market and obligation stores are not covered here: their behaviour is only reachable through
 * ItemStack, Inventory, ServerPlayer, and MinecraftServer, none of which this suite can construct
 * without a full game bootstrap. Their fixes are runtime-verified instead.
 */
class EconomySavedDataTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void rejectsBadAmountsOverdraftAndOverflow() {
        EconomySavedData data = Ledgers.seeded();
        assertFalse(data.deposit(ALICE, NeroEconomyCommon.CREDITS, -1));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, -1));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 101));
        // The whole supply has to fit one 64-bit number, so a balance cannot be set past the room left.
        assertFalse(data.setBalance(ALICE, Long.MAX_VALUE, TransactionType.ADMIN_SET));
        long room = Long.MAX_VALUE - data.totalBalancesForTest();
        assertTrue(data.setBalance(ALICE, Ledgers.STARTING + room, TransactionType.ADMIN_SET));
        assertEquals(Long.MAX_VALUE, data.totalBalancesForTest());
        assertFalse(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 1, TransactionType.ADMIN_GRANT));
        assertTrue(data.supplyBalanced());
    }

    @Test void transferIsAtomicAndDefinesZeroAndSelfSemantics() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 40));
        assertEquals(60, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(140, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertFalse(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 61));
        assertEquals(60, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(140, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertTrue(data.transfer(ALICE, ALICE, NeroEconomyCommon.CREDITS, 60));
        assertFalse(data.transfer(ALICE, ALICE, NeroEconomyCommon.CREDITS, 61));
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 0));
    }

    @Test void readingABalanceNeitherOpensTheAccountNorWritesAnAuditRow() {
        EconomySavedData data = Ledgers.seeded();
        // The one row so far is the Reserve's initial fill.
        assertEquals(1, data.transactionCount());
        assertEquals(100, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(100, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(1, data.transactionCount());
        assertEquals(Ledgers.RESERVE, data.balance(SystemAccountId.RESERVE));
        assertTrue(data.history(ALICE, 100).isEmpty());
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 1));
        assertEquals(101, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        // The first write opened the account (starting balance) and then made the payment.
        assertEquals(3, data.transactionCount());
        assertEquals(Ledgers.RESERVE - 101, data.balance(SystemAccountId.RESERVE));
    }

    @Test void erasureTombstonesTheAccountSoTheStartingBalanceIsNotPaidAgain() {
        EconomySavedData data = Ledgers.seeded();
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 5));
        assertEquals(105, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(2, data.history(ALICE, 100).size());
        data.forgetPlayer(ALICE);
        assertTrue(data.history(ALICE, 100).isEmpty());
        assertTrue(data.tombstoned(ALICE));
        assertEquals(0, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        // What the account held went back to the Reserve, so no credit was destroyed.
        assertEquals(Ledgers.RESERVE, data.balance(SystemAccountId.RESERVE));
        assertTrue(data.supplyBalanced());
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 7));
        assertEquals(7, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
    }

    @Test void tombstoneSurvivesThePersistenceRoundTrip() {
        EconomySavedData data = Ledgers.seeded();
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 5));
        data.forgetPlayer(ALICE);
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertTrue(restored.tombstoned(ALICE));
        assertEquals(0, restored.getBalance(ALICE, NeroEconomyCommon.CREDITS));
    }

    @Test void oneSidedOptOutBlanksOnlyThatSideAndKeepsTheCounterpartyRow() {
        EconomySavedData data = Ledgers.seeded();
        data.setLoggingOptOut(ALICE, true);
        Ledgers.open(data, ALICE, BOB);
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 10));
        assertEquals(2, data.history(BOB, 100).size());
        assertTrue(data.history(ALICE, 100).isEmpty());
    }

    @Test void retentionPrunesExpiredRecords() {
        EconomySavedData data = new EconomySavedData();
        long now = System.currentTimeMillis();
        data.importRecord(new TransactionRecord(TransactionType.DEPOSIT, 1, "neroeconomy:credits",
                now - 91L * 86_400_000L, "", "abc"));
        data.pruneForTest(now);
        assertEquals(0, data.transactionCount());
    }

    @Test void persistenceCodecRoundTripsBalancesAndAudit() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 25));
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(75, restored.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(125, restored.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(data.transactionCount(), restored.transactionCount());
        assertEquals(Ledgers.RESERVE - 200, restored.balance(SystemAccountId.RESERVE));
        assertTrue(restored.supplyBalanced());
    }

    @Test void synchronizedMutationSequenceConservesCredits() throws InterruptedException {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(() -> { await(start); for (int i = 0; i < 50; i++)
            data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 1); });
        Thread second = new Thread(() -> { await(start); for (int i = 0; i < 50; i++)
            data.transfer(BOB, ALICE, NeroEconomyCommon.CREDITS, 1); });
        first.start(); second.start(); start.countDown(); first.join(); second.join();
        assertEquals(200, data.getBalance(ALICE, NeroEconomyCommon.CREDITS)
                + data.getBalance(BOB, NeroEconomyCommon.CREDITS));
    }

    @Test void loggingOptOutPersistsAndErasureRemovesIt() {
        EconomySavedData data = new EconomySavedData();
        data.setLoggingOptOut(ALICE, true);
        assertTrue(data.export(ALICE).transactionLoggingOptOut());
        data.forgetPlayer(ALICE);
        assertFalse(data.export(ALICE).transactionLoggingOptOut());
    }

    @Test void theHandbookFlagPersistsAndAnOlderSaveLoadsWithNone() {
        EconomySavedData data = new EconomySavedData();
        assertTrue(data.isEmpty());
        data.markHandbookGiven(ALICE);
        assertFalse(data.isEmpty(), "a ledger holding only the flag is still saved");
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertTrue(restored.handbookGiven(ALICE));
        assertFalse(restored.handbookGiven(BOB));
        // A save from before the handbook has no such field: nobody is marked, so nothing breaks.
        JsonObject old = encoded.getAsJsonObject().deepCopy();
        old.remove("handbook_given");
        EconomySavedData older = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertFalse(older.handbookGiven(ALICE));
        assertEquals(0, older.handbookGivenCount());
    }

    @Test void exportIsNotCutAtTheHistoryLimit() {
        EconomySavedData data = Ledgers.seeded();
        for (int i = 0; i < 150; i++) assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 1));
        // The in-game history view stays capped; the data-subject export carries every retained row.
        assertEquals(100, data.history(ALICE, 1_000).size());
        assertEquals(151, data.export(ALICE).transactions().size());
    }

    @Test void exportCarriesOnlyTheSubjectsOwnPseudonym() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 10));
        PlayerLedgerExport export = data.export(ALICE);
        String bob = data.export(BOB).accountReference();
        assertFalse(export.accountReference().isEmpty());
        assertFalse(export.accountReference().equals(bob));
        for (TransactionRecord row : export.transactions()) {
            assertFalse(row.sourceAccount().equals(bob));
            assertFalse(row.destinationAccount().equals(bob));
        }
        assertTrue(export.transactions().stream().anyMatch(row -> row.type() == TransactionType.PLAYER_PAYMENT
                && row.sourceAccount().equals(export.accountReference())
                && row.destinationAccount().equals(EconomySavedData.COUNTERPARTY)));
        // A system account is not personal data, so it keeps its name: the grant came from the Reserve.
        assertTrue(export.transactions().stream().anyMatch(row -> row.type() == TransactionType.STARTING_BALANCE
                && row.sourceAccount().equals(SystemAccountId.RESERVE.stableKey())
                && row.destinationAccount().equals(export.accountReference())));
    }

    @Test void chipRedemptionsAreLimitedPerAccountAndEconomyDay() {
        EconomySavedData data = new EconomySavedData();
        long day = 20_000L * 86_400_000L;
        assertFalse(data.reserveChipRedemption(ALICE, 0, day));
        assertTrue(data.reserveChipRedemption(ALICE, 2, day));
        assertTrue(data.reserveChipRedemption(ALICE, 2, day + 3_600_000L));
        assertFalse(data.reserveChipRedemption(ALICE, 2, day + 7_200_000L));
        assertEquals(2, data.chipRedemptionsForTest(ALICE));
        // Another account has its own allowance, and a new economy day starts a new count.
        assertTrue(data.reserveChipRedemption(BOB, 2, day));
        assertTrue(data.reserveChipRedemption(ALICE, 2, day + 86_400_000L));
        assertEquals(1, data.chipRedemptionsForTest(ALICE));
        assertFalse(data.reserveChipRedemption(null, 2, day));
    }

    @Test void aRefusedChipDepositHandsItsReservationBack() {
        EconomySavedData data = new EconomySavedData();
        long day = 20_000L * 86_400_000L;
        assertTrue(data.reserveChipRedemption(ALICE, 1, day));
        assertFalse(data.reserveChipRedemption(ALICE, 1, day));
        data.releaseChipRedemption(ALICE);
        assertEquals(0, data.chipRedemptionsForTest(ALICE));
        assertTrue(data.reserveChipRedemption(ALICE, 1, day));
        // Releasing for an account with no reservation is a harmless no-op.
        data.releaseChipRedemption(BOB);
        data.releaseChipRedemption(null);
    }

    @Test void chipCounterIsExportedPersistedAndErasedWithTheAccount() {
        EconomySavedData data = new EconomySavedData();
        assertTrue(data.reserveChipRedemption(ALICE, 5));
        assertTrue(data.reserveChipRedemption(ALICE, 5));
        assertEquals(2, data.export(ALICE).chipCount());
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(2, restored.chipRedemptionsForTest(ALICE));
        assertEquals(data.export(ALICE).chipDay(), restored.export(ALICE).chipDay());
        restored.forgetPlayer(ALICE);
        assertEquals(0, restored.chipRedemptionsForTest(ALICE));
        assertEquals(0, restored.export(ALICE).chipCount());
    }

    @Test void theRetentionSweepDropsChipCountersFromEarlierDays() {
        EconomySavedData data = new EconomySavedData();
        long day = 20_000L * 86_400_000L;
        assertTrue(data.reserveChipRedemption(ALICE, 5, day));
        data.pruneForTest(day + 1L);
        assertEquals(1, data.chipRedemptionsForTest(ALICE));
        data.pruneForTest(day + 86_400_000L);
        assertEquals(0, data.chipRedemptionsForTest(ALICE));
    }

    @Test void clockFieldsArePersistedAndAnOldSaveLoadsWithoutThem() {
        EconomySavedData data = Ledgers.seeded();
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 1));
        JsonObject encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        // An offset of zero is the codec default and is simply left out; the watermark is written.
        assertFalse(encoded.has("clock_offset"));
        assertTrue(encoded.get("clock_last_wall").getAsLong() > 0L);
        encoded.addProperty("clock_offset", 7_200_000L);
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(7_200_000L, restored.clockForTest().offsetMillis());
        long ahead = restored.now() - System.currentTimeMillis();
        assertTrue(ahead > 7_100_000L && ahead < 7_300_000L);
        JsonObject again = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, restored).getOrThrow().getAsJsonObject();
        assertEquals(7_200_000L, again.get("clock_offset").getAsLong());
        assertTrue(again.get("clock_last_wall").getAsLong() > 0L);
        // A save written before the clock, the chip counter or the tombstones existed: every new
        // field falls back to its default and the first observation starts the clock.
        EconomySavedData old = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertEquals(0L, old.clockForTest().offsetMillis());
        assertTrue(old.clockForTest().lastWallMillis() > 0L);
        // No Reserve has been filled yet, so there is no starting balance to report.
        assertEquals(0, old.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertFalse(old.reserveSeeded());
        assertEquals(0, old.chipRedemptionsForTest(ALICE));
    }

    @Test void readingTheClockMarksTheStoreDirtyAtMostOnceAMinute() {
        EconomySavedData data = new EconomySavedData();
        data.now();
        data.setDirty(false);
        for (int i = 0; i < 1_000; i++) data.now();
        assertFalse(data.isDirty());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
