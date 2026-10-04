package za.co.neroland.neroeconomy.trader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The Auto-Trader as a rule's inventory, over a plain array of slots: a sell rule takes from the
 * inputs, a buy rule fills the outputs, an unloaded block gives and takes nothing, and whatever
 * does not fit is handed on rather than lost.
 */
class TraderInventoryTest {
    private static final String DIAMOND = "minecraft:diamond";
    private static final String IRON = "minecraft:iron_ingot";
    private final FakeSlots slots = new FakeSlots();
    /** What was handed to the owner's vault. */
    private final List<Lot> vault = new ArrayList<>();

    private TraderInventory selling() { return new TraderInventory(slots, true, vault::add); }
    private TraderInventory buying() { return new TraderInventory(slots, false, vault::add); }

    private long vaulted(String item) {
        return vault.stream().filter(lot -> lot.item().equals(item)).mapToLong(Lot::count).sum();
    }

    @Test void aSellRuleCountsOnlyUnmodifiedGoodsInTheInputSlots() {
        slots.input(0, DIAMOND, 40).input(3, DIAMOND, 10).input(5, IRON, 7);
        // An enchanted stack in an input slot, and diamonds in an output slot: neither is for sale.
        slots.held[6] = Lot.of(DIAMOND, 1);
        slots.modified.add(6);
        slots.held[TraderInventory.INPUTS] = Lot.of(DIAMOND, 64);

        TraderInventory inventory = selling();

        assertTrue(inventory.reachable());
        assertEquals(50L, inventory.available(DIAMOND));
        assertEquals(7L, inventory.available(IRON));
        assertEquals(0L, inventory.available("minecraft:gold_ingot"));
        assertEquals(0L, inventory.available(null));
        assertEquals(Map.of(DIAMOND, 50L, IRON, 7L), inventory.goods());
    }

    @Test void takingIsAllOrNothingAndComesOutOfTheLastSlotsFirst() {
        slots.input(0, DIAMOND, 40).input(3, DIAMOND, 10);
        TraderInventory inventory = selling();

        // More than is there, a modified lot, nothing at all: refused with nothing touched.
        assertFalse(inventory.take(Lot.of(DIAMOND, 51)));
        assertFalse(inventory.take(new Lot(DIAMOND, 1, 4)));
        assertFalse(inventory.take(null));
        assertFalse(inventory.take(Lot.of(IRON, 1)));
        assertEquals(0, slots.writes);

        assertTrue(inventory.take(Lot.of(DIAMOND, 15)));
        // The later slot is emptied first; the earlier one gives the rest.
        assertNull(slots.held[3]);
        assertEquals(Lot.of(DIAMOND, 35), slots.held[0]);
        assertTrue(inventory.take(Lot.of(DIAMOND, 35)));
        assertEquals(0L, inventory.available(DIAMOND));
        assertFalse(inventory.take(Lot.of(DIAMOND, 1)));
    }

    @Test void aBuyRuleHasRoomOnlyInTheOutputSlots() {
        TraderInventory inventory = buying();
        assertEquals(9L * 64L, inventory.room(DIAMOND));
        // Input slots never count, full or empty.
        slots.input(0, DIAMOND, 1);
        assertEquals(9L * 64L, inventory.room(DIAMOND));
        // A part stack has the rest of its space, another item's stack has none, and neither has a
        // slot that holds something a rule cannot deal in.
        slots.held[9] = Lot.of(DIAMOND, 60);
        slots.held[10] = Lot.of(IRON, 1);
        slots.held[11] = Lot.of(DIAMOND, 1);
        slots.modified.add(11);
        assertEquals(4L + 6L * 64L, inventory.room(DIAMOND));
        assertEquals(63L + 6L * 64L, inventory.room(IRON));
        slots.limit = 16;
        assertEquals(6L * 16L, inventory.room("minecraft:ender_pearl"));
        assertEquals(0L, inventory.room(null));
    }

    @Test void whatABuyRuleBuysGoesIntoTheOutputSlotsAndTheRestToTheVault() {
        TraderInventory inventory = buying();
        slots.held[9] = Lot.of(DIAMOND, 60);

        inventory.put(Lot.of(DIAMOND, 70));

        // The part stack is topped up first, then the next empty output slot.
        assertEquals(Lot.of(DIAMOND, 64), slots.held[9]);
        assertEquals(Lot.of(DIAMOND, 64), slots.held[10]);
        assertEquals(Lot.of(DIAMOND, 2), slots.held[11]);
        assertEquals(0L, slots.inputs(DIAMOND));
        assertTrue(vault.isEmpty());

        // With the outputs full there is no room, and anything that comes all the same is not lost.
        for (int slot = TraderInventory.INPUTS; slot < TraderInventory.SLOTS; slot++) slots.held[slot] = Lot.of(IRON, 64);
        assertEquals(0L, inventory.room(IRON));
        assertEquals(0L, inventory.room(DIAMOND));
        inventory.put(Lot.of(DIAMOND, 5));
        assertEquals(List.of(Lot.of(DIAMOND, 5)), vault);
        // A buy rule never fills the input slots, however empty they are.
        assertEquals(0L, slots.inputs(DIAMOND));
    }

