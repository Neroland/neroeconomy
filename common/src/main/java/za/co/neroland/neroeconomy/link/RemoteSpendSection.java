package za.co.neroland.neroeconomy.link;

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

/**
 * What each account has spent through the companion app in the current economy-day, against
 * {@code remoteDailySpendLimit}. It is what bounds a stolen app token: whatever an attacker sends,
 * the account loses at most one day's limit per economy-day.
 *
 * <p><b>The rule.</b> An action states the most it could spend before it runs ({@link #allows});
 * when it has run, what it actually spent is {@link #record recorded}. Everything an app action
 * spends counts: purchases, buy-order escrow, rule budgets, shares, bonds, payments and the
 * credits of an offer. The day rolls over on its own: a row from an earlier day counts as nothing.
 *
 * <p><b>Bounds.</b> One row per account, only for the current day: rows of earlier days are
 * dropped whenever a row is written, and at most {@link #MAX_ACCOUNTS} accounts are held. When it
 * is full, an account with no row yet is refused remote spending for the rest of the day; nothing
 * already held is ever pushed out, so the limit cannot be reset by flooding the table.
 *
 * <p><b>Privacy.</b> A row is the player's id, an economy-day and a sum: nothing about what was
 * bought or from whom. It is erased with the player ({@link #forgetPlayer}) and exported
 * ({@link #exportFor}). Every field is optional, so a store saved before this section existed
 * loads it empty.
 */
public final class RemoteSpendSection extends EconomySection {
    public static final Codec<RemoteSpendSection> CODEC = codec();
    /** The most accounts held for one day. */
    public static final int MAX_ACCOUNTS = 10_000;

    private record Spend(long day, long spent) { }

    private final Map<UUID, Spend> spends = new LinkedHashMap<>();

    public RemoteSpendSection() { }

    /** What {@code player} has spent through the app in economy-day {@code day}. */
    public synchronized long spentToday(UUID player, long day) {
        Spend spend = player == null ? null : spends.get(player);
        return spend == null || spend.day() != day ? 0L : spend.spent();
    }

    /**
     * Whether spending up to {@code amount} more today stays within {@code limit}. A negative
     * amount is never allowed; a limit of 0 allows nothing but 0.
     */
    public synchronized boolean allows(UUID player, long day, long amount, long limit) {
        if (player == null || amount < 0L || limit < 0L) return false;
        if (amount == 0L) return true;
        if (!spends.containsKey(player) && spends.size() >= MAX_ACCOUNTS && !purge(day)) return false;
        long spent = spentToday(player, day);
        return spent <= limit && amount <= limit - spent;
    }

    /** Counts {@code amount} spent by {@code player} today. Saturates rather than overflows. */
    public synchronized void record(UUID player, long day, long amount) {
        if (player == null || amount <= 0L) return;
        purge(day);
        long before = spentToday(player, day);
        if (!spends.containsKey(player) && spends.size() >= MAX_ACCOUNTS) return;
        long after = Long.MAX_VALUE - before < amount ? Long.MAX_VALUE : before + amount;
        spends.put(player, new Spend(day, after));
        setDirty();
    }

    /** Drops every row of an earlier day. @return whether anything was dropped */
    private boolean purge(long day) {
        boolean any = false;
        for (Iterator<Spend> rows = spends.values().iterator(); rows.hasNext();) {
            if (rows.next().day() != day) {
                rows.remove();
                any = true;
            }
        }
        if (any) setDirty();
        return any;
    }

    /**
     * Erasure entry point, registered in {@code NeroEconomyCommon.init()}: the player's row goes and
     * the purge is pushed into Core's recovery backup. Tolerates an unknown player.
     */
    public static void eraseFor(net.minecraft.server.MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        za.co.neroland.neroeconomy.economy.EconomyStore.get(server).remoteSpend().forgetPlayer(player);
        za.co.neroland.neroeconomy.economy.EconomyStore.afterErasure(server, player);
    }

    /** Erasure: the player's row goes. Unknown players are a no-op. */
    public synchronized void forgetPlayer(UUID player) {
        if (player != null && spends.remove(player) != null) setDirty();
    }

    /** The subject's own row for the data export: the day and the sum, nothing else. */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        Spend spend = player == null ? null : spends.get(player);
        if (spend != null) {
            out.addProperty("economy_day", spend.day());
            out.addProperty("spent", spend.spent());
        }
        return out;
    }

    /** How many accounts have a row; exists so the bound can be tested. */
    public synchronized int size() { return spends.size(); }

    @Override public synchronized boolean isEmpty() { return spends.isEmpty(); }

    // --- persistence -------------------------------------------------------------------------

    private record Row(String player, long day, long spent) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(Row::player),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Row::day),
                Codec.LONG.optionalFieldOf("spent", 0L).forGetter(Row::spent)).apply(instance, Row::new));
    }

    private static Codec<RemoteSpendSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Row.CODEC.listOf().optionalFieldOf("spends", List.of()).forGetter(RemoteSpendSection::rows))
                .apply(instance, RemoteSpendSection::fromRows));
    }

    private synchronized List<Row> rows() {
        List<Row> rows = new ArrayList<>(spends.size());
        spends.forEach((player, spend) -> rows.add(new Row(player.toString(), spend.day(), spend.spent())));
        return rows;
    }

    private static RemoteSpendSection fromRows(List<Row> rows) {
        RemoteSpendSection section = new RemoteSpendSection();
        // A malformed id or a negative sum (a hand-edited file) is dropped; the bound applies on load too.
        for (Row row : rows) {
            if (section.spends.size() >= MAX_ACCOUNTS) break;
            try {
                if (row.spent() > 0L) section.spends.put(UUID.fromString(row.player()), new Spend(row.day(), row.spent()));
            } catch (IllegalArgumentException malformed) {
                // skipped
            }
        }
        return section;
    }
}
