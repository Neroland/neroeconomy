package za.co.neroland.neroeconomy.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Packed64Test {
    /** What the vanilla data-slot packet does to one slot: writeShort on the way out, readShort on the way in. */
    private static int overTheWire(int sent) { return (short) sent; }

    private static long roundTrip(long value) {
        return Packed64.combine(overTheWire(Packed64.get(value, 0)), overTheWire(Packed64.get(value, 1)),
                overTheWire(Packed64.get(value, 2)), overTheWire(Packed64.get(value, 3)));
    }

    @Test void everyPartFitsTheSixteenBitsASlotCarries() {
        for (long value : new long[] { 0L, 1L, 32_767L, 32_768L, 65_535L, 65_536L, Long.MAX_VALUE, -1L, Long.MIN_VALUE })
            for (int index = 0; index < Packed64.SLOTS; index++) {
                int part = Packed64.get(value, index);
                assertTrue(part >= 0 && part <= 0xFFFF, value + " part " + index + " = " + part);
            }
    }

    @Test void valuesAroundTheShortRangeSurviveTheWire() {
        for (long value : new long[] { 0L, 1L, 32_766L, 32_767L, 32_768L, 32_769L, 65_535L, 65_536L, 65_537L })
            assertEquals(value, roundTrip(value));
    }

    @Test void aPartThatArrivesSignExtendedDoesNotLeakIntoItsNeighbours() {
        // 32,768 is 0x8000: the client reads that slot back as -32,768. Unmasked, its sign bits
        // would set every higher bit of the rebuilt value.
        assertEquals(-32_768, overTheWire(Packed64.get(32_768L, 0)));
        assertEquals(32_768L, Packed64.combine(-32_768, 0, 0, 0));
        assertEquals(0xFFFFL, Packed64.combine(-1, 0, 0, 0));
        assertEquals(0xFFFF_0000L, Packed64.combine(0, -1, 0, 0));
        assertEquals(0xFFFF_0000_0000L, Packed64.combine(0, 0, -1, 0));
        assertEquals(0xFFFF_0000_0000_0000L, Packed64.combine(0, 0, 0, -1));
        assertEquals(-1L, Packed64.combine(-1, -1, -1, -1));
    }

    @Test void largeBalancesSurviveTheWire() {
        for (long value : new long[] { 1L << 31, (1L << 31) - 1L, (1L << 32) + 5L, 1L << 48, (1L << 48) - 1L,
                1_000_000_000_000L, Long.MAX_VALUE - 1L, Long.MAX_VALUE })
            assertEquals(value, roundTrip(value));
    }

    @Test void negativeValuesSurviveTheWireToo() {
        // The short range's own negatives, and the extremes: nothing here should ever be negative in
        // play, but a helper that mangled them would hide a bug rather than show it.
        for (long value : new long[] { -1L, -32_767L, -32_768L, -32_769L, -65_536L, Long.MIN_VALUE })
            assertEquals(value, roundTrip(value));
    }

    @Test void partsAreLeastSignificantFirst() {
        long value = 0x1122_3344_5566_7788L;
        assertEquals(0x7788, Packed64.get(value, 0));
        assertEquals(0x5566, Packed64.get(value, 1));
        assertEquals(0x3344, Packed64.get(value, 2));
        assertEquals(0x1122, Packed64.get(value, 3));
        assertEquals(value, Packed64.combine(0x7788, 0x5566, 0x3344, 0x1122));
    }

    @Test void anIndexOutsideTheFourPartsIsZero() {
        assertEquals(0, Packed64.get(Long.MAX_VALUE, -1));
        assertEquals(0, Packed64.get(Long.MAX_VALUE, Packed64.SLOTS));
    }

    @Test void readTakesFourConsecutiveSlotsFromTheGivenOffset() {
        long price = 123_456_789_012L;
        int[] slots = new int[1 + Packed64.SLOTS];
        slots[0] = 1;
        for (int index = 0; index < Packed64.SLOTS; index++) slots[1 + index] = overTheWire(Packed64.get(price, index));
        assertEquals(price, Packed64.read(slot -> slots[slot], 1));
    }

    @Test void aThirtyTwoBitCountUsesTwoSlots() {
        for (int count : new int[] { 0, 1, 32_767, 32_768, 65_536, 1_000_000, Integer.MAX_VALUE }) {
            int[] slots = { overTheWire(Packed64.get(count, 0)), overTheWire(Packed64.get(count, 1)) };
            assertEquals(count, Packed64.readInt(slot -> slots[slot], 0));
        }
    }
}
