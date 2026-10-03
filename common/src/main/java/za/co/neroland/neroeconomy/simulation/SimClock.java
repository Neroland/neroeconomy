package za.co.neroland.neroeconomy.simulation;

/**
 * The simulation clock: which steps are due. The economy advances in fixed steps of <b>economy
 * time</b> (see {@code EconomyClock}); a step's index is {@code floor(economy time / step length)},
 * so the same instant always names the same step, on any server and after any restart.
 *
 * <p>This class is pure arithmetic over two numbers — the last step that was completed and the
 * economy time now — and decides nothing from game ticks, the day/night cycle, {@code /tick},
 * {@code /time} or sleeping. Economy time itself never runs backwards and never jumps more than 72
 * hours at once, whatever the wall clock does.
 *
 * <ul>
 *   <li><b>Normal running.</b> When the step index moves on, the steps in between are due.</li>
 *   <li><b>Catch-up.</b> After a pause or a shutdown many steps are due. If more than
 *       {@code maxCatchUp} are missing, the clock skips forward so that only the last
 *       {@code maxCatchUp} are replayed.</li>
 *   <li><b>A few per tick.</b> At most {@code maxPerTick} steps are handed out per call, so a long
 *       catch-up is spread over many ticks instead of landing in one.</li>
 *   <li><b>First run.</b> A world that has never run a step starts at the current step with
 *       nothing to replay.</li>
 * </ul>
 */
public final class SimClock {
    /** The last-step value of a simulation that has never run. */
    public static final long NEVER = -1L;
    /** Steps handed out per call unless the caller says otherwise. */
    public static final int DEFAULT_STEPS_PER_TICK = 2;

    private SimClock() { }

    /**
     * What to do now.
     *
     * @param first   index of the first step to run; meaningless when {@code count} is 0
     * @param count   how many consecutive steps to run now, starting at {@code first}
     * @param base    the last-step value to record before running anything: the old one, moved
     *                forward when steps were skipped or the simulation is starting
     * @param skipped steps that will never be run because they fell outside the catch-up cap
     * @param behind  steps still due after this batch; above 0 means the simulation is catching up
     */
    public record Batch(long first, int count, long base, long skipped, long behind) {
        /** Index of the last step of this batch; {@code base} when it runs nothing. */
        public long last() { return base + count; }
        public boolean catchingUp() { return behind > 0L; }
    }

    /** The step an economy time falls in. */
    public static long stepIndex(long economyMillis, long stepMillis) {
        return Math.floorDiv(economyMillis, Math.max(1L, stepMillis));
    }

    /** The economy time at which a step becomes due: its start. */
    public static long stepTime(long step, long stepMillis) {
        long length = Math.max(1L, stepMillis);
        return step > Long.MAX_VALUE / length ? Long.MAX_VALUE : step * length;
    }

    /**
     * The steps due at {@code economyMillis}.
     *
     * @param lastStep   the last completed step, or {@link #NEVER}
     * @param stepMillis the length of a step
     * @param maxCatchUp the most steps that are ever replayed after a gap; at least 1
     * @param maxPerTick the most steps to run in this call; at least 1
     */
    public static Batch next(long lastStep, long economyMillis, long stepMillis, long maxCatchUp, int maxPerTick) {
        long current = stepIndex(economyMillis, stepMillis);
        // Never run before, or a stored value that cannot be a step: start here, replay nothing.
        if (lastStep < 0L) return new Batch(current, 0, current, 0L, 0L);
        long missing = current - lastStep;
        // Nothing due. The clock does not run backwards; should the index be behind anyway (a
        // forced step ran ahead), wait for time to reach it rather than run a step twice.
        if (missing <= 0L) return new Batch(lastStep + 1L, 0, lastStep, 0L, 0L);
        long cap = Math.max(1L, maxCatchUp);
        long skipped = Math.max(0L, missing - cap);
        long base = lastStep + skipped;
        int count = (int) Math.min(Math.max(1, maxPerTick), current - base);
        return new Batch(base + 1L, count, base, skipped, current - base - count);
    }

    /**
     * Carries a last-step value over to a different step length, so that changing the length in
     * the configuration neither replays a flood of steps nor stalls the simulation: the new value
     * is the step, at the new length, that the old one's time falls in.
     */
    public static long rebase(long lastStep, long oldStepMillis, long newStepMillis) {
        if (lastStep < 0L || oldStepMillis <= 0L || oldStepMillis == newStepMillis) return lastStep;
        return stepIndex(stepTime(lastStep, oldStepMillis), newStepMillis);
    }
}
