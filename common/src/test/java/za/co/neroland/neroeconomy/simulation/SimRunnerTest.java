package za.co.neroland.neroeconomy.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;

/**
 * The step pipeline and its driver, run exactly as the game runs them but with a store, a time and
 * no server.
 */
class SimRunnerTest {
    private static final long STEP = 600_000L;
    private static final SimRunner.Settings SETTINGS = new SimRunner.Settings(STEP, 432L, 2, true);
    private static final SimRunner.Settings HOUSEKEEPING_ONLY = new SimRunner.Settings(STEP, 432L, 2, false);

    /** Records every call it gets, as "name@step". */
    private static final class Recorder implements SimStep {
        private final String name;
        private final boolean housekeeping;
        private final List<String> log;
        Recorder(String name, boolean housekeeping, List<String> log) {
            this.name = name;
            this.housekeeping = housekeeping;
            this.log = log;
        }
        @Override public String name() { return name; }
        @Override public boolean housekeeping() { return housekeeping; }
        @Override public void run(SimContext context, long step) { log.add(name + "@" + step); }
    }

    private static EconomyStore store() {
        EconomyStore store = new EconomyStore();
        SimTesting.fixSecret(store, SimTesting.secret(9));
        return store;
    }

    @Test void handlersRunInRegistrationOrderOnceForEveryStep() {
        List<String> log = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        pipeline.register(new Recorder("houses", false, log));
        pipeline.register(new Recorder("exchange", false, log));
        assertEquals(List.of("books", "houses", "exchange"), pipeline.names());
        EconomyStore store = store();
        // First call: the simulation starts at the current step and replays nothing.
        SimRunner.Progress start = SimRunner.advance(store, null, pipeline, 100L * STEP, SETTINGS, null);
        assertEquals(0, start.ran());
        assertEquals(100L, start.lastStep());
        assertTrue(log.isEmpty());
        // Later in the same step: still nothing.
        assertEquals(0, SimRunner.advance(store, null, pipeline, 100L * STEP + 599_999L, SETTINGS, null).ran());
        // The step index moves on: one step, every handler, in order.
        SimRunner.Progress one = SimRunner.advance(store, null, pipeline, 101L * STEP, SETTINGS, null);
        assertEquals(1, one.ran());
        assertEquals(101L, one.lastStep());
        assertFalse(one.catchingUp());
        assertEquals(List.of("books@101", "houses@101", "exchange@101"), log);
        assertEquals(101L, store.simulation().lastStep());
        assertEquals(STEP, store.simulation().stepMillis());
    }

    @Test void aNameIsNeededAndMayNotBeUsedTwice() {
        SimPipeline pipeline = new SimPipeline();
        List<String> log = new ArrayList<>();
        pipeline.register(new Recorder("books", true, log));
        assertThrows(IllegalArgumentException.class, () -> pipeline.register(new Recorder("books", false, log)));
        assertThrows(IllegalArgumentException.class, () -> pipeline.register(new Recorder(" ", false, log)));
        assertThrows(IllegalArgumentException.class, () -> pipeline.register(null));
        assertEquals(List.of("books"), pipeline.names());
    }

    @Test void withTheAutonomousEconomyOffOnlyHousekeepingRuns() {
        List<String> log = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        pipeline.register(new Recorder("houses", false, log));
        EconomyStore store = store();
        SimRunner.advance(store, null, pipeline, 100L * STEP, HOUSEKEEPING_ONLY, null);
        SimRunner.advance(store, null, pipeline, 101L * STEP, HOUSEKEEPING_ONLY, null);
        assertEquals(List.of("books@101"), log);
        // Switched back on, the next step runs everything; the step that was housekeeping-only is not redone.
        SimRunner.advance(store, null, pipeline, 102L * STEP, SETTINGS, null);
        assertEquals(List.of("books@101", "books@102", "houses@102"), log);
    }

