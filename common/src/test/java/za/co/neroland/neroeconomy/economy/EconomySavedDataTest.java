package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

class EconomySavedDataTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void rejectsBadAmountsOverdraftAndOverflow() {
        EconomySavedData data = new EconomySavedData();
        assertFalse(data.deposit(ALICE, NeroEconomyCommon.CREDITS, -1));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, -1));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 101));
        assertTrue(data.setBalance(ALICE, Long.MAX_VALUE, TransactionType.ADMIN_SET));
        assertFalse(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 1));
    }

    @Test void transferIsAtomicAndDefinesZeroAndSelfSemantics() {
        EconomySavedData data = new EconomySavedData();
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

    @Test void startingBalanceMintsOnlyOnceAndErasureIsComplete() {
        EconomySavedData data = new EconomySavedData();
        assertEquals(100, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(100, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(1, data.history(ALICE, 100).size());
        data.forgetPlayer(ALICE);
        assertTrue(data.history(ALICE, 100).isEmpty());
        assertEquals(100, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
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
        EconomySavedData data = new EconomySavedData();
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 25));
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(75, restored.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(125, restored.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(data.transactionCount(), restored.transactionCount());
    }

    @Test void synchronizedMutationSequenceConservesCredits() throws InterruptedException {
        EconomySavedData data = new EconomySavedData();
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

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
