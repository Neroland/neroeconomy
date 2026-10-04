package za.co.neroland.neroeconomy.trader;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.rules.RuleInventory;

/**
 * An Auto-Trader block as the inventory its rule works from. A sell rule lists what is in the
 * nine input slots; a buy rule delivers what it buys into the nine output slots. The slots
 * themselves are reached through {@link Slots}, so the rules here run against the block in the
 * game and against a plain array in a unit test.
 *
 * <ul>
 *   <li><b>Reachable</b> only while the block's chunk is loaded and ticking. An Auto-Trader never
 *       loads a chunk: while it cannot be reached it holds nothing, has no room, and gives
 *       nothing — the rule pauses and its order leaves the book.</li>
 *   <li><b>Selling</b> takes unmodified goods out of the input slots, last slot first. A stack
 *       that is not unmodified (renamed, enchanted, damaged) is never counted and never taken:
 *       a rule only deals in unmodified goods.</li>
 *   <li><b>Buying</b> only ever asks for what the output slots have {@link #room} for. When they
 *       are full the rule reports that and buys nothing more.</li>
 *   <li><b>Goods coming back</b> — an order that was withdrawn, a purchase — go into the slots as
 *       far as they fit (a sell rule's into the input slots first, so it lists them again) and
 *       whatever does not fit is handed to {@code overflow}, which books it into the owner's
 *       vault. Nothing is ever dropped and nothing is ever lost.</li>
 * </ul>
 */
public final class TraderInventory implements RuleInventory {
    public static final int INPUTS = 9;
    public static final int OUTPUTS = 9;
    public static final int SLOTS = INPUTS + OUTPUTS;

    /** The eighteen slots of one Auto-Trader: 0–8 are inputs, 9–17 are outputs. */
    public interface Slots {
        /** Whether the block is there and its chunk is loaded and ticking. */
        boolean reachable();

        /** Whether a slot holds nothing at all. */
        boolean empty(int slot);

        /**
         * The unmodified goods in a slot, or null: the slot is empty, or it holds a stack a rule
         * cannot deal in.
         */
        Lot unmodified(int slot);

        /** Replaces what a slot holds with unmodified goods; null empties it. */
        void set(int slot, Lot lot);

        /**
         * How many of an item fit in one slot; 0 when a slot cannot hold it at all (an item id the
         * game does not know). Such goods are never put in a slot: they go to the overflow.
         */
        int limit(String item);
    }

    private final Slots slots;
    private final boolean sells;
    private final Consumer<Lot> overflow;

    /**
     * @param sells    whether the rule bound to the block sells: goods coming back then go to the
     *                 input slots first
     * @param overflow where goods go that do not fit, or that arrive while the block cannot be
     *                 reached; must always take them (the owner's vault)
     */
    public TraderInventory(Slots slots, boolean sells, Consumer<Lot> overflow) {
        if (slots == null || overflow == null) throw new IllegalArgumentException("trader inventory");
        this.slots = slots;
        this.sells = sells;
        this.overflow = overflow;
    }

    @Override public boolean reachable() { return slots.reachable(); }

    @Override public long available(String item) {
        if (item == null || !reachable()) return 0L;
        long total = 0L;
        for (int slot = 0; slot < INPUTS; slot++) {
            Lot held = slots.unmodified(slot);
            if (held != null && held.item().equals(item)) total += held.count();
        }
        return total;
    }

    @Override public Map<String, Long> goods() {
        Map<String, Long> out = new TreeMap<>();
        if (!reachable()) return out;
        for (int slot = 0; slot < INPUTS; slot++) {
            Lot held = slots.unmodified(slot);
            if (held != null) out.merge(held.item(), (long) held.count(), Long::sum);
        }
        return out;
    }

    @Override public boolean take(Lot lot) {
        if (lot == null || !lot.pristine() || available(lot.item()) < lot.count()) return false;
        int left = lot.count();
        for (int slot = INPUTS - 1; slot >= 0 && left > 0; slot--) {
            Lot held = slots.unmodified(slot);
            if (held == null || !held.item().equals(lot.item())) continue;
            int taken = Math.min(left, held.count());
            slots.set(slot, taken == held.count() ? null : held.withCount(held.count() - taken));
            left -= taken;
        }
        return true;
    }

    @Override public void put(Lot lot) {
        if (lot == null) return;
        // Goods a rule cannot list again, and goods for a block that cannot be reached, go to the vault.
        if (!lot.pristine() || !reachable()) {
            overflow.accept(lot);
            return;
        }
        int left = lot.count();
        if (sells) left = fill(lot.item(), left, 0, INPUTS);
        left = fill(lot.item(), left, INPUTS, SLOTS);
        if (left > 0) overflow.accept(lot.withCount(left));
    }

    /** Tops up stacks of the item, then fills empty slots, between two slot indices. @return what did not fit */
    private int fill(String item, int count, int from, int to) {
        int limit = slots.limit(item);
        if (limit < 1) return count;
        int left = count;
        for (int slot = from; slot < to && left > 0; slot++) {
            Lot held = slots.unmodified(slot);
            if (held == null || !held.item().equals(item) || held.count() >= limit) continue;
            int added = Math.min(left, limit - held.count());
            slots.set(slot, held.withCount(held.count() + added));
            left -= added;
        }
        for (int slot = from; slot < to && left > 0; slot++) {
            if (!slots.empty(slot)) continue;
            int added = Math.min(left, limit);
            slots.set(slot, Lot.of(item, added));
            left -= added;
        }
        return left;
    }

    /** What the output slots can still take of an item: the space in its stacks plus the empty slots. */
    @Override public long room(String item) {
        if (item == null || !reachable()) return 0L;
        int limit = slots.limit(item);
        if (limit < 1) return 0L;
        long room = 0L;
        for (int slot = INPUTS; slot < SLOTS; slot++) {
            if (slots.empty(slot)) room += limit;
            else {
                Lot held = slots.unmodified(slot);
                if (held != null && held.item().equals(item) && held.count() < limit) room += limit - held.count();
            }
        }
        return room;
    }
}
