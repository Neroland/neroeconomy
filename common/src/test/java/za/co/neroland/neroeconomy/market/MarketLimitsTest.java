package za.co.neroland.neroeconomy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarketLimitsTest {
    @Test void aCapAdmitsRowsUpToItAndNoFurther() {
        assertTrue(MarketLimits.withinCap(0, 1, 64));
        assertTrue(MarketLimits.withinCap(63, 1, 64));
        assertFalse(MarketLimits.withinCap(64, 1, 64));
        // Adding nothing always fits, even at the cap; this is what lets a no-op pre-flight pass.
        assertTrue(MarketLimits.withinCap(64, 0, 64));
    }

    @Test void aCapOfZeroRefusesEveryNewRow() {
        assertFalse(MarketLimits.withinCap(0, 1, 0));
        assertTrue(MarketLimits.withinCap(0, 0, 0));
    }

    @Test void severalStacksAreJudgedTogether() {
        assertTrue(MarketLimits.withinCap(250, 6, 256));
        assertFalse(MarketLimits.withinCap(250, 7, 256));
    }

    @Test void capArithmeticCannotWrapOrAcceptNonsense() {
        assertFalse(MarketLimits.withinCap(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(MarketLimits.withinCap(-1, 1, 64));
        assertFalse(MarketLimits.withinCap(1, -1, 64));
        assertFalse(MarketLimits.withinCap(1, 1, -1));
    }

    @Test void aStackIsAllowedOnlyWhenItFitsAndIsNotTaggedUntradeable() {
        assertTrue(MarketLimits.stackAllowed(40, 8192, false));
        assertTrue(MarketLimits.stackAllowed(8192, 8192, false));
        assertFalse(MarketLimits.stackAllowed(8193, 8192, false));
        assertFalse(MarketLimits.stackAllowed(40, 8192, true));
        // What an unencodable stack reports: the largest size, or a negative one.
        assertFalse(MarketLimits.stackAllowed(Integer.MAX_VALUE, 1_048_576, false));
        assertFalse(MarketLimits.stackAllowed(-1, 8192, false));
    }

    @Test void pageSizeIsBoundedBothWays() {
        assertEquals(1, MarketLimits.pageSize(0));
        assertEquals(1, MarketLimits.pageSize(-5));
        assertEquals(8, MarketLimits.pageSize(8));
        assertEquals(MarketLimits.MAX_PAGE_SIZE, MarketLimits.pageSize(Integer.MAX_VALUE));
    }

    @Test void pageOffsetsAreExactForAnyPage() {
        assertEquals(0L, MarketLimits.pageOffset(0, 8));
        assertEquals(0L, MarketLimits.pageOffset(-3, 8));
        assertEquals(80_000L, MarketLimits.pageOffset(10_000, 8));
        // int * int would wrap here; the offset is carried in 64 bits instead.
        assertEquals((long) Integer.MAX_VALUE * 50L, MarketLimits.pageOffset(Integer.MAX_VALUE, 50));
        assertEquals((long) Integer.MAX_VALUE * 50L, MarketLimits.pageOffset(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void countsAreClampedNotNarrowed() {
        assertEquals(0, MarketLimits.clampCount(-1L));
        assertEquals(12, MarketLimits.clampCount(12L));
        assertEquals(Integer.MAX_VALUE, MarketLimits.clampCount((long) Integer.MAX_VALUE + 1L));
        assertEquals(Integer.MAX_VALUE, MarketLimits.clampCount(Long.MAX_VALUE));
    }

    @Test void deliveryNeverOffersMoreThanTheInventoryCanPlace() {
        // Room for everything.
        assertEquals(10, MarketLimits.deliverable(10, 1, 0, 64, true));
        // A full inventory takes nothing — the case a creative inventory would otherwise void.
        assertEquals(0, MarketLimits.deliverable(10, 0, 0, 64, true));
        // Only the gaps in matching stacks are available.
        assertEquals(6, MarketLimits.deliverable(10, 0, 6, 64, true));
        assertEquals(64, MarketLimits.deliverable(64, 0, 70, 64, true));
        // Free slots and gaps add up.
        assertEquals(70, MarketLimits.deliverable(100, 1, 6, 64, true));
    }

    @Test void anUnstackableClaimMovesWholeOrNotAtAll() {
        assertEquals(1, MarketLimits.deliverable(1, 1, 0, 1, false));
        assertEquals(0, MarketLimits.deliverable(1, 0, 5, 1, false));
    }

    @Test void deliveryRejectsNonsenseInputs() {
        assertEquals(0, MarketLimits.deliverable(0, 5, 5, 64, true));
        assertEquals(0, MarketLimits.deliverable(-4, 5, 5, 64, true));
        assertEquals(0, MarketLimits.deliverable(10, -1, 5, 64, true));
        assertEquals(0, MarketLimits.deliverable(10, 5, -1, 64, true));
        assertEquals(0, MarketLimits.deliverable(10, 5, 5, 0, true));
        assertEquals(10, MarketLimits.deliverable(10, Integer.MAX_VALUE, Integer.MAX_VALUE, 99, true));
    }
}
