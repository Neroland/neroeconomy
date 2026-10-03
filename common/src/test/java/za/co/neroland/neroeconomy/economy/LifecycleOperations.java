package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.simulation.SimRunner;

/**
 * The fuzzer's operations on time and on whole accounts: the clock moving in every way a wall clock
 * can, the simulation catching up with it, and the erasure of a player.
 */
final class LifecycleOperations {
    private LifecycleOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new SimulationSteps(), new DayRollOver(), new ClockJump(), new Erase());
    }

    /**
     * The server tick's simulation call: the steps that are due run, two at most. Housekeeping may
     * mint the Reserve's daily top-up; nothing else a step does may change the supply.
     */
    static final class SimulationSteps implements FuzzOperation {
        @Override public String name() { return "sim_steps"; }
        @Override public int weight() { return 160; }
        @Override public long run(FuzzWorld world) {
            EconomySavedData ledger = world.ledger();
            long before = ledger.totalBalancesForTest();
            long mintedBefore = reserveMinted(world);
            SimRunner.Progress progress = SimRunner.advance(world.store, null, world.pipeline, ledger.now(),
                    world.settings, (handler, failure) -> { throw new AssertionError(handler, failure); });
            long minted = reserveMinted(world) - mintedBefore;
            assertTrue(minted >= 0L);
            assertEquals(minted, ledger.totalBalancesForTest() - before);
            world.supply += minted;
            world.stepsRun += progress.ran();
            world.stepsSkipped += progress.skipped();
            assertTrue(progress.ran() <= world.settings.maxPerTick());
            assertTrue(progress.behind() <= world.settings.maxCatchUpSteps());
            return progress.ran() * 1_000_003L + progress.skipped() * 31L + minted;
        }

        private static long reserveMinted(FuzzWorld world) {
            return world.ledger().supplyReport(0L).mintedByReason().getOrDefault(TransactionType.RESERVE_MINT, 0L);
        }
    }

    /** A day goes by with nothing else happening: budgets start afresh and a top-up falls due. */
    static final class DayRollOver implements FuzzOperation {
        @Override public String name() { return "day_roll_over"; }
        @Override public int weight() { return 1; }
        @Override public long run(FuzzWorld world) {
            long before = EconomySavedData.economyDay(world.ledger().now());
            world.wall += FuzzWorld.DAY_MILLIS + world.random.nextInt(3_600_000);
            long days = EconomySavedData.economyDay(world.ledger().now()) - before;
            assertTrue(days >= 1L);
            return days;
        }
    }

    /**
     * The wall clock is set back, or far ahead. Economy time must never run backwards, and never
     * jump more than 72 hours however far ahead the wall clock went.
     */
    static final class ClockJump implements FuzzOperation {
        @Override public String name() { return "clock_jump"; }
        @Override public int weight() { return 1; }
        @Override public long run(FuzzWorld world) {
            long before = world.ledger().now();
            if (world.random.nextBoolean()) world.wall -= 1L + world.random.nextInt(86_400_000);
            else world.wall += 100L * 3_600_000L + world.random.nextInt(86_400_000);
            long passed = world.ledger().now() - before;
            assertTrue(passed >= 0L);
            assertTrue(passed <= EconomyClock.MAX_FORWARD_STEP_MILLIS);
            return passed;
        }
    }

    /**
     * A player is erased: their balance and whatever escrow was theirs go to the Reserve, and their
     * goods are destroyed. The account can be opened again afterwards, with nothing in it.
     */
    static final class Erase implements FuzzOperation {
        @Override public String name() { return "erase"; }
        @Override public int weight() { return 3; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            EconomySavedData ledger = world.ledger();
            long destroyed = 0L;
            for (Lot lot : world.vault().forgetPlayer(player)) {
                world.destroyedInStore(lot.key(), lot.count());
                destroyed += lot.count();
            }
            long owed = world.owed(player);
            assertEquals(owed, ledger.sweepErasedEscrow(owed));
            world.owe(player, -owed);
            // The trading sections go in the order the game erases them: the book and offers, then
            // the market's claims. Each sweeps its own escrow; the erased player's goods on the
            // book and in offers they sent are destroyed, and offers sent to them go back.
            for (Lot lot : world.store.book().forgetPlayer(player)) {
                world.destroyedInStore(lot.key(), lot.count());
                destroyed += lot.count();
            }
            for (Lot lot : world.store.offers().forgetPlayer(world.env, player)) {
                world.destroyedInStore(lot.key(), lot.count());
                destroyed += lot.count();
            }
            assertEquals(0, world.store.offers().outgoing(FuzzWorld.account(player)));
            assertEquals(0, world.store.offers().incoming(FuzzWorld.account(player)));
            world.store.rules().forgetPlayer(player);
            assertEquals(0, world.store.rules().count(FuzzWorld.account(player)));
            world.store.market().forgetPlayer(player);
            assertEquals(0, world.store.book().sellCount(FuzzWorld.account(player)));
            assertEquals(0, world.store.book().buyCount(FuzzWorld.account(player)));
            assertEquals(0L, world.store.market().creditClaimTotal(player));
            ledger.forgetPlayer(player);
            assertEquals(0L, ledger.getBalance(player, NeroEconomyCommon.CREDITS));
            assertEquals(0, world.vault().stackCount(player));
            return 1L + destroyed + owed;
        }
    }
}
