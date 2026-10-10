package za.co.neroland.neroeconomy.sibling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * Each player's economy milestones, which open Core progression gates of the same name
 * ({@code neroeconomy:first_sale} and so on) so quest packs can react without code.
 *
 * <p>Per player: which milestones were reached ({@code earned}), which of those still wait for
 * their gate to be opened because the player was offline ({@code pending}), and the count of
 * settled sales toward {@code trader_100} (it stops counting at 100). Personal: erased with the
 * player ({@link #forgetPlayer}), exported ({@link #exportFor}), at most {@link #MAX_PLAYERS}
 * rows; when full, a new player's milestone is not recorded (the gate can still be opened by an
 * operator). Every field is optional, so a store saved before this section existed loads it empty.
 */
public final class MilestoneSection extends EconomySection {
    public static final Codec<MilestoneSection> CODEC = codec();
    public static final int MAX_PLAYERS = 100_000;
    /** Settled sales that reach {@link Milestone#TRADER_100}. */
    public static final int TRADER_SALES = 100;

    /** The milestones; the ordinal is the saved bit, so constants are only ever appended. */
    public enum Milestone {
        FIRST_SALE, FIRST_PURCHASE, FIRST_INVESTMENT, FIRST_BOND, TRADER_100, FIRST_OFFER_ACCEPTED;

        /** The gate path and data-file name, such as {@code first_sale}. */
        public String id() { return name().toLowerCase(Locale.ROOT); }

        /** The lang key of the gate's title, for a quest pack or screen that shows it. */
        public String translationKey() { return "gate.neroeconomy." + id(); }

        long bit() { return 1L << ordinal(); }
    }

    private record Row(long earned, long pending, long sales) { }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    public MilestoneSection() { }

    /** Records {@code milestone} for {@code player}. @return true when it is new (its gate now waits to open) */
    public synchronized boolean earn(UUID player, Milestone milestone) {
        if (player == null || milestone == null) return false;
        Row row = rows.get(player);
        if (row == null && rows.size() >= MAX_PLAYERS) return false;
        if (row == null) row = new Row(0L, 0L, 0L);
        if ((row.earned() & milestone.bit()) != 0L) return false;
        rows.put(player, new Row(row.earned() | milestone.bit(), row.pending() | milestone.bit(), row.sales()));
        setDirty();
        return true;
    }

    /** One settled sale: {@code first_sale}, and {@code trader_100} at the hundredth. @return whether anything new was earned */
    public synchronized boolean countSale(UUID player) {
        if (player == null) return false;
        boolean any = earn(player, Milestone.FIRST_SALE);
        Row row = rows.get(player);
        if (row == null || row.sales() >= TRADER_SALES) return any;
        long sales = row.sales() + 1L;
        rows.put(player, new Row(row.earned(), row.pending(), sales));
        setDirty();
        if (sales >= TRADER_SALES) any |= earn(player, Milestone.TRADER_100);
        return any;
    }

    public synchronized boolean earned(UUID player, Milestone milestone) {
        Row row = player == null ? null : rows.get(player);
        return row != null && (row.earned() & milestone.bit()) != 0L;
    }

    public synchronized long sales(UUID player) {
        Row row = player == null ? null : rows.get(player);
        return row == null ? 0L : row.sales();
    }

    /** The milestones of {@code player} whose gate still waits to be opened. */
    public synchronized List<Milestone> pending(UUID player) {
        Row row = player == null ? null : rows.get(player);
        List<Milestone> out = new ArrayList<>();
        if (row != null) for (Milestone milestone : Milestone.values())
            if ((row.pending() & milestone.bit()) != 0L) out.add(milestone);
        return out;
    }

    /** Whether any player has a gate waiting to be opened. */
    public synchronized boolean hasPending() {
        for (Row row : rows.values()) if (row.pending() != 0L) return true;
        return false;
    }

    /** The gate of {@code milestone} was opened for {@code player}. */
    public synchronized void opened(UUID player, Milestone milestone) {
        if (player == null || milestone == null) return;
        Row row = rows.get(player);
        if (row == null || (row.pending() & milestone.bit()) == 0L) return;
        rows.put(player, new Row(row.earned(), row.pending() & ~milestone.bit(), row.sales()));
        setDirty();
    }

    public synchronized int size() { return rows.size(); }

    // --- privacy -----------------------------------------------------------------------------

    /** Erasure entry point, registered first in {@code NeroEconomyCommon.init()}. Tolerates an unknown player. */
    public static void eraseFor(net.minecraft.server.MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore.get(server).milestones().forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    public synchronized void forgetPlayer(UUID player) {
        if (player != null && rows.remove(player) != null) setDirty();
    }

    /** The subject's own row for the data export: milestones reached, gates waiting, the sales count. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        Row row = player == null ? null : rows.get(player);
        if (row == null) return out;
        JsonArray earned = new JsonArray();
        JsonArray pending = new JsonArray();
        for (Milestone milestone : Milestone.values()) {
            if ((row.earned() & milestone.bit()) != 0L) earned.add(milestone.id());
            if ((row.pending() & milestone.bit()) != 0L) pending.add(milestone.id());
        }
        out.add("reached", earned);
        out.add("gates_waiting", pending);
        out.addProperty("settled_sales_counted", row.sales());
        return out;
    }

    @Override public synchronized boolean isEmpty() { return rows.isEmpty(); }

    // --- persistence -------------------------------------------------------------------------

    private record Saved(String player, long earned, long pending, long sales) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(Saved::player),
                Codec.LONG.optionalFieldOf("earned", 0L).forGetter(Saved::earned),
                Codec.LONG.optionalFieldOf("pending", 0L).forGetter(Saved::pending),
                Codec.LONG.optionalFieldOf("sales", 0L).forGetter(Saved::sales)).apply(instance, Saved::new));
    }

    private static Codec<MilestoneSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Saved.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(MilestoneSection::saved))
                .apply(instance, MilestoneSection::fromSaved));
    }

    private synchronized List<Saved> saved() {
        List<Saved> out = new ArrayList<>(rows.size());
        rows.forEach((player, row) -> out.add(new Saved(player.toString(), row.earned(), row.pending(), row.sales())));
        return out;
    }

    private static MilestoneSection fromSaved(List<Saved> saved) {
        MilestoneSection section = new MilestoneSection();
        long known = (1L << Milestone.values().length) - 1L;
        for (Saved row : saved) {
            if (section.rows.size() >= MAX_PLAYERS) break;
            try {
                long earned = row.earned() & known;
                if (earned != 0L || row.sales() > 0L) section.rows.put(UUID.fromString(row.player()),
                        new Row(earned, row.pending() & earned, Math.max(0L, Math.min(TRADER_SALES, row.sales()))));
            } catch (IllegalArgumentException malformed) {
                // skipped
            }
        }
        return section;
    }
}
