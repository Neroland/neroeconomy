package za.co.neroland.neroeconomy.trader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.economy.EconomySection;

/**
 * The Auto-Trader section of the economy store: who owns the Auto-Trader block at each position.
 * It is the only place an Auto-Trader's owner is recorded — the block itself stores no player id
 * — so erasing a player reaches every Auto-Trader they placed, loaded or not, in one pass, and
 * nothing about them is left behind in a chunk that happens not to be loaded.
 *
 * <p>A location is a {@code dimension|packedPosition} key, the same form Player Shops use. It
 * holds no item, no price and no rule: an Auto-Trader's rule lives in the rules section and
 * names the location as its binding.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_PER_OWNER} Auto-Traders per player and {@link #MAX_ROWS}
 * in all. <b>Purge.</b> A row goes when its block does (and a sweep catches a block that was
 * replaced without the game saying so). <b>Erasure.</b> {@link #forgetPlayer}: the blocks stay,
 * ownerless. <b>Export.</b> {@link #exportFor}.
 */
public final class AutoTraderSection extends EconomySection {
    public static final Codec<AutoTraderSection> CODEC = codec();
    /** The most Auto-Traders one player may own. */
    public static final int MAX_PER_OWNER = 64;
    /** The most Auto-Traders the section records in all. */
    public static final int MAX_ROWS = 65_536;

    private final Map<String, UUID> owners = new LinkedHashMap<>();

    public AutoTraderSection() { }

    @Override public synchronized boolean isEmpty() { return owners.isEmpty(); }

    /** Who owns the Auto-Trader at a location, or null: nobody, or no Auto-Trader recorded there. */
    public synchronized UUID owner(String location) { return location == null ? null : owners.get(location); }

    /** How many Auto-Traders a player owns. */
    public synchronized int count(UUID player) {
        int count = 0;
        for (UUID owner : owners.values()) if (owner.equals(player)) count++;
        return count;
    }

    public synchronized int size() { return owners.size(); }

    /**
     * Records who placed the Auto-Trader at a location. A row already there is replaced: it can
     * only belong to an earlier block whose removal was never reported.
     *
     * @return false, with nothing recorded, when the player or the section is at its bound; the
     *         block then has no owner
     */
    public synchronized boolean claim(String location, UUID player) {
        if (location == null || location.isBlank() || player == null) return false;
        UUID previous = owners.get(location);
        if (player.equals(previous)) return true;
        if (count(player) >= MAX_PER_OWNER || (previous == null && owners.size() >= MAX_ROWS)) return false;
        owners.put(location, player);
        setDirty();
        return true;
    }

    /** Drops the row of a location. @return true when one existed */
    public synchronized boolean remove(String location) {
        if (location == null || owners.remove(location) == null) return false;
        setDirty();
        return true;
    }

    /** Every recorded location, for the sweep that looks for blocks that are no longer there. */
    public synchronized List<String> locations() { return List.copyOf(owners.keySet()); }

    /** The locations of one player's Auto-Traders. */
    public synchronized List<String> locationsOf(UUID player) {
        List<String> out = new ArrayList<>();
        owners.forEach((location, owner) -> { if (owner.equals(player)) out.add(location); });
        return out;
    }

    /**
     * Erases one player: their Auto-Traders become ownerless. The blocks and whatever is in their
     * slots stay in the world; their rules are the rules section's to erase.
     *
     * @return the locations that lost their owner
     */
    public synchronized List<String> forgetPlayer(UUID player) {
        if (player == null) return List.of();
        List<String> orphaned = locationsOf(player);
        if (orphaned.isEmpty()) return orphaned;
        orphaned.forEach(owners::remove);
        setDirty();
        return orphaned;
    }

    /** Data-subject export: the dimension and block position of every Auto-Trader this player owns. */
    public synchronized JsonArray exportFor(UUID player) {
        JsonArray out = new JsonArray();
        for (String location : locationsOf(player)) out.add(describe(location));
        return out;
    }

    /** Splits a stored {@code dimension|packedPosition} key into readable coordinates. */
    static JsonObject describe(String location) {
        JsonObject row = new JsonObject();
        int separator = location.lastIndexOf('|');
        row.addProperty("dimension", separator < 0 ? location : location.substring(0, separator));
        if (separator < 0) return row;
        try {
            long packed = Long.parseLong(location.substring(separator + 1));
            // A block position packs x into the top 26 bits, z into the next 26 and y into the low 12.
            row.addProperty("x", (int) (packed >> 38));
            row.addProperty("y", (int) (packed << 52 >> 52));
            row.addProperty("z", (int) (packed << 26 >> 38));
        } catch (NumberFormatException malformed) {
            // A key this build did not write; the dimension alone is still the subject's data.
        }
        return row;
    }

    private record Row(String location, String owner) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("location").forGetter(Row::location),
                Codec.STRING.fieldOf("owner").forGetter(Row::owner)).apply(instance, Row::new));
    }

    private synchronized List<Row> rows() {
        List<Row> rows = new ArrayList<>(owners.size());
        owners.forEach((location, owner) -> rows.add(new Row(location, owner.toString())));
        return rows;
    }

    private static Codec<AutoTraderSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Row.CODEC.listOf().optionalFieldOf("traders", List.of()).forGetter(AutoTraderSection::rows))
                .apply(instance, AutoTraderSection::fromRows));
    }

    private static AutoTraderSection fromRows(List<Row> rows) {
        AutoTraderSection section = new AutoTraderSection();
        // A row that is not a location and an owner is skipped; every other row still loads.
        for (Row row : rows) {
            if (row.location().isBlank() || section.owners.size() >= MAX_ROWS) continue;
            try { section.owners.put(row.location(), UUID.fromString(row.owner())); }
            catch (IllegalArgumentException ignored) { }
        }
        return section;
    }
}
