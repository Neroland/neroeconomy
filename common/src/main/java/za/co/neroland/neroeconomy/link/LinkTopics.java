package za.co.neroland.neroeconomy.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Decides when one of a player's schema-2 topics ({@code orders}, {@code offers},
 * {@code portfolio}, {@code alerts}) becomes a live event. Pure bookkeeping on an injected
 * nanosecond clock, like {@link BalancePulse}, whose interest rule it shares: only a player whose
 * app asked recently gets events at all.
 *
 * <p><b>Changes.</b> A topic is either {@link #observe observed} — the module reads a cheap
 * signature of what the topic shows, at most once per {@link #CHECK_INTERVAL_NANOS} per player, and
 * a signature that differs from the last one makes the topic pending — or {@link #mark marked}
 * directly, as an alert is. The first signature seen for a topic only sets the baseline.
 *
 * <p><b>Throttle.</b> At most one event per topic per player per {@link #MIN_INTERVAL_NANOS}; any
 * number of changes in between become one event built from the state as it is then.
 *
 * <p>Player ids are held in memory only, never logged or sent, dropped on erasure ({@link #forget})
 * and when the server unbinds ({@link #clear}).
 */
final class LinkTopics {
    static final long MIN_INTERVAL_NANOS = 1_000_000_000L;
    static final long CHECK_INTERVAL_NANOS = 1_000_000_000L;
    /** Past this many tracked players, those no longer interested are swept. */
    private static final int SWEEP_THRESHOLD = 256;

    /** One event that is due: the topic and, for an alert, the alert kind's id. */
    record Due(UUID player, String topic, String detail) { }

    private final Map<UUID, Map<String, Long>> signatures = new HashMap<>();
    private final Map<UUID, Map<String, Long>> published = new HashMap<>();
    private final Map<UUID, Long> checked = new HashMap<>();
    private final Map<UUID, Map<String, String>> pending = new LinkedHashMap<>();

    /** Whether the player's signatures are due to be read again; stamps the read when they are. */
    synchronized boolean checkDue(UUID player, long now) {
        if (player == null) return false;
        Long last = checked.get(player);
        if (last != null && now - last < CHECK_INTERVAL_NANOS) return false;
        checked.put(player, now);
        return true;
    }

    /** Records a topic's signature; a changed one makes the topic pending. */
    synchronized void observe(UUID player, String topic, long signature, long now) {
        if (player == null || topic == null) return;
        Long before = signatures.computeIfAbsent(player, ignored -> new HashMap<>()).put(topic, signature);
        if (before != null && before != signature) pending.computeIfAbsent(player, ignored -> new LinkedHashMap<>())
                .put(topic, null);
    }

    /** Makes a topic pending directly. A later mark before the event goes out replaces the detail. */
    synchronized void mark(UUID player, String topic, String detail) {
        if (player == null || topic == null) return;
        pending.computeIfAbsent(player, ignored -> new LinkedHashMap<>()).put(topic, detail);
    }

    synchronized boolean hasPending() { return !pending.isEmpty(); }

    /**
     * The events due now. A topic published less than {@link #MIN_INTERVAL_NANOS} ago stays pending;
     * a player no longer {@code interested} is dropped with everything pending for them.
     */
    synchronized List<Due> due(long now, BiPredicate<UUID, Long> interested) {
        List<Due> out = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Map<String, String>>> players = pending.entrySet().iterator(); players.hasNext();) {
            Map.Entry<UUID, Map<String, String>> entry = players.next();
            UUID player = entry.getKey();
            if (!interested.test(player, now)) {
                players.remove();
                continue;
            }
            Map<String, Long> times = published.computeIfAbsent(player, ignored -> new HashMap<>());
            for (Iterator<Map.Entry<String, String>> topics = entry.getValue().entrySet().iterator(); topics.hasNext();) {
                Map.Entry<String, String> topic = topics.next();
                Long last = times.get(topic.getKey());
                if (last != null && now - last < MIN_INTERVAL_NANOS) continue;
                topics.remove();
                times.put(topic.getKey(), now);
                out.add(new Due(player, topic.getKey(), topic.getValue()));
            }
            if (entry.getValue().isEmpty()) players.remove();
        }
        if (checked.size() > SWEEP_THRESHOLD) {
            List<UUID> gone = new ArrayList<>();
            for (UUID player : checked.keySet()) if (!interested.test(player, now)) gone.add(player);
            gone.forEach(this::forget);
        }
        return out;
    }

    synchronized void forget(UUID player) {
        if (player == null) return;
        signatures.remove(player);
        published.remove(player);
        checked.remove(player);
        pending.remove(player);
    }

    synchronized void clear() {
        signatures.clear();
        published.clear();
        checked.clear();
        pending.clear();
    }

    /** Test seam: how many players this object holds anything for. */
    synchronized int tracked() {
        java.util.Set<UUID> all = new java.util.HashSet<>(signatures.keySet());
        all.addAll(published.keySet());
        all.addAll(checked.keySet());
        all.addAll(pending.keySet());
        return all.size();
    }
}
