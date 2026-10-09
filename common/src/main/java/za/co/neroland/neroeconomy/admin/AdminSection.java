package za.co.neroland.neroeconomy.admin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.economy.EconomySection;

/**
 * The operators' section of the economy store: the market freeze switch, and how many times each
 * kind of operator action has been carried out. Nothing here is about a player — a count says how
 * often operators granted credits or erased an account, never whose — so there is nothing to
 * erase and nothing to export.
 *
 * <p><b>Market freeze.</b> An operator can close the market for a while: player trading, the
 * Merchant Houses' orders and the exchange refuse with a plain message, but everything can still
 * be read, waiting credits and goods collected, and orders and offers taken back. It is separate
 * from the corruption freeze ({@code EconomyHealth}), which stops every value-moving operation and
 * is never saved. Every field is optional with a safe default: a store saved before this section
 * existed loads with the market open and every count at zero.
 */
public final class AdminSection extends EconomySection {
    public static final Codec<AdminSection> CODEC = codec();

    /** The operator actions that are counted. Stored by name; constants are only ever appended. */
    public enum Action {
        GRANT, TAKE, SET, SIM_STEP, MARKET_CLOSE, MARKET_OPEN, FRESH_START, EXPORT, ERASE, GALLERY_BUILD, GALLERY_CLEAR;

        /** The lang key of the action's name on the admin page. */
        public String key() { return "gui.neroeconomy.hub.admin.action." + name().toLowerCase(Locale.ROOT); }

        static Action parse(String name) {
            for (Action action : values()) if (action.name().equals(name)) return action;
            return null;
        }
    }

    private boolean marketFrozen;
    private final Map<Action, Long> counts = new EnumMap<>(Action.class);

    public AdminSection() { }

    /** Whether an operator has closed the market. */
    public synchronized boolean marketFrozen() { return marketFrozen; }

    /** Closes ({@code true}) or opens the market. @return whether anything changed */
    public synchronized boolean setMarketFrozen(boolean frozen) {
        if (marketFrozen == frozen) return false;
        marketFrozen = frozen;
        setDirty();
        return true;
    }

    /** Counts one operator action. */
    public synchronized void record(Action action) {
        if (action == null) return;
        counts.merge(action, 1L, (a, b) -> a == Long.MAX_VALUE ? a : a + b);
        setDirty();
    }

    /** How many times {@code action} has been carried out. */
    public synchronized long count(Action action) {
        Long value = action == null ? null : counts.get(action);
        return value == null ? 0L : value;
    }

    /** Every count, in action order, zeros included. */
    public synchronized Map<Action, Long> counts() {
        Map<Action, Long> out = new EnumMap<>(Action.class);
        for (Action action : Action.values()) out.put(action, counts.getOrDefault(action, 0L));
        return out;
    }

    @Override public synchronized boolean isEmpty() { return !marketFrozen && counts.isEmpty(); }

    // --- persistence -------------------------------------------------------------------------

    private record CountRow(String action, long count) {
        static final Codec<CountRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("action").forGetter(CountRow::action),
                Codec.LONG.fieldOf("count").forGetter(CountRow::count)).apply(instance, CountRow::new));
    }

    private static Codec<AdminSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("market_frozen", false).forGetter(section -> section.marketFrozen),
                CountRow.CODEC.listOf().optionalFieldOf("counts", List.of()).forGetter(AdminSection::countRows))
                .apply(instance, AdminSection::fromData));
    }

    private synchronized List<CountRow> countRows() {
        List<CountRow> rows = new ArrayList<>(counts.size());
        counts.forEach((action, count) -> rows.add(new CountRow(action.name(), count)));
        return rows;
    }

    private static AdminSection fromData(boolean frozen, List<CountRow> rows) {
        AdminSection section = new AdminSection();
        section.marketFrozen = frozen;
        // An unknown name (from a newer build) is dropped; a negative count is read as none.
        for (CountRow row : rows) {
            Action action = Action.parse(row.action());
            if (action != null && row.count() > 0L) section.counts.put(action, row.count());
        }
        return section;
    }
}
