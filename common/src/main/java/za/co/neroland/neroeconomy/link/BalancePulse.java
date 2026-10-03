package za.co.neroland.neroeconomy.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides when a player's balance change becomes a live {@code balance} event. Pure bookkeeping on
 * an injected nanosecond clock, so the rules are unit tested without the game.
 *
 * <p><b>Coalescing.</b> Any number of changes inside {@link #MIN_INTERVAL_NANOS} produce one event,
 * carrying the balance as it stands when the event is built.
 *
 * <p><b>Interest.</b> An event is only produced for a player whose companion app asked this module
 * for something within the last {@link #INTEREST_WINDOW_NANOS}. The bridge turns a player-scoped
 * event into a push notification whenever that player has no live socket and has opted into the
 * module's notification category, and it offers a module no way to ask whether a socket is open.
 * Without this rule every routine in-game purchase would buzz the phone of a player whose app is
 * closed. With it, a closed app stops receiving events at most one window after its last request.
 *
 * <p>The keys are player ids held in memory only: never logged or sent, dropped on erasure
 * ({@link #forget}) and when the server unbinds ({@link #clear}).
 */
final class BalancePulse {
    static final long MIN_INTERVAL_NANOS = 1_000_000_000L;
    static final long INTEREST_WINDOW_NANOS = 120L * 1_000_000_000L;
    /** Past this many tracked players the expired entries are swept on the next request. */
    private static final int SWEEP_THRESHOLD = 256;

    private final Map<UUID, Long> interest = new HashMap<>();
    private final Map<UUID, Long> published = new HashMap<>();
    private final Set<UUID> pending = new LinkedHashSet<>();
    private volatile boolean anyPending;

    /** The player's app just asked this module for a snapshot or an action. */
    synchronized void interested(UUID player, long now) {
        if (player == null) return;
        if (interest.size() >= SWEEP_THRESHOLD) interest.values().removeIf(at -> now - at >= INTEREST_WINDOW_NANOS);
        interest.put(player, now);
    }

    /** The player's balance changed. Cheap and non-throwing: it runs inside the ledger call that changed it. */
    synchronized void changed(UUID player, long now) {
        if (player == null || !interestedAt(player, now)) return;
        pending.add(player);
        anyPending = true;
    }

    /** Lock-free check for the tick: true only while some change is waiting to be published. */
    boolean hasPending() { return anyPending; }

    /**
     * The players whose event is due now. A player published less than {@link #MIN_INTERVAL_NANOS}
     * ago stays pending for a later call; one whose interest has lapsed is dropped unpublished.
     */
    synchronized List<UUID> due(long now) {
        List<UUID> out = new ArrayList<>();
        for (Iterator<UUID> iterator = pending.iterator(); iterator.hasNext();) {
            UUID player = iterator.next();
            if (!interestedAt(player, now)) {
                iterator.remove();
                continue;
            }
            Long last = published.get(player);
            if (last != null && now - last < MIN_INTERVAL_NANOS) continue;
            iterator.remove();
            published.put(player, now);
            out.add(player);
        }
        anyPending = !pending.isEmpty();
        // A publish time only matters for one interval; nothing older is worth keeping.
        if (!anyPending) published.values().removeIf(at -> now - at >= MIN_INTERVAL_NANOS);
        return out;
    }

    private boolean interestedAt(UUID player, long now) {
        Long at = interest.get(player);
        return at != null && now - at < INTEREST_WINDOW_NANOS;
    }

    synchronized void forget(UUID player) {
        if (player == null) return;
        interest.remove(player);
        published.remove(player);
        pending.remove(player);
        anyPending = !pending.isEmpty();
    }

    synchronized void clear() {
        interest.clear();
        published.clear();
        pending.clear();
        anyPending = false;
    }

    /** Test seam: how many players this object currently holds anything for. */
    synchronized int tracked() {
        Set<UUID> all = new LinkedHashSet<>(interest.keySet());
        all.addAll(published.keySet());
        all.addAll(pending);
        return all.size();
    }
}
