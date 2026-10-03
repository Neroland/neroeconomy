package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * The whole economy under a long random run of everything it can do: payments, escrow and its
 * refunds, fees, Reserve payouts, another mod's deposits, vault deposits, withdrawals and
 * deliveries, erasures, days rolling over, the clock jumping and the simulation stepping.
 * Invariant A, Invariant B and item conservation are checked after every single operation by
 * {@link OperationFuzzer}; this class runs it, makes sure the run was not a run of refusals, and
 * crashes it.
 */
class OperationFuzzTest {
    private static final int OPERATIONS = 100_000;
    private static final int CRASH_RUN = 15_000;

    @AfterEach void realClockAgain() { StoreTesting.wallClock(null); }

    @Test void aHundredThousandMixedOperationsKeepEveryInvariantAtEveryStep() {
        OperationFuzzer.Result result = OperationFuzzer.run(20_261_003L, OPERATIONS, Set.of(), false);
        FuzzWorld world = result.world();
        // Every kind of operation was picked, and every kind did something at least once.
        for (FuzzOperation operation : OperationFuzzer.OPERATIONS) {
            assertTrue(result.calls().get(operation.name()) > 0, operation.name() + " was never picked");
            assertTrue(result.effects().get(operation.name()) > 0, operation.name() + " never did anything");
        }
        assertEquals(OPERATIONS, result.calls().values().stream().mapToInt(Integer::intValue).sum());
        // The run was not a run of refusals: credits really were minted, burned, escrowed and paid.
        EconomySavedData ledger = world.ledger();
        // What is owed from escrow: the model's share, and what the store's own sections record
        // (open buy orders, credit claims).
        long owedFromEscrow = world.escrowTotal + world.store.escrowObligations();
        SupplyReport report = ledger.supplyReport(owedFromEscrow);
        assertTrue(report.healthy());
        assertTrue(report.mintedAllTime() > Ledgers.RESERVE);
        assertTrue(report.burnedAllTime() > 0L);
        assertEquals(world.supply, report.total());
        assertEquals(world.supply, report.mintedAllTime() - report.burnedAllTime());
        assertEquals(owedFromEscrow, ledger.balance(SystemAccountId.ESCROW));
        assertFalse(ledger.rowsForTest().isEmpty());
        for (BudgetKind kind : BudgetKind.values())
            if (kind != BudgetKind.UNBUDGETED) assertTrue(ledger.budgetUsed(kind) <= EconomySavedData.dailyLimit(kind));
        // Goods really did go into vaults, past the cap and out again.
        assertTrue(world.goods.values().stream().mapToLong(Long::longValue).sum() > 0L);
        assertFalse(world.vault().isEmpty());
        assertTrue(world.players.stream().anyMatch(player -> world.vault().stackCount(player) > FuzzWorld.CAP)
                || result.effects().get("vault_deliver") > 100);
        // The simulation ran steps, was at some point further behind than its cap, and is at a real step.
        assertTrue(world.stepsRun > 1_000L);
        assertTrue(world.stepsSkipped > 0L);
        assertTrue(world.store.simulation().lastStep() > 0L);
        // The trading sections really traded: orders matched and rested, offers were sent and
        // answered, rules bought and sold on their own, and alerts were raised for them.
        assertTrue(world.ruleTraded > 0L, "no rule ever traded");
        assertTrue(world.alerts > 0L, "no alert was ever raised");
        assertTrue(world.store.book().size() > 0);
        assertTrue(result.effects().get("offer_accept") > 20);
        assertTrue(result.effects().get("book_buy_from") > 100);
        assertTrue(result.effects().get("book_sell_to") > 100);
        // A summary for whoever tunes the weights: how much of each operation got through.
        System.out.println("[fuzz] rule fills worth " + world.ruleTraded + ", alerts " + world.alerts + ", orders open "
                + world.store.book().size() + ", offers open " + world.store.offers().size() + ", rules "
                + world.store.rules().size());
        System.out.println("[fuzz] steps run " + world.stepsRun + ", skipped " + world.stepsSkipped + ", lots held "
                + lotsHeld(world) + ", effects " + result.effects());
    }

    private static long lotsHeld(FuzzWorld world) {
        long[] count = new long[1];
        world.store.forEachLot(lot -> count[0]++);
        return count[0];
    }

    @Test void anotherSeedHoldsToo() {
        OperationFuzzer.Result first = OperationFuzzer.run(42L, 20_000, Set.of(), false);
        assertTrue(first.world().stepsRun > 0L);
        // The same seed is the same run, operation for operation; a different seed is a different one.
        OperationFuzzer.Result again = OperationFuzzer.run(42L, 20_000, Set.of(), false);
        assertEquals(first.world().fingerprint(), again.world().fingerprint());
        assertEquals(first.calls(), again.calls());
        assertNotEquals(first.world().digest, OperationFuzzer.run(43L, 5_000, Set.of(), false).world().digest);
    }

    @Test void aCrashAtAnyPointLosesNothingAndTheRunCarriesOnExactlyAsIfItHadNotHappened() {
        for (long seed : new long[] {1L, 7L}) {
            // Forty-odd points picked at random, the same ones for both runs.
            Random picker = new Random(seed * 31L + 5L);
            Set<Integer> points = new HashSet<>();
            while (points.size() < 40) points.add(picker.nextInt(CRASH_RUN));
            // At each point the first run only carries on; the second writes the whole store out
            // with its codecs, throws it away, reads a fresh one back and carries on with that.
            OperationFuzzer.Result uninterrupted = OperationFuzzer.run(seed, CRASH_RUN, points, false);
            OperationFuzzer.Result crashed = OperationFuzzer.run(seed, CRASH_RUN, points, true);
            assertEquals(uninterrupted.world().digest, crashed.world().digest, "seed " + seed);
            assertEquals(uninterrupted.calls(), crashed.calls(), "seed " + seed);
            assertEquals(uninterrupted.effects(), crashed.effects(), "seed " + seed);
            assertEquals(uninterrupted.world().fingerprint(), crashed.world().fingerprint(), "seed " + seed);
            // And the store that came out of forty reloads is still a store that saves and loads.
            FuzzWorld world = crashed.world();
            String before = world.fingerprint();
            world.crash();
            world.check("final reload", CRASH_RUN);
            assertEquals(before, world.fingerprint(), "seed " + seed);
        }
    }
}
