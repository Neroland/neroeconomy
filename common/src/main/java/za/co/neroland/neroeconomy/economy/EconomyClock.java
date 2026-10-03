package za.co.neroland.neroeconomy.economy;

/**
 * The one clock every economy deadline is measured against. Economy time is the wall clock plus a
 * persisted offset, corrected on each observation so that:
 *
 * <ul>
 *   <li>it never runs backwards — a wall clock that jumps back by {@code d} adds {@code d} to the
 *       offset, so expiry watermarks and the retention sweep cannot stall;</li>
 *   <li>it advances by at most {@link #MAX_FORWARD_STEP_MILLIS} between two observations — a wild
 *       forward jump or a long shutdown has the excess subtracted from the offset, so listings,
 *       offers, jobs and auctions are not all expired in one pass.</li>
 * </ul>
 *
 * <p>Economy timestamps are therefore <b>not</b> wall-clock timestamps once the offset is non-zero:
 * compare them only with other economy timestamps, and pass them through {@link #toWallMillis}
 * before they leave the server. Pure Java with no game types, so it is unit
 * tested directly; the ledger store persists the two fields.
 */
public final class EconomyClock {
    /** Largest advance one observation may make; anything beyond it is treated as a clock jump. */
    public static final long MAX_FORWARD_STEP_MILLIS = 72L * 3_600_000L;

    private long offsetMillis;
    /** Wall clock at the previous observation; {@code 0} means "never observed" (a fresh or old save). */
    private long lastWallMillis;

    public EconomyClock() { }

    /** Restores persisted state. A non-positive {@code lastWallMillis} starts from the next observation. */
    public EconomyClock(long offsetMillis, long lastWallMillis) {
        this.offsetMillis = offsetMillis;
        this.lastWallMillis = Math.max(0L, lastWallMillis);
    }

    /** Folds one wall-clock reading into the clock and returns the economy time for it. */
    public synchronized long observe(long wallNow) {
        if (lastWallMillis > 0L) {
            long step = wallNow - lastWallMillis;
            if (step < 0L) offsetMillis -= step;
            else if (step > MAX_FORWARD_STEP_MILLIS) offsetMillis -= step - MAX_FORWARD_STEP_MILLIS;
        }
        lastWallMillis = wallNow;
        return wallNow + offsetMillis;
    }

    /**
     * The wall-clock instant an economy timestamp stands for under the current offset. This is what
     * leaves the server (a countdown sent to a client, a time shown in chat or in the companion
     * app): the remaining time to a deadline is then right on any clock that agrees with the
     * server's. A timestamp recorded before a later clock jump is shifted by that jump, so past
     * times are approximate. The "none" ({@code <= 0}) and "never" ({@link Long#MAX_VALUE}) sentinels
     * pass through untouched.
     */
    public synchronized long toWallMillis(long economyMillis) {
        if (economyMillis <= 0L || economyMillis == Long.MAX_VALUE) return economyMillis;
        try {
            return Math.subtractExact(economyMillis, offsetMillis);
        } catch (ArithmeticException overflow) {
            return economyMillis;
        }
    }

    public synchronized long offsetMillis() { return offsetMillis; }
    public synchronized long lastWallMillis() { return lastWallMillis; }
}