    @Test void aHandlerThatThrowsIsSkippedReportedOnceAndNeverStopsTheRest() {
        List<String> log = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        pipeline.register(new SimStep() {
            @Override public String name() { return "broken"; }
            @Override public void run(SimContext context, long step) { throw new IllegalStateException("boom " + step); }
        });
        pipeline.register(new Recorder("exchange", false, log));
        EconomyStore store = store();
        SimRunner.advance(store, null, pipeline, 100L * STEP, SETTINGS, null);
        for (long step = 101L; step <= 105L; step++)
            SimRunner.advance(store, null, pipeline, step * STEP, SETTINGS,
                    (name, failure) -> reports.add(name + ":" + failure.getClass().getSimpleName()));
        // Five steps, the handlers either side of the broken one ran every time, and the step counted.
        assertEquals(10, log.size());
        assertEquals("books@105", log.get(8));
        assertEquals("exchange@105", log.get(9));
        assertEquals(105L, store.simulation().lastStep());
        // One report for the whole session, not one per step.
        assertEquals(List.of("broken:IllegalStateException"), reports);
        // A new session reports it once more.
        pipeline.resetReports();
        SimRunner.advance(store, null, pipeline, 106L * STEP, SETTINGS,
                (name, failure) -> reports.add(name + ":" + failure.getClass().getSimpleName()));
        assertEquals(2, reports.size());
        // With nobody to report to, a failure is still contained.
        SimRunner.advance(store, null, pipeline, 107L * STEP, SETTINGS, null);
        assertEquals(107L, store.simulation().lastStep());
    }

    @Test void aLongGapIsReplayedAFewStepsPerCallAndNeverPastTheCap() {
        List<String> log = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        EconomyStore store = store();
        SimRunner.advance(store, null, pipeline, 1_000L * STEP, SETTINGS, null);
        // A thousand steps later (about a week): 568 are skipped, 432 are replayed two at a time.
        long now = 2_000L * STEP;
        SimRunner.Progress first = SimRunner.advance(store, null, pipeline, now, SETTINGS, null);
        assertEquals(568L, first.skipped());
        assertEquals(2, first.ran());
        assertEquals(430L, first.behind());
        assertTrue(first.catchingUp());
        assertEquals(List.of("books@1569", "books@1570"), log);
        int calls = 1;
        while (SimRunner.advance(store, null, pipeline, now, SETTINGS, null).ran() > 0) calls++;
        assertEquals(216, calls);
        assertEquals(432, log.size());
        assertEquals("books@2000", log.get(431));
        assertEquals(2_000L, store.simulation().lastStep());
        assertEquals(0L, SimRunner.behind(store.simulation(), now, STEP));
    }

    @Test void aRestartInTheMiddleOfACatchUpCarriesOnWhereItStopped() {
        List<String> log = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        EconomyStore store = store();
        SimRunner.advance(store, null, pipeline, 1_000L * STEP, SETTINGS, null);
        long now = 1_020L * STEP;
        for (int call = 0; call < 3; call++) SimRunner.advance(store, null, pipeline, now, SETTINGS, null);
        assertEquals(1_006L, store.simulation().lastStep());
        // The server stops: the section is saved and read back into a fresh store.
        JsonElement saved = SimulationSection.CODEC.encodeStart(JsonOps.INSTANCE, store.simulation()).getOrThrow();
        SimulationSection restored = SimulationSection.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(1_006L, restored.lastStep());
        assertEquals(14L, SimRunner.behind(restored, now, STEP));
        EconomyStore reopened = StoreTesting.reload(store);
        assertArrayEquals(SimTesting.secret(9), SimTesting.secretOf(reopened.simulation()));
        // Carrying on: step 1,007 is next, and none of 1,001 to 1,006 runs again.
        while (SimRunner.advance(reopened, null, pipeline, now, SETTINGS, null).ran() > 0) { }
        assertEquals(20, log.size());
        for (int index = 0; index < 20; index++) assertEquals("books@" + (1_001 + index), log.get(index));
        assertEquals(1_020L, reopened.simulation().lastStep());
    }

    @Test void forcedStepsRunNowAndTheClockThenWaitsForThem() {
        List<String> log = new ArrayList<>();
        List<Long> deadlines = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        pipeline.register(new SimStep() {
            @Override public String name() { return "deadlines"; }
            @Override public void run(SimContext context, long step) {
                assertEquals(step * STEP, context.time());
                assertEquals((step - 1L) * STEP, context.previousTime());
                deadlines.add(context.deadlineTime());
            }
        });
        EconomyStore store = store();
        long now = 500L * STEP + 1_234L;
        // Forcing before anything ever ran starts the count at the current step.
        assertEquals(3, SimRunner.force(store, null, pipeline, now, SETTINGS, 3, null));
        assertEquals(List.of("books@501", "books@502", "books@503"), log);
        assertEquals(503L, store.simulation().lastStep());
        // A forced step is ahead of the clock, so stored deadlines are judged against the real time.
        assertEquals(List.of(now, now, now), deadlines);
        // The clock reaches 501, 502, 503: nothing runs twice. At 504 the simulation moves again.
        for (long step = 501L; step <= 503L; step++)
            assertEquals(0, SimRunner.advance(store, null, pipeline, step * STEP, SETTINGS, null).ran());
        assertEquals(1, SimRunner.advance(store, null, pipeline, 504L * STEP, SETTINGS, null).ran());
        assertEquals("books@504", log.get(3));
        // A step that is due is judged at its own time.
        assertEquals(504L * STEP, deadlines.get(3));
        assertEquals(0, SimRunner.force(store, null, pipeline, now, SETTINGS, 0, null));
        assertEquals(0, SimRunner.force(store, null, pipeline, now, SETTINGS, -4, null));
    }