    @Test void whatASellRuleGetsBackGoesToTheInputSlotsFirstSoItIsListedAgain() {
        TraderInventory inventory = selling();
        slots.input(0, DIAMOND, 50);

        inventory.put(Lot.of(DIAMOND, 30));

        assertEquals(Lot.of(DIAMOND, 64), slots.held[0]);
        assertEquals(Lot.of(DIAMOND, 16), slots.held[1]);
        assertEquals(80L, inventory.available(DIAMOND));
        assertEquals(0L, slots.outputs(DIAMOND));
        // Inputs full: the rest goes to the outputs, and only then to the vault.
        for (int slot = 0; slot < TraderInventory.SLOTS; slot++) slots.held[slot] = Lot.of(IRON, 64);
        slots.held[17] = null;
        inventory.put(Lot.of(DIAMOND, 70));
        assertEquals(Lot.of(DIAMOND, 64), slots.held[17]);
        assertEquals(6L, vaulted(DIAMOND));
    }

    @Test void aModifiedLotIsNeverPutInASlotARuleWouldListItFrom() {
        TraderInventory inventory = selling();
        inventory.put(new Lot(DIAMOND, 3, 9));
        inventory.put(null);
        assertEquals(List.of(new Lot(DIAMOND, 3, 9)), vault);
        assertEquals(0, slots.writes);
    }

    @Test void anUnloadedBlockGivesNothingTakesNothingAndIsNeverWritten() {
        slots.input(0, DIAMOND, 40);
        slots.held[9] = Lot.of(IRON, 10);
        slots.loaded = false;
        for (TraderInventory inventory : List.of(selling(), buying())) {
            assertFalse(inventory.reachable());
            assertEquals(0L, inventory.available(DIAMOND));
            assertTrue(inventory.goods().isEmpty());
            assertEquals(0L, inventory.room(DIAMOND));
            assertFalse(inventory.take(Lot.of(DIAMOND, 1)));
        }
        // Goods that arrive for it go to the owner's vault: the slots are not touched.
        selling().put(Lot.of(DIAMOND, 12));
        buying().put(Lot.of(IRON, 3));
        assertEquals(List.of(Lot.of(DIAMOND, 12), Lot.of(IRON, 3)), vault);
        assertEquals(0, slots.writes);
        assertEquals(Lot.of(DIAMOND, 40), slots.held[0]);
        // The moment it loads again everything is there as it was left.
        slots.loaded = true;
        assertEquals(40L, selling().available(DIAMOND));
        assertEquals(8L * 64L, buying().room(DIAMOND));
    }

    @Test void goodsTheSlotsCannotHoldAtAllGoToTheVault() {
        // An item id the game no longer knows fits in no slot: there is no room for it, and what
        // comes back all the same is handed on whole, with no slot written.
        slots.limit = 0;
        assertEquals(0L, buying().room(DIAMOND));
        buying().put(Lot.of(DIAMOND, 5));
        selling().put(Lot.of(DIAMOND, 2));
        assertEquals(List.of(Lot.of(DIAMOND, 5), Lot.of(DIAMOND, 2)), vault);
        assertEquals(0, slots.writes);
    }

    @Test void anInventoryNeedsItsSlotsAndSomewhereForTheOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new TraderInventory(null, true, vault::add));
        assertThrows(IllegalArgumentException.class, () -> new TraderInventory(slots, true, null));
    }

    @Test void nothingIsEverLostOrMadeThroughARandomRunOfPutsAndTakes() {
        Random random = new Random(20_261_003L);
        String[] items = {DIAMOND, IRON, "minecraft:ender_pearl"};
        long[] put = new long[items.length];
        long[] taken = new long[items.length];
        for (int step = 0; step < 20_000; step++) {
            int index = random.nextInt(items.length);
            String item = items[index];
            TraderInventory inventory = random.nextBoolean() ? selling() : buying();
            if (random.nextInt(40) == 0) slots.loaded = !slots.loaded;
            if (random.nextInt(3) == 0) {
                int count = 1 + random.nextInt(100);
                inventory.put(Lot.of(item, count));
                put[index] += count;
            } else {
                long available = inventory.available(item);
                int count = 1 + random.nextInt(80);
                boolean took = inventory.take(Lot.of(item, count));
                assertEquals(slots.loaded && available >= count, took);
                if (took) taken[index] += count;
            }
            // A buy rule's purchases land in the outputs, where nothing sells them: move some back
            // to the inputs now and then, as a player rearranging the block would.
            if (slots.loaded && random.nextInt(10) == 0) {
                int from = TraderInventory.INPUTS + random.nextInt(TraderInventory.OUTPUTS);
                int to = random.nextInt(TraderInventory.INPUTS);
                if (slots.held[from] != null && slots.held[to] == null) {
                    slots.held[to] = slots.held[from];
                    slots.held[from] = null;
                }
            }
            for (int each = 0; each < items.length; each++) {
                long inSlots = slots.count(items[each], 0, TraderInventory.SLOTS);
                assertEquals(put[each] - taken[each], inSlots + vaulted(items[each]), "step " + step);
            }
            for (Lot lot : slots.held) assertTrue(lot == null || lot.count() <= slots.limit);
        }
        assertTrue(taken[0] > 0L && vaulted(DIAMOND) > 0L);
    }
}
