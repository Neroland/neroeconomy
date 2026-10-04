package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimRunner;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.trade.ItemCatalog;

/**
 * What the price step costs, measured through the same handler, pipeline and runner the game
 * uses, with the twelve shipped companies. A server tick is 50 ms; the budgets below are generous
 * so a busy build machine does not fail them, and still far inside what a tick can spare.
 */
class ExchangePerformanceTest {
    private static final long STEP = ExchangeTesting.STEP;
    /** 72 hours of ten-minute steps: the most a catch-up ever replays. */
    private static final int CATCH_UP = 432;
    /** One step for twelve companies has to be far under a millisecond. */
    private static final long STEP_BUDGET_NANOS = 500_000L;
    /** A whole 72-hour catch-up has to fit well inside a few ticks, were it ever run at once. */
    private static final long CATCH_UP_BUDGET_NANOS = 100_000_000L;

    private static SimPipeline pipeline(StockRegistry registry) {
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new ExchangeStep(new ExchangeStep.Wiring() {
            @Override public ExchangeSettings settings() { return ExchangeSettings.DEFAULTS; }
            @Override public StockRegistry registry(SimContext context) { return registry; }
            @Override public ItemCatalog items(SimContext context) { return ItemCatalog.OPEN; }
            @Override public double shock(java.util.List<String> keys, long now) { return 1.0D; }
        }));
        return pipeline;
    }

    /** Runs {@code steps} steps in one call, as a catch-up with no per-tick limit would. @return nanoseconds */
    private static long time(EconomyStore store, SimPipeline pipeline, long from, int steps) {
        SimRunner.Settings settings = new SimRunner.Settings(STEP, steps, steps, true);
        long started = System.nanoTime();
        SimRunner.Progress progress = SimRunner.advance(store, null, pipeline, (from + steps) * STEP, settings, null);
        long taken = System.nanoTime() - started;
        assertEquals(steps, progress.ran());
        return taken;
    }

    @Test void oneStepIsFarUnderAMillisecondAndA72HourCatchUpFitsInAFewTicks() {
        StockRegistry registry = ExchangeTesting.shipped();
        assertEquals(12, registry.stocks().size());
        SimPipeline pipeline = pipeline(registry);
        EconomyStore store = new EconomyStore();
        SimTesting.fixSecret(store, SimTesting.secret(11));
        long at = ExchangeTesting.DAY_START;
        SimRunner.advance(store, null, pipeline, at * STEP, new SimRunner.Settings(STEP, 1L, 1, true), null);
        // Warm-up: the first steps pay for class loading and compilation, which a server pays once.
        time(store, pipeline, at, 2_000);
        at += 2_000L;

        // The best of five runs: what the code costs, not what the build machine was doing.
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            best = Math.min(best, time(store, pipeline, at, CATCH_UP));
            at += CATCH_UP;
        }
        long perStep = best / CATCH_UP;
        System.out.println("[exchange performance] 432 steps of 12 companies: " + best / 1_000L + " µs, "
                + perStep / 1_000L + " µs per step");
        assertTrue(perStep < STEP_BUDGET_NANOS, "one step took " + perStep + " ns");
        assertTrue(best < CATCH_UP_BUDGET_NANOS, "a 72-hour catch-up took " + best + " ns");
        assertEquals(12, store.exchange().size());
        assertEquals(at, store.exchange().lastStep());
    }
}
