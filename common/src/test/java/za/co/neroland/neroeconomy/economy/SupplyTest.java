package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The minted and burned counters behind Invariant A. */
class SupplyTest {
    private static final long DAY = 20_000L;

    @Test void onlyMintingAndBurningReasonsAreCounted() {
        Supply supply = new Supply();
        for (TransactionType type : TransactionType.values()) {
            assertEquals(type.mints(), supply.mint(type, 10L, DAY), type.name());
            assertEquals(type.burns(), supply.burn(type, 1L, DAY), type.name());
        }
        // Four reasons mint and two burn; nothing else does either.
        assertEquals(40L, supply.mintedTotal());
        assertEquals(2L, supply.burnedTotal());
        assertEquals(38L, supply.net());
        assertEquals(4, supply.mintedByReason().size());
        assertEquals(2, supply.burnedByReason().size());
        assertFalse(supply.mint(null, 1L, DAY));
        assertFalse(supply.mint(TransactionType.ADMIN_GRANT, -1L, DAY));
        assertFalse(supply.burn(TransactionType.ADMIN_TAKE, -1L, DAY));
    }

    @Test void theDayCountersStartAgainEachEconomyDay() {
        Supply supply = new Supply();
        assertTrue(supply.mint(TransactionType.RESERVE_MINT, 500L, DAY));
        assertTrue(supply.burn(TransactionType.ADMIN_TAKE, 20L, DAY));
        assertEquals(500L, supply.mintedToday(DAY));
        assertEquals(20L, supply.burnedToday(DAY));
        // Asked about another day before anything happened on it: nothing yet.
        assertEquals(0L, supply.mintedToday(DAY + 1L));
        assertTrue(supply.mint(TransactionType.RESERVE_MINT, 7L, DAY + 1L));
        assertEquals(7L, supply.mintedToday(DAY + 1L));
        assertEquals(0L, supply.burnedToday(DAY + 1L));
        assertEquals(507L, supply.mintedTotal());
        assertEquals(20L, supply.burnedTotal());
    }

    @Test void theSupplyCanNeverPassTheLargestAmountOrGoBelowZero() {
        Supply supply = new Supply();
        assertTrue(supply.mint(TransactionType.ADMIN_GRANT, Long.MAX_VALUE - 5L, DAY));
        assertFalse(supply.canMint(TransactionType.ADMIN_GRANT, 6L));
        assertFalse(supply.mint(TransactionType.ADMIN_GRANT, 6L, DAY));
        assertTrue(supply.mint(TransactionType.ADMIN_GRANT, 5L, DAY));
        assertEquals(Long.MAX_VALUE, supply.net());
        assertFalse(supply.mint(TransactionType.RESERVE_MINT, 1L, DAY));
        // More cannot be burned than exists.
        Supply small = new Supply();
        assertTrue(small.mint(TransactionType.ADMIN_GRANT, 10L, DAY));
        assertFalse(small.burn(TransactionType.ADMIN_TAKE, 11L, DAY));
        assertTrue(small.burn(TransactionType.ADMIN_TAKE, 10L, DAY));
        assertEquals(0L, small.net());
        assertFalse(small.canBurn(TransactionType.ADMIN_TAKE, 1L));
        // Minting or burning nothing is accepted and counts for nothing.
        assertTrue(small.mint(TransactionType.ADMIN_GRANT, 0L, DAY));
        assertTrue(small.burn(TransactionType.ADMIN_TAKE, 0L, DAY));
        assertEquals(10L, small.mintedTotal());
    }
}
