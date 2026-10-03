package za.co.neroland.neroeconomy.simulation;

/**
 * One named part of a simulation step. Handlers are registered once, in a fixed order, with
 * {@link SimPipeline#register}, and every one of them is called for every step:
 * {@code run(context, stepIndex)}.
 *
 * <p>A handler must be a pure function of (state, step index, secret): it reads and writes the
 * store's sections through the context, takes its time from {@link SimContext#time()} and its
 * randomness from {@link SimContext#random(String)}, and uses nothing else — not the wall clock,
 * not the game's tick or day counters, not {@code java.util.Random}. That is what lets a missed
 * step be replayed after a restart and a step be checked in a unit test.
 *
 * <p>A handler that throws is caught by the pipeline, reported once per session, and skipped for
 * that step; the handlers after it still run.
 */
public interface SimStep {
    /** A stable, unique name: it keys the handler's random streams and its failure report. */
    String name();

    /**
     * Whether this handler keeps the books rather than acting in the market: the Reserve top-up,
     * expiry, retention. Housekeeping runs even when {@code autonomousEconomy} is off; everything
     * else (Merchant Houses, the exchange, system jobs) is switched off with it.
     */
    default boolean housekeeping() { return false; }

    void run(SimContext context, long step);
}