    @Test void aChangedStepLengthNeitherFloodsNorStalls() {
        List<String> log = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new Recorder("books", true, log));
        EconomyStore store = store();
        long now = 3_000_000L * STEP;
        SimRunner.advance(store, null, pipeline, now, SETTINGS, null);
        // An operator sets one-minute steps: nothing is suddenly due, and a minute later one step is.
        SimRunner.Settings minute = new SimRunner.Settings(60_000L, 432L, 2, true);
        assertEquals(0, SimRunner.advance(store, null, pipeline, now, minute, null).ran());
        assertEquals(60_000L, store.simulation().stepMillis());
        assertEquals(1, SimRunner.advance(store, null, pipeline, now + 60_000L, minute, null).ran());
        // And back to ten minutes: the simulation waits for the next ten-minute boundary and runs it.
        assertEquals(0, SimRunner.advance(store, null, pipeline, now + 120_000L, SETTINGS, null).ran());
        assertEquals(1, SimRunner.advance(store, null, pipeline, now + STEP, SETTINGS, null).ran());
        assertEquals(2, log.size());
    }

    @Test void eachHandlerGetsItsOwnRepeatableRandomStream() {
        List<Long> seen = new ArrayList<>();
        SimPipeline pipeline = new SimPipeline();
        for (String name : List.of("houses", "exchange")) pipeline.register(new SimStep() {
            @Override public String name() { return name; }
            @Override public void run(SimContext context, long step) {
                seen.add(context.random("price").nextLong());
                seen.add(context.random("volume").nextLong());
            }
        });
        EconomyStore first = store();
        SimRunner.force(first, null, pipeline, 100L * STEP, SETTINGS, 2, null);
        List<Long> run = List.copyOf(seen);
        // Eight draws: two handlers, two labels, two steps — all different.
        assertEquals(8, run.stream().distinct().count());
        // The same secret, handlers and steps on a different store give exactly the same numbers.
        seen.clear();
        SimRunner.force(store(), null, pipeline, 100L * STEP, SETTINGS, 2, null);
        assertEquals(run, seen);
        // They are what the documented derivation says, so a handler can be checked in isolation.
        assertEquals(SimRandom.of(SimTesting.secret(9), "exchange", 102L, "volume").nextLong(), run.get(7));
        assertEquals(run.get(0), SimTesting.context(first, "houses", 101L, STEP, 0L, true).random("price").nextLong());
        // A different secret changes every one of them.
        seen.clear();
        EconomyStore other = new EconomyStore();
        SimTesting.fixSecret(other, SimTesting.secret(10));
        SimRunner.force(other, null, pipeline, 100L * STEP, SETTINGS, 2, null);
        for (int index = 0; index < 8; index++) assertFalse(run.get(index).equals(seen.get(index)));
    }

    @Test void theContextHandsOutTheStoreAndNoServerInATest() {
        EconomyStore store = store();
        SimContext context = SimTesting.context(store, "books", 7L, STEP, 7L * STEP + 5L, false);
        assertEquals(store, context.store());
        assertEquals(store.ledger(), context.ledger());
        assertEquals(store.vault(), context.vault());
        assertEquals(store.simulation(), context.simulation());
        assertNull(context.server());
        assertEquals(7L, context.step());
        assertEquals(STEP, context.stepMillis());
        assertEquals(7L * STEP, context.deadlineTime());
        assertFalse(context.autonomous());
    }

    @Test void settingsThatMakeNoSenseAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SimRunner.Settings(0L, 1L, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new SimRunner.Settings(1L, 0L, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new SimRunner.Settings(1L, 1L, 0, true));
    }
}
