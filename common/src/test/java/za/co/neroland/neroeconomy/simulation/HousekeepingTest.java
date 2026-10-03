package za.co.neroland.neroeconomy.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;

/** The handlers that keep the books: what they do on a step, and that they are always first. */
class HousekeepingTest {
    private static final long STEP = 600_000L;
    private static final long DAY = 86_400_000L;
    /** The defaults of {@code reserveInitial} and {@code reserveDailyMint}. */
    private static final long RESERVE = 250_000L;
    private static final long DAILY_MINT = 5_000L;

    // A clock that stands still, so no test here can straddle midnight.
    @BeforeEach void stopTheClock() { StoreTesting.wallClock(() -> 1_800_000_000_000L); }
    @AfterEach void realClockAgain() { StoreTesting.wallClock(null); }

    private static EconomyStore seeded() {
        EconomyStore store = new EconomyStore();
        store.ledger().seedReserve(RESERVE);
        return store;
    }

    /** The first step of the economy day {@code days} after today. */
    private static long firstStepOfDay(EconomyStore store, long days) {
        long day = EconomySavedData.economyDay(store.ledger().now()) + days;
        return day * DAY / STEP;
    }

    private static long reserve(EconomyStore store) { return store.ledger().balance(SystemAccountId.RESERVE); }

    @Test void theyAreHousekeepingAndRunInAFixedOrder() {
        SimPipeline pipeline = new SimPipeline();
        Housekeeping.register(pipeline);
        assertEquals(List.of("reserve_top_up", "day_rollover", "expiry"), pipeline.names());
        for (SimStep handler : List.of(Housekeeping.RESERVE, Housekeeping.DAY, Housekeeping.EXPIRY))
            assertTrue(handler.housekeeping(), handler.name());
        // The game's own pipeline starts with exactly these.
        assertEquals(pipeline.names(), Simulation.PIPELINE.names().subList(0, 3));
    }

    @Test void theReserveIsToppedUpOncePerEconomyDayOfTheStep() {
        EconomyStore store = seeded();
        long tomorrow = firstStepOfDay(store, 1L);
        // A step still inside the day the Reserve was filled: nothing.
        Housekeeping.RESERVE.run(SimTesting.context(store, "reserve_top_up", tomorrow - 1L, STEP, Long.MAX_VALUE, false),
                tomorrow - 1L);
        assertEquals(RESERVE, reserve(store));
        // The first step of the next day: one top-up, and no second one from the steps after it.
        for (long step = tomorrow; step < tomorrow + 5L; step++)
            Housekeeping.RESERVE.run(SimTesting.context(store, "reserve_top_up", step, STEP, Long.MAX_VALUE, false), step);
        assertEquals(RESERVE + DAILY_MINT, reserve(store));
        assertTrue(store.ledger().supplyBalanced());
        // A step four days on makes up at most three missed days.
        long later = firstStepOfDay(store, 5L);
        Housekeeping.RESERVE.run(SimTesting.context(store, "reserve_top_up", later, STEP, Long.MAX_VALUE, false), later);
        assertEquals(RESERVE + 4L * DAILY_MINT, reserve(store));
    }

    @Test void aStepForcedAheadOfTheClockDoesNotTopUpEarly() {
        EconomyStore store = seeded();
        long tomorrow = firstStepOfDay(store, 1L);
        // The step's own time is tomorrow, but it is being run today.
        Housekeeping.RESERVE.run(SimTesting.context(store, "reserve_top_up", tomorrow, STEP, store.ledger().now(), false),
                tomorrow);
        assertEquals(RESERVE, reserve(store));
    }

    @Test void theWholeHousekeepingStepRunsWithoutAServer() {
        EconomyStore store = seeded();
        SimTesting.fixSecret(store, SimTesting.secret(4));
        SimPipeline pipeline = new SimPipeline();
        Housekeeping.register(pipeline);
        List<String> failures = new ArrayList<>();
        SimRunner.Settings settings = new SimRunner.Settings(STEP, 432L, 2, false);
        long start = store.ledger().now();
        SimRunner.advance(store, null, pipeline, start, settings, (name, failure) -> failures.add(name));
        // A day and a bit later: a day boundary is crossed during the catch-up, two steps per call.
        long later = start + DAY + STEP;
        int calls = 0;
        while (SimRunner.advance(store, null, pipeline, later, settings, (name, failure) -> failures.add(name)).ran() > 0)
            calls++;
        assertEquals(List.of(), failures);
        assertEquals(73, calls);
        assertEquals(RESERVE + DAILY_MINT, reserve(store));
        assertTrue(store.checkInvariants());
    }
}
