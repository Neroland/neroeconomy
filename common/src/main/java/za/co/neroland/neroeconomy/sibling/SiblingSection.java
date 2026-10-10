package za.co.neroland.neroeconomy.sibling;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * What other mods have paid out through the cross-mod API ({@code api.EconomyCaller}) in the
 * current economy day, and which of their requests were already applied.
 *
 * <ul>
 *   <li><b>Per caller:</b> one count per mod id, against its daily limit. Not personal.</li>
 *   <li><b>Per player:</b> one count per player, against {@code siblingPerPlayerDailyCap}, kept
 *       only while that cap is on. Personal (a player id and a number): today only, erased with
 *       the player ({@link #forgetPlayer}) and exported ({@link #exportFor}).</li>
 *   <li><b>Request keys:</b> {@code <modid>/<key>} with when it lapses, 24 economy-hours after it
 *       was applied. A key never holds a player id ({@link SiblingRules#validRequestKey}).</li>
 * </ul>
 *
 * <p>Bounds: {@link #MAX_CALLERS} callers, {@link #MAX_PLAYERS} players, {@link #MAX_REQUEST_KEYS}
 * keys. Both counts are cleared at the day roll and lapsed keys are purged whenever the table is
 * full. Every field is optional, so a store saved before this section existed loads it empty.
 */
public final class SiblingSection extends EconomySection {
    public static final Codec<SiblingSection> CODEC = codec();
    public static final int MAX_CALLERS = 256;
    public static final int MAX_PLAYERS = 100_000;
    public static final int MAX_REQUEST_KEYS = 50_000;
    /** How long an applied request key is remembered: 24 economy-hours. */
    public static final long KEY_LIFETIME_MILLIS = 24L * 60L * 60L * 1000L;

    private long day;
    private final Map<String, Long> callerUsed = new LinkedHashMap<>();
    private final Map<UUID, Long> playerUsed = new LinkedHashMap<>();
    private final Map<String, Long> requestKeys = new LinkedHashMap<>();

    public SiblingSection() { }

    /** What {@code modId} has paid out in economy day {@code today}. A day before the one kept answers with that day's count. */
    public synchronized long callerUsed(String modId, long today) {
        return modId == null || today > day ? 0L : callerUsed.getOrDefault(modId, 0L);
    }

    /** What other mods together have paid {@code player} in economy day {@code today}. */
    public synchronized long playerUsed(UUID player, long today) {
        return player == null || today > day ? 0L : playerUsed.getOrDefault(player, 0L);
    }

    /** Whether a new caller or player row could be held today. */
    public synchronized boolean hasRoom(String modId, UUID player, long today) {
        boolean newDay = today > day;
        boolean callerRoom = newDay || callerUsed.containsKey(modId) || callerUsed.size() < MAX_CALLERS;
        boolean playerRoom = player == null || newDay || playerUsed.containsKey(player) || playerUsed.size() < MAX_PLAYERS;
        return callerRoom && playerRoom;
    }

    /**
     * Counts a payout that was made. {@code player} is null when the per-player cap is off, so no
     * per-player row is kept then. Saturates rather than overflows.
     */
    public synchronized void count(String modId, UUID player, long amount, long today) {
        if (modId == null || amount <= 0L) return;
        roll(today);
        if (callerUsed.containsKey(modId) || callerUsed.size() < MAX_CALLERS)
            callerUsed.merge(modId, amount, SiblingSection::saturatingAdd);
        if (player != null && (playerUsed.containsKey(player) || playerUsed.size() < MAX_PLAYERS))
            playerUsed.merge(player, amount, SiblingSection::saturatingAdd);
        setDirty();
    }

    private void roll(long today) {
        if (today <= day) return;
        day = today;
        callerUsed.clear();
        playerUsed.clear();
    }

    // --- request keys ------------------------------------------------------------------------

    private static String keyOf(String modId, String key) { return modId + '/' + key; }

    /** Whether {@code key} from {@code modId} was applied and has not lapsed at {@code now}. */
    public synchronized boolean seen(String modId, String key, long now) {
        if (modId == null || key == null) return false;
        Long expires = requestKeys.get(keyOf(modId, key));
        return expires != null && expires > now;
    }

    /** Whether one more key can be remembered; purges lapsed keys when the table is full. */
    public synchronized boolean hasKeyRoom(long now) {
        if (requestKeys.size() < MAX_REQUEST_KEYS) return true;
        purgeKeys(now);
        return requestKeys.size() < MAX_REQUEST_KEYS;
    }

    /** Remembers an applied request for {@link #KEY_LIFETIME_MILLIS}. */
    public synchronized void remember(String modId, String key, long now) {
        if (modId == null || key == null) return;
        String full = keyOf(modId, key);
        if (!requestKeys.containsKey(full) && !hasKeyRoom(now)) return;
        long expires = Long.MAX_VALUE - now < KEY_LIFETIME_MILLIS ? Long.MAX_VALUE : now + KEY_LIFETIME_MILLIS;
        requestKeys.put(full, expires);
        setDirty();
    }

    /** Drops every lapsed key. @return how many were dropped */
    public synchronized int purgeKeys(long now) {
        int dropped = 0;
        for (Iterator<Long> keys = requestKeys.values().iterator(); keys.hasNext();) {
            if (keys.next() <= now) {
                keys.remove();
                dropped++;
            }
        }
        if (dropped > 0) setDirty();
        return dropped;
    }

    public synchronized int keyCount() { return requestKeys.size(); }
    public synchronized int playerCount() { return playerUsed.size(); }

    // --- privacy -----------------------------------------------------------------------------

    /**
     * Erasure entry point, registered first in {@code NeroEconomyCommon.init()}: the player's count
     * goes and the purge is pushed into Core's recovery backup. Tolerates an unknown player.
     */
    public static void eraseFor(net.minecraft.server.MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore.get(server).sibling().forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    /** Erasure: the player's count goes. Unknown players are a no-op. */
    public synchronized void forgetPlayer(UUID player) {
        if (player != null && playerUsed.remove(player) != null) setDirty();
    }

    /** The subject's own row for the data export: the day and what other mods paid them in it. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        Long used = player == null ? null : playerUsed.get(player);
        if (used != null) {
            out.addProperty("economy_day", day);
            out.addProperty("credits_from_other_mods_today", used);
        }
        return out;
    }

    @Override public synchronized boolean isEmpty() {
        return callerUsed.isEmpty() && playerUsed.isEmpty() && requestKeys.isEmpty();
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    // --- persistence -------------------------------------------------------------------------

    private record Count(String id, long used) {
        static final Codec<Count> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Count::id),
                Codec.LONG.optionalFieldOf("used", 0L).forGetter(Count::used)).apply(instance, Count::new));
    }

    private record Key(String key, long expires) {
        static final Codec<Key> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("key").forGetter(Key::key),
                Codec.LONG.optionalFieldOf("expires", 0L).forGetter(Key::expires)).apply(instance, Key::new));
    }

    private static Codec<SiblingSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(section -> section.day),
                Count.CODEC.listOf().optionalFieldOf("callers", List.of()).forGetter(SiblingSection::callerRows),
                Count.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(SiblingSection::playerRows),
                Key.CODEC.listOf().optionalFieldOf("request_keys", List.of()).forGetter(SiblingSection::keyRows))
                .apply(instance, SiblingSection::fromRows));
    }

    private synchronized List<Count> callerRows() {
        List<Count> rows = new ArrayList<>();
        callerUsed.forEach((id, used) -> rows.add(new Count(id, used)));
        return rows;
    }

    private synchronized List<Count> playerRows() {
        List<Count> rows = new ArrayList<>();
        playerUsed.forEach((id, used) -> rows.add(new Count(id.toString(), used)));
        return rows;
    }

    private synchronized List<Key> keyRows() {
        List<Key> rows = new ArrayList<>();
        requestKeys.forEach((key, expires) -> rows.add(new Key(key, expires)));
        return rows;
    }

    private static SiblingSection fromRows(long day, List<Count> callers, List<Count> players, List<Key> keys) {
        SiblingSection section = new SiblingSection();
        section.day = day;
        // Malformed rows (a hand-edited file) are dropped; the bounds apply on load too.
        for (Count row : callers)
            if (section.callerUsed.size() < MAX_CALLERS && SiblingRules.validModId(row.id()) && row.used() > 0L)
                section.callerUsed.put(row.id(), row.used());
        for (Count row : players) {
            if (section.playerUsed.size() >= MAX_PLAYERS) break;
            try {
                if (row.used() > 0L) section.playerUsed.put(UUID.fromString(row.id()), row.used());
            } catch (IllegalArgumentException malformed) {
                // skipped
            }
        }
        for (Key row : keys) {
            if (section.requestKeys.size() >= MAX_REQUEST_KEYS) break;
            int slash = row.key().indexOf('/');
            if (slash > 0 && SiblingRules.validModId(row.key().substring(0, slash))
                    && SiblingRules.validRequestKey(row.key().substring(slash + 1)))
                section.requestKeys.put(row.key(), row.expires());
        }
        return section;
    }
}
