package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/** A frozen economy moves nothing, by any entry point, and still answers every read. */
class EconomyHealthTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PlayerAccountId ALICE_ACCOUNT = new PlayerAccountId(ALICE);

    @AfterEach void thaw() { EconomyHealth.clear(); }

    @Test void theFlagIsOffUntilSetAndOffAgainOnceCleared() {
        assertFalse(EconomyHealth.frozen());
        EconomyHealth.freeze();
        assertTrue(EconomyHealth.frozen());
        EconomyHealth.clear();
        assertFalse(EconomyHealth.frozen());
        assertEquals("message.neroeconomy.frozen", EconomyHealth.FROZEN_KEY);
    }

    @Test void everyLedgerMutationIsRefusedWhileFrozen() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        long supply = data.totalBalancesForTest();
        int rows = data.transactionCount();
        EconomyHealth.freeze();
        assertFalse(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 5L));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 5L));
        assertFalse(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 5L));
        assertFalse(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 5L, TransactionType.ADMIN_GRANT));
        assertFalse(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 5L, TransactionType.ADMIN_TAKE));
        assertFalse(data.setBalance(ALICE, 500L, TransactionType.ADMIN_SET));
        assertEquals(LedgerResult.FROZEN, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.ESCROW, 5L,
                TransactionType.MARKET_ESCROW)));
        assertEquals(LedgerResult.FROZEN, data.payFromReserve(BudgetKind.UNBUDGETED, ALICE_ACCOUNT, 5L,
                TransactionType.CREDIT_CHIP_REDEMPTION));
        Map<AccountId, Long> batch = new LinkedHashMap<>();
        batch.put(ALICE_ACCOUNT, 50L);
        assertEquals(0L, data.payProRata(BudgetKind.NPC, batch, TransactionType.MARKET_PAYOUT).paid());
        assertEquals(0L, data.quoteProRata(BudgetKind.NPC, batch).paid());
        assertEquals(0L, data.topUpReserve());
        // A new account is not opened, so no starting balance leaves the Reserve either.
        UUID carol = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertFalse(data.deposit(carol, NeroEconomyCommon.CREDITS, 1L));
        assertTrue(data.history(carol, 10).isEmpty());
        assertEquals(supply, data.totalBalancesForTest());
        assertEquals(rows, data.transactionCount());
        // Reads still work.
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(Ledgers.RESERVE - 200L, data.balance(SystemAccountId.RESERVE));
        assertEquals(1, data.history(ALICE, 10).size());
        assertTrue(data.supplyReport(0L).healthy());
        assertEquals(ALICE, data.export(ALICE).player());
        // And once it thaws, everything works again.
        EconomyHealth.clear();
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 5L));
    }

    @Test void erasureStillWorksWhileFrozen() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.ESCROW, 30L,
                TransactionType.MARKET_ESCROW)));
        long supply = data.totalBalancesForTest();
        EconomyHealth.freeze();
        // A data subject's request is honoured whatever state the economy is in.
        data.forgetPlayer(ALICE);
        assertEquals(30L, data.sweepErasedEscrow(30L));
        assertEquals(0L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        assertEquals(supply, data.totalBalancesForTest());
        assertTrue(data.supplyBalanced());
    }
}
