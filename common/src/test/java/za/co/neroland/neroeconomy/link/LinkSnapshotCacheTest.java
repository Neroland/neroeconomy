package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

class LinkSnapshotCacheTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static JsonObject snapshot(long amount) {
        JsonObject out = new JsonObject();
        out.addProperty("amount", amount);
        return out;
    }

    @Test void returnsWhatWasStoredPerPlayerSectionAndQuery() {
        LinkSnapshotCache cache = new LinkSnapshotCache();
        cache.put(ALICE, "balance", "", snapshot(10));
        cache.put(ALICE, "market", "diamond|0", snapshot(20));
        cache.put(BOB, "balance", "", snapshot(30));
        assertEquals(10, cache.get(ALICE, "balance", "").get("amount").getAsLong());
        assertEquals(20, cache.get(ALICE, "market", "diamond|0").get("amount").getAsLong());
        assertEquals(30, cache.get(BOB, "balance", "").get("amount").getAsLong());
        // Another query, another section and another player are all misses: one player never sees
        // what was stored for another.
        assertNull(cache.get(ALICE, "market", "iron|0"));
        assertNull(cache.get(ALICE, "transactions", ""));
        assertNull(cache.get(BOB, "market", "diamond|0"));
    }

    @Test void storedSnapshotsCannotBeAlteredThroughWhatWentInOrCameOut() {
        LinkSnapshotCache cache = new LinkSnapshotCache();
        JsonObject original = snapshot(10);
        cache.put(ALICE, "balance", "", original);
        original.addProperty("amount", 99);
        JsonObject first = cache.get(ALICE, "balance", "");
        assertEquals(10, first.get("amount").getAsLong());
        first.addProperty("throttled", true);
        assertNull(cache.get(ALICE, "balance", "").get("throttled"));
    }

    @Test void isBoundedAndDropsTheLeastRecentlyUsedEntryFirst() {
        LinkSnapshotCache cache = new LinkSnapshotCache(2);
        cache.put(ALICE, "balance", "", snapshot(1));
        cache.put(ALICE, "market", "", snapshot(2));
        // Reading the balance makes the market entry the least recently used one.
        assertNotNull(cache.get(ALICE, "balance", ""));
        cache.put(ALICE, "transactions", "", snapshot(3));
        assertEquals(2, cache.size());
        assertNull(cache.get(ALICE, "market", ""));
        assertNotNull(cache.get(ALICE, "balance", ""));
        assertNotNull(cache.get(ALICE, "transactions", ""));
    }

    @Test void erasureDropsOnePlayerAndUnbindDropsEverything() {
        LinkSnapshotCache cache = new LinkSnapshotCache();
        cache.put(ALICE, "balance", "", snapshot(1));
        cache.put(ALICE, "market", "", snapshot(2));
        cache.put(BOB, "balance", "", snapshot(3));
        cache.forget(ALICE);
        assertEquals(1, cache.size());
        assertNull(cache.get(ALICE, "balance", ""));
        assertNotNull(cache.get(BOB, "balance", ""));
        cache.forget(null);
        cache.forget(UUID.randomUUID());
        assertEquals(1, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        // Nulls are ignored rather than stored.
        cache.put(null, "balance", "", snapshot(1));
        cache.put(ALICE, "balance", "", null);
        assertEquals(0, cache.size());
    }
}
