package za.co.neroland.neroeconomy.trader;

import java.util.HashSet;
import java.util.Set;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The eighteen slots of an Auto-Trader as a plain array: what the block entity is to the game.
 * A slot can also hold a stack a rule cannot deal in (a renamed or enchanted one), which only
 * takes up room.
 */
public final class FakeSlots implements TraderInventory.Slots {
    public final Lot[] held = new Lot[TraderInventory.SLOTS];
    /** Slots that hold a modified stack: not empty, and not something a rule can count or take. */
    public final Set<Integer> modified = new HashSet<>();
    /** Whether the block's chunk is loaded and ticking. */
    public boolean loaded = true;
    public int limit = 64;
    /** How many times a slot was written. */
    public int writes;

    @Override public boolean reachable() { return loaded; }
    @Override public boolean empty(int slot) { return held[slot] == null && !modified.contains(slot); }
    @Override public Lot unmodified(int slot) { return modified.contains(slot) ? null : held[slot]; }

    @Override public void set(int slot, Lot lot) {
        if (!loaded) throw new IllegalStateException("the slots of an unloaded block were written");
        held[slot] = lot;
        modified.remove(slot);
        writes++;
    }

    @Override public int limit(String item) { return limit; }

    /** Puts goods into an input slot, as a hopper or a player does. */
    public FakeSlots input(int slot, String item, int count) {
        held[slot] = Lot.of(item, count);
        return this;
    }

    /** How many of an item the slots between two indices hold. */
    public long count(String item, int from, int to) {
        long total = 0L;
        for (int slot = from; slot < to; slot++)
            if (held[slot] != null && !modified.contains(slot) && held[slot].item().equals(item)) total += held[slot].count();
        return total;
    }

    public long inputs(String item) { return count(item, 0, TraderInventory.INPUTS); }
    public long outputs(String item) { return count(item, TraderInventory.INPUTS, TraderInventory.SLOTS); }
}
