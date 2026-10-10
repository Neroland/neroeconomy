package za.co.neroland.neroeconomy.network;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-player token buckets for serverbound intents. Each player has one bucket per {@link Budget};
 * a bucket starts full, refills continuously and never holds more than its burst. A packet that
 * finds its bucket empty is dropped by the caller without any reply, so a flood costs the server one
 * map lookup per packet and tells the sender nothing.
 *
 * <p>The player id is a key held in memory only: it is never logged, sent or persisted, and the
 * entry is removed when the player logs out ({@link #forget}) and when the server stops
 * ({@link #clear}). The clock is injected so the arithmetic is unit tested without waiting.
 */
public final class RateLimiter {
    /** What a packet is allowed to cost. Value-moving intents get the tighter budget. */
    public enum Budget {
        /** Read-only requests: page queries, tab switches, price nudges. */
        QUERY(10, 5),
        /** Anything that moves credits, goods or an obligation. */
        VALUE(6, 2),
        /** Another mod's calls through the cross-mod API, per calling mod (not per player). */
        SIBLING(64, 16);

        private final int burst;
        private final int perSecond;

        Budget(int burst, int perSecond) {
            this.burst = burst;
            this.perSecond = perSecond;
        }

        public int burst() { return burst; }
        public int perSecond() { return perSecond; }
    }

    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

    private static final class Bucket {
        private double tokens;
        private long refilledAt;
        private Bucket(double tokens, long refilledAt) {
            this.tokens = tokens;
            this.refilledAt = refilledAt;
        }
    }

    private final LongSupplier nanoClock;
    private final Map<UUID, EnumMap<Budget, Bucket>> buckets = new HashMap<>();

    /** @param nanoClock a monotonic nanosecond clock, such as {@code System::nanoTime} */
    public RateLimiter(LongSupplier nanoClock) {
        if (nanoClock == null) throw new IllegalArgumentException("nanoClock");
        this.nanoClock = nanoClock;
    }

    /** Takes one token from the player's bucket. @return false when the bucket is empty. */
    public synchronized boolean tryAcquire(UUID player, Budget budget) {
        if (player == null || budget == null) return false;
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(player, ignored -> new EnumMap<>(Budget.class))
                .computeIfAbsent(budget, ignored -> new Bucket(budget.burst, now));
        // A clock that stepped backwards refills nothing; it never takes tokens away.
        long elapsed = Math.max(0L, now - bucket.refilledAt);
        bucket.tokens = Math.min(budget.burst, bucket.tokens + elapsed / NANOS_PER_SECOND * budget.perSecond);
        bucket.refilledAt = now;
        if (bucket.tokens < 1.0D) return false;
        bucket.tokens -= 1.0D;
        return true;
    }

    /** Logout: drops everything held for one player. */
    public synchronized void forget(UUID player) {
        if (player != null) buckets.remove(player);
    }

    /** Server stop: drops everything. */
    public synchronized void clear() { buckets.clear(); }

    /** How many players currently have a bucket; exists so the clearing rules can be tested. */
    public synchronized int tracked() { return buckets.size(); }
}
