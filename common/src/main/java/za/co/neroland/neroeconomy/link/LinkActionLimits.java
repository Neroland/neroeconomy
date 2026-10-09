package za.co.neroland.neroeconomy.link;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import za.co.neroland.neroeconomy.network.RateLimiter;

/**
 * Per-action rate limits for companion-app actions, on top of the bridge's own request budget and
 * the player's shared {@code VALUE} budget. Each action id has its own {@link RateLimiter}, read
 * through a clock slowed by {@link #SLOWDOWN}: a player may send any one action
 * {@code VALUE.burst()} times at once, and then once every {@code SLOWDOWN / VALUE.perSecond()}
 * seconds. Player ids are memory-only keys, dropped on erasure and when the server unbinds.
 */
final class LinkActionLimits {
    /** How much slower than the in-game {@code VALUE} budget one action refills. */
    static final long SLOWDOWN = 10L;

    private final LongSupplier nanoClock;
    private final Map<String, RateLimiter> limiters = new HashMap<>();

    LinkActionLimits(LongSupplier nanoClock) { this.nanoClock = nanoClock; }

    /** Takes one token for this player and action. @return false when the action must be refused */
    synchronized boolean tryAcquire(UUID player, String action) {
        if (player == null || action == null) return false;
        return limiters.computeIfAbsent(action, ignored -> new RateLimiter(() -> nanoClock.getAsLong() / SLOWDOWN))
                .tryAcquire(player, RateLimiter.Budget.VALUE);
    }

    synchronized void forget(UUID player) { limiters.values().forEach(limiter -> limiter.forget(player)); }

    synchronized void clear() { limiters.clear(); }
}
