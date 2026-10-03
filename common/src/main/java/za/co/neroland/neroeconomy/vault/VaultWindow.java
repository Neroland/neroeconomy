package za.co.neroland.neroeconomy.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The part of a vault a screen shows: a fixed number of slots over an ordered list that may be
 * longer. Pure Java, so the rules a chest-style screen depends on are tested without the game.
 *
 * <ul>
 *   <li><b>Opening</b> puts the first stacks that can be shown into the slots, in order, and keeps
 *       the rest — stacks past the last slot, and stacks the game cannot show right now — out of
 *       sight but untouched.</li>
 *   <li>While it is open the slots are positions, exactly as in a chest: a stack stays where it
 *       was put, and a slot may be empty between two full ones.</li>
 *   <li><b>Surfacing</b> moves hidden stacks into slots that have become empty, so an over-full
 *       vault can always be emptied through the same slots.</li>
 *   <li>{@link #contents} is the list to store: the slots in slot order with the gaps closed, then
 *       everything still hidden. No stack is ever dropped or copied by any of this.</li>
 * </ul>
 */
public final class VaultWindow {
    private final Lot[] slots;
    private final List<Lot> hidden;

    private VaultWindow(Lot[] slots, List<Lot> hidden) {
        this.slots = slots;
        this.hidden = hidden;
    }

    /**
     * @param stacks   the vault's stacks, in order
     * @param slots    how many slots the window has
     * @param showable whether a stack can be shown at all; one that cannot stays hidden
     */
    public static VaultWindow open(List<Lot> stacks, int slots, Predicate<Lot> showable) {
        Lot[] shown = new Lot[Math.max(0, slots)];
        List<Lot> hidden = new ArrayList<>();
        int next = 0;
        for (Lot lot : stacks) {
            if (lot == null) continue;
            if (next < shown.length && showable.test(lot)) shown[next++] = lot;
            else hidden.add(lot);
        }
        return new VaultWindow(shown, hidden);
    }

    public int size() { return slots.length; }

    /** The stack in a slot, or null when it is empty or there is no such slot. */
    public Lot slot(int index) { return index < 0 || index >= slots.length ? null : slots[index]; }

    /** Records what a slot holds now; null empties it. */
    public void set(int index, Lot lot) {
        if (index >= 0 && index < slots.length) slots[index] = lot;
    }

    /** Stacks that are not in any slot. */
    public int hiddenCount() { return hidden.size(); }

    /** Slots that hold a stack. */
    public int shownCount() {
        int count = 0;
        for (Lot lot : slots) if (lot != null) count++;
        return count;
    }

    /** Every stack of the vault: shown plus hidden. */
    public int stackCount() { return shownCount() + hidden.size(); }

    /**
     * Moves hidden stacks that can be shown into empty slots, lowest slot first, oldest stack first.
     *
     * @return the slots that were filled
     */
    public List<Integer> surface(Predicate<Lot> showable) {
        List<Integer> filled = new ArrayList<>();
        int slot = 0;
        for (int index = 0; index < hidden.size();) {
            while (slot < slots.length && slots[slot] != null) slot++;
            if (slot >= slots.length) break;
            Lot candidate = hidden.get(index);
            if (!showable.test(candidate)) { index++; continue; }
            slots[slot] = candidate;
            hidden.remove(index);
            filled.add(slot);
        }
        return filled;
    }

    /** The list to store: the slots in order with the gaps closed, then everything still hidden. */
    public List<Lot> contents() {
        List<Lot> out = new ArrayList<>(slots.length + hidden.size());
        for (Lot lot : slots) if (lot != null) out.add(lot);
        out.addAll(hidden);
        return out;
    }
}
