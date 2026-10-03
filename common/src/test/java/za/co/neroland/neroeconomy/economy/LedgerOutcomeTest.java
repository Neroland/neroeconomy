package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LedgerOutcomeTest {
    @Test void depositIsConfirmedOnlyByAnExactRise() {
        assertTrue(LedgerOutcome.deposited(100L, 125L, 25L));
        assertTrue(LedgerOutcome.deposited(100L, 100L, 0L));
        assertFalse(LedgerOutcome.deposited(100L, 100L, 25L));
        assertFalse(LedgerOutcome.deposited(100L, 124L, 25L));
        assertFalse(LedgerOutcome.deposited(100L, 75L, 25L));
        assertFalse(LedgerOutcome.deposited(100L, 99L, -1L));
    }

    @Test void withdrawalIsConfirmedOnlyByAnExactFall() {
        assertTrue(LedgerOutcome.withdrawn(100L, 60L, 40L));
        assertTrue(LedgerOutcome.withdrawn(100L, 100L, 0L));
        assertFalse(LedgerOutcome.withdrawn(100L, 100L, 40L));
        assertFalse(LedgerOutcome.withdrawn(100L, 140L, 40L));
        assertFalse(LedgerOutcome.withdrawn(100L, 61L, 40L));
    }

    @Test void transferNeedsBothSidesToHaveMoved() {
        assertTrue(LedgerOutcome.transferred(100L, 70L, 50L, 80L, 30L));
        // One side alone is never a success: that would report a transfer that did not complete.
        assertFalse(LedgerOutcome.transferred(100L, 70L, 50L, 50L, 30L));
        assertFalse(LedgerOutcome.transferred(100L, 100L, 50L, 80L, 30L));
        assertFalse(LedgerOutcome.transferred(100L, 100L, 50L, 50L, 30L));
    }

    @Test void extremeBalancesDoNotOverflowTheComparison() {
        assertTrue(LedgerOutcome.deposited(0L, Long.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(LedgerOutcome.withdrawn(Long.MAX_VALUE, 0L, Long.MAX_VALUE));
        assertFalse(LedgerOutcome.deposited(Long.MAX_VALUE, 0L, 1L));
    }
}
