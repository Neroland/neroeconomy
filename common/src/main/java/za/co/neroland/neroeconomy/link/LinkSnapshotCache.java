package za.co.neroland.neroeconomy.link;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * The last snapshot served per player, section and query, so a player who is over their request
 * budget gets what they last saw (marked {@code throttled}) instead of an empty object the app
 * would draw as "no data".
 *
 * <p>Each entry is the player's own snapshot and nothing else. It lives in memory only, is bounded
 * to {@link #MAX_ENTRIES} with the least recently used entry dropped first, and is dropped for a
 * player on erasure ({@link #forget}) and entirely when the server unbinds ({@link #clear}).
 * Snapshots are copied on the way in and on the way out, so a caller can never alter a stored one.
 */
final class LinkSnapshotCache {
    static final int MAX_ENTRIES = 512;

    private record Key(UUID player, String section, String variant) { }

    private final int capacity;
    /** Access-ordered: iteration starts at the least recently used entry. */
    private final Map<Key, JsonObject> entries = new LinkedHashMap<>(64, 0.75F, true);

    LinkSnapshotCache() { this(MAX_ENTRIES); }

    LinkSnapshotCache(int capacity) { this.capacity = Math.max(1, capacity); }

    synchronized void put(UUID player, String section, String variant, JsonObject snapshot) {
        if (player == null || section == null || variant == null || snapshot == null) return;
        entries.put(new Key(player, section, variant), snapshot.deepCopy());
        Iterator<Key> eldest = entries.keySet().iterator();
        while (entries.size() > capacity && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }

    /** A copy of the last snapshot stored under this key, or null. */
    synchronized JsonObject get(UUID player, String section, String variant) {
        JsonObject found = entries.get(new Key(player, section, variant));
        return found == null ? null : found.deepCopy();
    }

    synchronized void forget(UUID player) {
        if (player != null) entries.keySet().removeIf(key -> key.player().equals(player));
    }

    synchronized void clear() { entries.clear(); }

    synchronized int size() { return entries.size(); }
}
