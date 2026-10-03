package za.co.neroland.neroeconomy.goods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The arithmetic of lots: nothing it does creates or loses an item. */
class LotsTest {
    private static final String DIAMOND = "minecraft:diamond";
    private static final String PEARL = "minecraft:ender_pearl";
    private static final String SWORD = "minecraft:diamond_sword";
    /** Diamonds stack to 64, pearls to 16, a sword not at all. */
    private static final StackLimit LIMIT = (item, payload) -> switch (item) {
        case PEARL -> 16;
        case SWORD -> 1;
        default -> 64;
    };

    @Test void aLotNeedsAnItemAPositiveCountAndANonNegativePayload() {
        assertTrue(Lot.valid(DIAMOND, 1, 0));
        assertFalse(Lot.valid(DIAMOND, 0, 0));
        assertFalse(Lot.valid(DIAMOND, -3, 0));
        assertFalse(Lot.valid(DIAMOND, 1, -1));
        assertFalse(Lot.valid("", 1, 0));
        assertFalse(Lot.valid("  ", 1, 0));
        assertFalse(Lot.valid(null, 1, 0));
        assertFalse(Lot.valid("x".repeat(Lot.MAX_ITEM_LENGTH + 1), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Lot(DIAMOND, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Lot.Key(DIAMOND, -1));
        assertTrue(Lot.of(DIAMOND, 5).pristine());
        assertFalse(new Lot(DIAMOND, 5, 3).pristine());
    }

    @Test void goodsAreTheSameOnlyWhenItemAndPayloadBothMatch() {
        Lot plain = Lot.of(SWORD, 1);
        Lot named = new Lot(SWORD, 1, 7);
        Lot otherNamed = new Lot(SWORD, 1, 8);
        // An unmodified stack is fungible with every other unmodified stack of the item...
        assertTrue(plain.sameGoods(Lot.of(SWORD, 40)));
        // ...a modified one only with its own prototype, and never with the unmodified item.
        assertTrue(named.sameGoods(new Lot(SWORD, 3, 7)));
        assertFalse(named.sameGoods(otherNamed));
        assertFalse(named.sameGoods(plain));
        assertFalse(plain.sameGoods(Lot.of(DIAMOND, 1)));
        assertFalse(plain.sameGoods(null));
        assertEquals(plain.key(), Lot.of(SWORD, 9).key());
        assertEquals(new Lot(SWORD, 4, 7), named.withCount(4));
    }

    @Test void splittingCutsALotAtTheLimitAndKeepsEveryItem() {
        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 2)),
                Lots.split(Lot.of(DIAMOND, 130), 64));
        assertEquals(List.of(Lot.of(DIAMOND, 64)), Lots.split(Lot.of(DIAMOND, 64), 64));
        assertEquals(List.of(Lot.of(DIAMOND, 3)), Lots.split(Lot.of(DIAMOND, 3), 64));
        assertEquals(3, Lots.split(Lot.of(SWORD, 3), 1).size());
        // A limit that makes no sense is treated as one per stack rather than looping for ever.
        assertEquals(2, Lots.split(Lot.of(SWORD, 2), 0).size());
    }

    @Test void mergingTopsUpPartStacksOldestFirstThenAppends() {
        List<Lot> held = List.of(Lot.of(DIAMOND, 60), Lot.of(PEARL, 16), Lot.of(DIAMOND, 10));
        List<Lot> merged = Lots.merge(held, List.of(Lot.of(DIAMOND, 70)), LIMIT);
        // 4 into the first stack, 54 into the third, the remaining 12 as a new stack.
        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(PEARL, 16), Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 12)), merged);
        // The input list is not changed.
        assertEquals(Lot.of(DIAMOND, 60), held.get(0));
        // A full stack is not touched, so a new one is opened.
        assertEquals(List.of(Lot.of(PEARL, 16), Lot.of(PEARL, 1)), Lots.merge(List.of(Lot.of(PEARL, 16)),
                List.of(Lot.of(PEARL, 1)), LIMIT));
    }

    @Test void mergingNeverMixesAModifiedStackWithAnUnmodifiedOne() {
        List<Lot> merged = Lots.merge(List.of(Lot.of(DIAMOND, 1), new Lot(DIAMOND, 1, 4)),
                List.of(new Lot(DIAMOND, 2, 4), new Lot(DIAMOND, 2, 5), Lot.of(DIAMOND, 2)), LIMIT);
        assertEquals(List.of(Lot.of(DIAMOND, 3), new Lot(DIAMOND, 3, 4), new Lot(DIAMOND, 2, 5)), merged);
    }

    @Test void aStackAlreadyOverItsLimitIsLeftAsItIs() {
        // Nothing is ever trimmed: an oversized stack from an older save stays whole.
        List<Lot> merged = Lots.merge(List.of(Lot.of(PEARL, 40)), List.of(Lot.of(PEARL, 5)), LIMIT);
        assertEquals(List.of(Lot.of(PEARL, 40), Lot.of(PEARL, 5)), merged);
        assertEquals(45L, Lots.total(merged, PEARL, 0));
    }

    @Test void takingComesOffTheNewestStackFirstAndReportsWhatItGot() {
        List<Lot> held = List.of(Lot.of(DIAMOND, 64), Lot.of(PEARL, 5), Lot.of(DIAMOND, 10));
        Lots.Taken some = Lots.take(held, DIAMOND, 0, 12L);
        assertEquals(12L, some.taken());
        assertEquals(List.of(Lot.of(DIAMOND, 62), Lot.of(PEARL, 5)), some.stacks());
        // More than there is: everything of that kind, and the count says so.
        Lots.Taken all = Lots.take(held, DIAMOND, 0, 1_000L);
        assertEquals(74L, all.taken());
        assertEquals(List.of(Lot.of(PEARL, 5)), all.stacks());
        // Nothing of that kind, or nothing asked for.
        assertEquals(0L, Lots.take(held, DIAMOND, 9, 5L).taken());
        assertEquals(0L, Lots.take(held, DIAMOND, 0, -5L).taken());
        assertEquals(held, Lots.take(held, DIAMOND, 0, 0L).stacks());
    }

    @Test void totalsAreCountedPerItemAndPayload() {
        List<Lot> held = List.of(Lot.of(DIAMOND, 64), new Lot(DIAMOND, 2, 3), Lot.of(DIAMOND, 6), Lot.of(PEARL, 5));
        assertEquals(70L, Lots.total(held, DIAMOND, 0));
        assertEquals(2L, Lots.total(held, DIAMOND, 3));
        assertEquals(0L, Lots.total(held, SWORD, 0));
        assertEquals(Map.of(new Lot.Key(DIAMOND, 0), 70L, new Lot.Key(DIAMOND, 3), 2L, new Lot.Key(PEARL, 0), 5L),
                Lots.totals(held));
        assertEquals(77L, Lots.count(held));
    }

    @Test void aLongRandomRunOfMergesAndTakesNeverChangesWhatIsThere() {
        Random random = new Random(7L);
        String[] items = {DIAMOND, PEARL, SWORD};
        List<Lot> held = new ArrayList<>();
        long[][] expected = new long[items.length][3];
        for (int step = 0; step < 5_000; step++) {
            int item = random.nextInt(items.length);
            int payload = random.nextInt(3);
            if (random.nextBoolean()) {
                int count = 1 + random.nextInt(200);
                held = Lots.merge(held, List.of(new Lot(items[item], count, payload)), LIMIT);
                expected[item][payload] += count;
            } else {
                Lots.Taken taken = Lots.take(held, items[item], payload, random.nextInt(260));
                held = taken.stacks();
                expected[item][payload] -= taken.taken();
            }
            for (int i = 0; i < items.length; i++) for (int p = 0; p < 3; p++)
                assertEquals(expected[i][p], Lots.total(held, items[i], p), "step " + step);
            // Every stack this arithmetic made respects its limit.
            for (Lot lot : held) assertTrue(lot.count() <= LIMIT.limitOf(lot), "step " + step);
        }
    }
}
