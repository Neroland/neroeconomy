package za.co.neroland.neroeconomy.market;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player memory of the intent ids already acted on, so a replayed packet or Link action cannot
 * act twice. Bounded both ways: at most {@link #MAX_PER_PLAYER} ids per player and nothing older
 * than {@link #TTL_MILLIS}. The ids are random client-generated values and carry no meaning of
 * their own; the store persists them so a replay across a restart is still refused, and drops them
 * with the player on erasure. Pure Java so it is unit tested without the game.
 */
public final class RequestLedger {
    public static final int MAX_PER_PLAYER = 128;
    public static final long TTL_MILLIS = 600_000L;

    private final Map<UUID, LinkedHashMap<UUID, Long>> seen = new HashMap<>();

    /** Records {@code request} for {@code player}. @return false when it was already seen and is still live. */
    public boolean remember(UUID player, UUID request, long now) {
        if (player == null || request == null) return false;
        LinkedHashMap<UUID, Long> values = seen.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        Long prior = values.get(request);
        if (prior != null && now - prior < TTL_MILLIS) return false;
        values.remove(request);
        values.put(request, now);
        trim(values);
        return true;
    }

    /** Load path: re-inserts a persisted id in its saved order without judging its age. */
    public void restore(UUID player, UUID request, long at) {
        if (player == null || request == null) return;
        LinkedHashMap<UUID, Long> values = seen.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        values.put(request, at);
        trim(values);
    }

    /** Drops every id older than the time-to-live. @return true when anything was removed. */
    public boolean expire(long now) {
        boolean changed = false;
        for (var players = seen.entrySet().iterator(); players.hasNext();) {
            LinkedHashMap<UUID, Long> values = players.next().getValue();
            changed |= values.values().removeIf(at -> now - at >= TTL_MILLIS);
            if (values.isEmpty()) players.remove();
        }
        return changed;
    }

    /** Erasure: drops everything remembered for one player. @return true when anything was removed. */
    public boolean forget(UUID player) { return player != null && seen.remove(player) != null; }

    /** One player's live ids and when each was recorded, oldest first. */
    public Map<UUID, Long> of(UUID player) {
        LinkedHashMap<UUID, Long> values = seen.get(player);
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    /** Every player's ids, for persistence. */
    public Map<UUID, Map<UUID, Long>> snapshot() {
        Map<UUID, Map<UUID, Long>> copy = new LinkedHashMap<>();
        seen.forEach((player, values) -> copy.put(player, new LinkedHashMap<>(values)));
        return copy;
    }

    private static void trim(LinkedHashMap<UUID, Long> values) {
        var eldest = values.entrySet().iterator();
        while (values.size() > MAX_PER_PLAYER && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }
}
