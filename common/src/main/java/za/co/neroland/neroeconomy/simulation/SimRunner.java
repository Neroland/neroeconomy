package za.co.neroland.neroeconomy.simulation;

import java.util.function.BiConsumer;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * Drives the simulation: asks {@link SimClock} which steps are due, runs each one through a
 * {@link SimPipeline}, and records how far it got in the store's {@link SimulationSection} after
 * every step. It holds no state and reads no configuration, so the game and the tests run the very
 * same code — the game from its server tick, a test with a store, a time and no server.
 */
public final class SimRunner {
    private SimRunner() { }

    /**
     * How the simulation is set up.
     *
     * @param stepMillis      the length of a step, in milliseconds of economy time
     * @param maxCatchUpSteps the most steps replayed after a gap
     * @param maxPerTick      the most steps run in one call to {@link #advance}
     * @param autonomous      whether handlers that are not housekeeping run
     */
    public record Settings(long stepMillis, long maxCatchUpSteps, int maxPerTick, boolean autonomous) {
        public Settings {
            if (stepMillis < 1L || maxCatchUpSteps < 1L || maxPerTick < 1)
                throw new IllegalArgumentException("simulation settings");
        }
    }

    /**
     * What one call did.
     *
     * @param ran      steps run in this call
     * @param skipped  steps skipped for good because they fell outside the catch-up cap
     * @param behind   steps still due; above 0 means the simulation is catching up
     * @param lastStep the last completed step now
     */
    public record Progress(int ran, long skipped, long behind, long lastStep) {
        public boolean catchingUp() { return behind > 0L; }
    }

    /**
     * Runs the steps that are due at economy time {@code now}, at most
     * {@code settings.maxPerTick()} of them.
     *
     * @param server   the running server, or null in a test
     * @param failures told a handler's name and exception the first time it fails in a session
     */
    public static Progress advance(EconomyStore store, MinecraftServer server, SimPipeline pipeline, long now,
            Settings settings, BiConsumer<String, RuntimeException> failures) {
        SimulationSection section = store.simulation();
        section.useStepLength(settings.stepMillis());
        SimClock.Batch batch = SimClock.next(section.lastStep(), now, settings.stepMillis(),
                settings.maxCatchUpSteps(), settings.maxPerTick());
        // A first start or a skip forward is recorded before anything runs, so it is never redone.
        section.setLastStep(batch.base());
        run(store, server, pipeline, now, settings, failures, batch.first(), batch.count());
        return new Progress(batch.count(), batch.skipped(), batch.behind(), section.lastStep());
    }

    /**
     * Runs {@code steps} steps now, whether or not they are due: an operator's tool for testing.
     * The simulation is then ahead of the clock and simply waits for time to catch up, so no step
     * ever runs twice. Handlers see the forced steps' own times through {@link SimContext#time()},
     * but judge stored deadlines against the real time (see {@link SimContext#deadlineTime()}).
     *
     * @return how many steps ran
     */
    public static int force(EconomyStore store, MinecraftServer server, SimPipeline pipeline, long now,
            Settings settings, int steps, BiConsumer<String, RuntimeException> failures) {
        SimulationSection section = store.simulation();
        section.useStepLength(settings.stepMillis());
        if (section.lastStep() == SimClock.NEVER)
            section.setLastStep(SimClock.stepIndex(now, settings.stepMillis()));
        int count = Math.max(0, steps);
        run(store, server, pipeline, now, settings, failures, section.lastStep() + 1L, count);
        return count;
    }

    private static void run(EconomyStore store, MinecraftServer server, SimPipeline pipeline, long now,
            Settings settings, BiConsumer<String, RuntimeException> failures, long first, int count) {
        if (count <= 0) return;
        SimulationSection section = store.simulation();
        byte[] secret = section.secret();
        for (int index = 0; index < count; index++) {
            long step = first + index;
            pipeline.run(new SimContext(store, server, secret, "", step, settings.stepMillis(), now,
                    settings.autonomous()), failures);
            // Saved with whatever the step changed, in the same store: a step is done or not done.
            section.setLastStep(step);
        }
    }

    /** How many steps are due at {@code now} and not yet run; 0 for a simulation that has never run. */
    public static long behind(SimulationSection section, long now, long stepMillis) {
        long last = SimClock.rebase(section.lastStep(), section.stepMillis(), stepMillis);
        return last < 0L ? 0L : Math.max(0L, SimClock.stepIndex(now, stepMillis) - last);
    }
}
