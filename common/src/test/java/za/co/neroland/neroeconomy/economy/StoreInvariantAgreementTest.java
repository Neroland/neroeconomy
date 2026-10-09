package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * The store's own invariant check (what operators see in {@code /eco admin supply}, {@code health}
 * and the Admin tab) and the fuzzer agree. A fuzz run once kept part of its escrow in a private
 * model no section recorded, so the store's Invariant B read false on a world the fuzzer called
 * sound; now every credit in escrow is recorded by the store, and an unrecorded one is caught.
 */
class StoreInvariantAgreementTest {
    @AfterEach void realClockAgain() { StoreTesting.wallClock(null); }

    @Test void aFuzzedStorePassesItsOwnCheckWithNothingLeftOver() {
        for (long seed : new long[] {34L, 1L, 2L, 3L}) {
            FuzzWorld world = OperationFuzzer.run(seed, 600, Set.of(), false).world();
            EconomyStore store = world.store;
            EconomySavedData ledger = store.ledger();
            assertEquals(world.supply, ledger.totalBalancesForTest(), "A, seed " + seed);
            assertEquals(store.escrowObligations(), ledger.balance(SystemAccountId.ESCROW), "B, seed " + seed);
            assertEquals(store.exchangeObligations(), ledger.balance(SystemAccountId.EXCHANGE), "C, seed " + seed);
            assertTrue(store.checkInvariants(), "seed " + seed);
            assertTrue(store.supplyReport().healthy(), "seed " + seed);
            // The run really did leave credits waiting in escrow for the check to account for.
            assertTrue(store.escrowObligations() > 0L, "seed " + seed);
        }
    }

    @Test void escrowNoSectionRecordsIsCaught() {
        FuzzWorld world = OperationFuzzer.run(34L, 200, Set.of(), false).world();
        EconomyStore store = world.store;
        assertTrue(store.checkInvariants());
        UUID player = world.players.get(0);
        // Credits moved into escrow with no record of who they are owed to: a leak the check must see.
        LedgerResult moved = world.ledger().apply(LedgerPlan.of(SystemAccountId.RESERVE, SystemAccountId.ESCROW, 1L,
                TransactionType.MARKET_ESCROW));
        assertTrue(moved.ok(), moved.toString());
        assertFalse(store.checkInvariants());
        assertFalse(store.supplyReport().healthy());
        // Recording it puts the books right again.
        assertTrue(store.market().addCreditClaim(player, 1L, TransactionType.ESCROW_REFUND));
        assertTrue(store.checkInvariants());
    }
}
