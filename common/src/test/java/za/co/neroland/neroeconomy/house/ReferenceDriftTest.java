package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** How a reference drifts: toward the volume-weighted price, weighted by volume, clamped per step and to a band. */
class ReferenceDriftTest {
    private static final ReferenceDrift.Bounds TEN = ReferenceDrift.bounds(10L, 100, 5_000, 20_000);

    @Test void boundsAreAroundTheDataReference() {
        assertEquals(10_000L, TEN.dataMillis());
        assertEquals(5_000L, TEN.minMillis());
        assertEquals(20_000L, TEN.maxMillis());
        assertEquals(100L, TEN.stepMillis());
        // A step of at least a milli-credit, however cheap the commodity.
        assertEquals(1L, ReferenceDrift.bounds(1L, 1, 5_000, 20_000).stepMillis());
        assertEquals(10_240L, ReferenceDrift.liquidity(10L, 512L, 512L));
        assertEquals(640L, ReferenceDrift.liquidity(10L, 0L, 8L));
    }

    @Test void withoutCountedTradeNothingMoves() {
        assertEquals(10_000L, ReferenceDrift.next(10_000L, 0L, 0L, 10_240L, TEN));
        // A reference outside the band (the data changed) is pulled back into it.
        assertEquals(20_000L, ReferenceDrift.next(50_000L, 0L, 0L, 10_240L, TEN));
    }

    @Test void theMoveIsTheGapWeightedByVolumeAndClampedPerStep() {
        // 1 unit for 11 credits against 10 credits of liquidity: half of the 1-credit gap = 500, clamped to 100.
        assertEquals(10_100L, ReferenceDrift.next(10_000L, 1L, 11L, 11L, TEN));
        // A tiny volume against a deep market moves it by a sliver.
        assertEquals(10_001L, ReferenceDrift.next(10_000L, 1L, 11L, 10_000L, TEN));
        // Down as well as up.
        assertEquals(9_900L, ReferenceDrift.next(10_000L, 100L, 100L, 10L, TEN));
    }

    @Test void neitherSideOfTheBandIsEverPassed() {
        Random random = new Random(3L);
        long reference = 10_000L;
        for (int step = 0; step < 100_000; step++) {
            long quantity = 1L + random.nextInt(100);
            long value = quantity * (1L + random.nextInt(random.nextBoolean() ? 3 : 100));
            long next = ReferenceDrift.next(reference, quantity, value, 10_240L, TEN);
            assertTrue(Math.abs(next - reference) <= TEN.stepMillis());
            assertTrue(next >= TEN.minMillis() && next <= TEN.maxMillis());
            reference = next;
        }
    }
}
