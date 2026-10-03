package za.co.neroland.neroeconomy.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.Lots;

/** What a chest-style screen over a vault shows, and that it never drops or copies a stack. */
class VaultWindowTest {
    private static final Predicate<Lot> ALL = lot -> true;
    /** Stands for an item from a mod that is not installed: it cannot be shown. */
    private static final String GONE = "removedmod:widget";
    private static final Predicate<Lot> KNOWN = lot -> !lot.item().equals(GONE);

    private static List<Lot> numbered(int count) {
        List<Lot> stacks = new ArrayList<>();
        for (int index = 0; index < count; index++) stacks.add(Lot.of("test:item_" + index, 1 + index));
        return stacks;
    }

    @Test void aVaultThatFitsIsShownInOrderWithNothingHidden() {
        List<Lot> stacks = numbered(5);
        VaultWindow window = VaultWindow.open(stacks, 9, ALL);
        assertEquals(9, window.size());
        for (int slot = 0; slot < 5; slot++) assertEquals(stacks.get(slot), window.slot(slot));
        for (int slot = 5; slot < 9; slot++) assertNull(window.slot(slot));
        assertNull(window.slot(-1));
        assertNull(window.slot(9));
        assertEquals(5, window.shownCount());
        assertEquals(0, window.hiddenCount());
        assertEquals(stacks, window.contents());
    }

    @Test void stacksPastTheLastSlotAreHiddenAndMoveIntoViewAsSlotsEmpty() {
        List<Lot> stacks = numbered(12);
        VaultWindow window = VaultWindow.open(stacks, 9, ALL);
        assertEquals(9, window.shownCount());
        assertEquals(3, window.hiddenCount());
        assertEquals(12, window.stackCount());
        // Nothing to do while every slot is full.
        assertEquals(List.of(), window.surface(ALL));
        // The player takes the stacks out of slots 2 and 7.
        window.set(2, null);
        window.set(7, null);
        assertEquals(List.of(2, 7), window.surface(ALL));
        // The oldest hidden stack went to the lowest free slot.
        assertEquals(stacks.get(9), window.slot(2));
        assertEquals(stacks.get(10), window.slot(7));
        assertEquals(1, window.hiddenCount());
        assertEquals(10, window.stackCount());
        // What is stored: the slots in order, then what is still hidden.
        List<Lot> stored = window.contents();
        assertEquals(10, stored.size());
        assertEquals(stacks.get(9), stored.get(2));
        assertEquals(stacks.get(11), stored.get(9));
        // Opened again, the last hidden stack still does not fit; one more withdrawal brings it in.
        VaultWindow again = VaultWindow.open(stored, 9, ALL);
        assertEquals(1, again.hiddenCount());
        again.set(0, null);
        assertEquals(List.of(0), again.surface(ALL));
        assertEquals(0, again.hiddenCount());
        assertEquals(stacks.get(11), again.slot(0));
    }

    @Test void slotsKeepTheirPlacesWhileOpenAndGapsCloseWhenStored() {
        VaultWindow window = VaultWindow.open(numbered(3), 9, ALL);
        Lot moved = window.slot(0);
        // The player moves the first stack to the last slot and leaves a gap behind.
        window.set(0, null);
        window.set(8, moved);
        assertNull(window.slot(0));
        assertEquals(moved, window.slot(8));
        assertEquals(List.of(), window.surface(ALL));
        assertEquals(List.of(Lot.of("test:item_1", 2), Lot.of("test:item_2", 3), moved), window.contents());
    }

    @Test void aStackTheGameCannotShowStaysHiddenAndIsNeverLost() {
        List<Lot> stacks = List.of(Lot.of("test:a", 1), Lot.of(GONE, 7), Lot.of("test:b", 2), new Lot(GONE, 1, 4));
        VaultWindow window = VaultWindow.open(stacks, 9, KNOWN);
        assertEquals(Lot.of("test:a", 1), window.slot(0));
        assertEquals(Lot.of("test:b", 2), window.slot(1));
        assertEquals(2, window.shownCount());
        assertEquals(2, window.hiddenCount());
        // Free slots do not pull it into view either.
        assertEquals(List.of(), window.surface(KNOWN));
        window.set(0, null);
        assertEquals(List.of(), window.surface(KNOWN));
        // It is still part of what is stored, in its original order after the shown stacks.
        assertEquals(List.of(Lot.of("test:b", 2), Lot.of(GONE, 7), new Lot(GONE, 1, 4)), window.contents());
    }

    @Test void aWindowWithNoSlotsHidesEverythingAndStoresItUnchanged() {
        List<Lot> stacks = numbered(4);
        VaultWindow window = VaultWindow.open(stacks, 0, ALL);
        assertEquals(0, window.size());
        assertEquals(4, window.hiddenCount());
        window.set(0, Lot.of("test:ignored", 1));
        assertEquals(stacks, window.contents());
        assertEquals(stacks, VaultWindow.open(stacks, -3, ALL).contents());
    }

    @Test void emptyingAnOverFullVaultThroughTheWindowTakesOutEveryItemExactlyOnce() {
        Random random = new Random(11L);
        List<Lot> stacks = new ArrayList<>();
        for (int index = 0; index < 200; index++)
            stacks.add(new Lot("test:item_" + random.nextInt(6), 1 + random.nextInt(64), random.nextInt(2)));
        long before = Lots.count(stacks);
        List<Lot> stored = stacks;
        long taken = 0L;
        int rounds = 0;
        while (!stored.isEmpty()) {
            VaultWindow window = VaultWindow.open(stored, 27, ALL);
            // Take a few random stacks, let the hidden ones surface, store, and open again.
            for (int picks = 1 + random.nextInt(9); picks > 0; picks--) {
                int slot = random.nextInt(27);
                Lot lot = window.slot(slot);
                if (lot == null) continue;
                taken += lot.count();
                window.set(slot, null);
                window.surface(ALL);
            }
            stored = window.contents();
            assertEquals(before, taken + Lots.count(stored));
            assertTrue(++rounds < 10_000);
        }
        assertEquals(before, taken);
    }
}
