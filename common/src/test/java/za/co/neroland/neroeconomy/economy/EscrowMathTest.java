package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * The migration arithmetic. An older version wrote escrowed credits off the ledger and kept only
 * the numbers in the market and obligation stores; on migration exactly that much is minted back
 * into the escrow account, after which both invariants hold. The store-level migration reads item
 * stacks and so cannot run here; this covers the calculation it performs and the ledger it leaves.
 */
class EscrowMathTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** A ledger as 0.2 saved it: balances and opened accounts, nothing about supply or the Reserve. */
    private static EconomySavedData legacyLedger(long alice, long bob) {
        JsonObject json = new JsonObject();
        JsonArray balances = new JsonArray();
        JsonArray initialized = new JsonArray();
        for (Object[] entry : new Object[][] {{ALICE, alice}, {BOB, bob}}) {
            JsonObject row = new JsonObject();
            row.addProperty("player", entry[0].toString());
            row.addProperty("balance", (Long) entry[1]);
            balances.add(row);
            initialized.add("player:" + entry[0]);
        }
        json.add("balances", balances);
        json.add("initialized", initialized);
        return EconomySavedData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    @Test void obligationsAreSummedExactly() {
        assertEquals(0L, EscrowMath.total());
        assertEquals(60L, EscrowMath.total(10L, 20L, 30L));
        // A part that is not a positive amount counts as nothing.
        assertEquals(30L, EscrowMath.total(10L, -5L, 0L, 20L));
        assertEquals(Long.MAX_VALUE, EscrowMath.total(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, EscrowMath.total(Long.MAX_VALUE - 1L, 1L));
    }

    @Test void theShortfallIsWhatEscrowDoesNotAlreadyHold() {
        // Straight from 0.2 the escrow account is empty, so the whole of the obligations is missing.
        assertEquals(735L, EscrowMath.shortfall(735L, 0L));
        // Already migrated: nothing is missing, and running it again mints nothing.
        assertEquals(0L, EscrowMath.shortfall(735L, 735L));
        assertEquals(0L, EscrowMath.shortfall(735L, 800L));
        assertEquals(35L, EscrowMath.shortfall(735L, 700L));
        assertEquals(0L, EscrowMath.shortfall(0L, 0L));
        assertEquals(0L, EscrowMath.shortfall(-1L, 0L));
        assertEquals(10L, EscrowMath.shortfall(10L, -3L));
    }

    @Test void aLegacyLedgerIsAdoptedAndItsEscrowRestoredSoBothInvariantsHold() {
        EconomySavedData data = legacyLedger(400L, 75L);
        // Nothing has been counted yet, so the supply check cannot pass.
        assertFalse(data.supplyBalanced());
        // What the 0.2 stores recorded as taken out of balances and still owed:
        long buyOfferEscrow = 120L + 45L;            // two open buy offers
        long creditClaims = 300L + 12L;              // uncollected proceeds and one refund
        long playerJobPayouts = 250L;                // an open, player-funded job
        long systemJobPayouts = 80L;                 // an open system job, which 0.2 never funded
        long standingBids = 60L;                     // one running auction's high bid
        long obligations = EscrowMath.total(buyOfferEscrow, creditClaims, playerJobPayouts, systemJobPayouts, standingBids);
        assertEquals(867L, obligations);
        assertEquals(867L, data.adoptLegacySupply(obligations));
        // Invariant B: escrow holds exactly what is owed.
        assertEquals(obligations, data.balance(SystemAccountId.ESCROW));
        // Invariant A: all balances equal minted less burned, and no player balance changed.
        assertTrue(data.supplyBalanced());
        assertEquals(400L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(75L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        SupplyReport report = data.supplyReport(obligations);
        assertTrue(report.healthy());
        assertEquals(475L + 867L, report.mintedByReason().get(TransactionType.MIGRATION));
        assertEquals(475L, report.circulation());
        // The escrow mint is one audit row that names no player; the adopted balances have none.
        long migrationRows = data.rowsForTest().stream().filter(row -> row.type() == TransactionType.MIGRATION).count();
        assertEquals(1L, migrationRows);
        TransactionRecord row = data.rowsForTest().stream().filter(each -> each.type() == TransactionType.MIGRATION)
                .findFirst().orElseThrow();
        assertEquals(867L, row.amount());
        assertEquals("", row.sourceAccount());
        assertEquals("system:escrow", row.destinationAccount());
        // Then the Reserve is filled, once, and both invariants still hold.
        assertEquals(Ledgers.RESERVE, data.seedReserve(Ledgers.RESERVE));
        assertTrue(data.supplyReport(obligations).healthy());
        // Migration is once: a second pass, or one after a reload, mints nothing.
        assertEquals(0L, data.adoptLegacySupply(obligations));
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(0L, restored.adoptLegacySupply(obligations));
        assertEquals(0L, restored.seedReserve(Ledgers.RESERVE));
        assertTrue(restored.supplyReport(obligations).healthy());
        assertEquals(475L + 867L + Ledgers.RESERVE, restored.totalBalancesForTest());
    }

    @Test void whatMigrationRestoredCanBeCollectedRefundedAndPaidOut() {
        EconomySavedData data = legacyLedger(400L, 75L);
        long obligations = 500L;
        data.adoptLegacySupply(obligations);
        data.seedReserve(Ledgers.RESERVE);
        // A claim of 300 is collected, an offer's 120 is refunded, a fee of 5 goes to the Reserve.
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.ESCROW, new PlayerAccountId(ALICE),
                300L, TransactionType.MARKET_PAYOUT)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.ESCROW, new PlayerAccountId(BOB),
                120L, TransactionType.ESCROW_REFUND)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE,
                5L, TransactionType.FEE_MARKET)));
        assertEquals(700L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(195L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertTrue(data.supplyReport(obligations - 425L).healthy());
        // Escrow cannot pay out more than was restored.
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, data.apply(LedgerPlan.of(SystemAccountId.ESCROW,
                new PlayerAccountId(ALICE), 76L, TransactionType.MARKET_PAYOUT)));
    }

    @Test void anyLegacySaveEndsWithBothInvariantsHolding() {
        Random random = new Random(20_261_003L);
        for (int round = 0; round < 500; round++) {
            EconomySavedData data = legacyLedger(random.nextInt(1_000_000), random.nextInt(1_000_000));
            long[] parts = new long[random.nextInt(12)];
            for (int index = 0; index < parts.length; index++) parts[index] = random.nextInt(50_000);
            long obligations = EscrowMath.total(parts);
            assertEquals(obligations, data.adoptLegacySupply(obligations));
            data.seedReserve(random.nextInt(500_000));
            SupplyReport report = data.supplyReport(obligations);
            assertTrue(report.supplyBalanced(), "round " + round);
            assertTrue(report.escrowBalanced(), "round " + round);
            assertEquals(obligations, report.escrow());
        }
    }

    @Test void aBrandNewLedgerTakesTheSamePathWithNothingToRestore() {
        EconomySavedData data = new EconomySavedData();
        assertEquals(0L, data.adoptLegacySupply(0L));
        assertEquals(Ledgers.RESERVE, data.seedReserve(Ledgers.RESERVE));
        assertTrue(data.supplyReport(0L).healthy());
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        assertFalse(data.supplyReport(0L).mintedByReason().containsKey(TransactionType.MIGRATION));
    }
}
