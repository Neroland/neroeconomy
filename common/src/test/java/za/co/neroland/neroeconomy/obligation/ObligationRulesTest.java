package za.co.neroland.neroeconomy.obligation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ObligationRulesTest {
    @Test void theFirstBidOnlyHasToMeetTheLotMinimum() {
        assertEquals(50L, ObligationRules.minimumNextBid(50L, 0L, 5));
        assertEquals(1L, ObligationRules.minimumNextBid(0L, 0L, 5));
    }

    @Test void aLaterBidMustBeatTheStandingOneByThePercentage() {
        assertEquals(105L, ObligationRules.minimumNextBid(50L, 100L, 5));
        assertEquals(1100L, ObligationRules.minimumNextBid(50L, 1000L, 10));
        assertEquals(200L, ObligationRules.minimumNextBid(50L, 100L, 100));
    }

    @Test void theIncrementRoundsUpAndIsNeverLessThanOneCredit() {
        // 5% of 10 is half a credit: the step is still a whole credit.
        assertEquals(11L, ObligationRules.minimumNextBid(1L, 10L, 5));
        // 5% of 101 is 5.05, which rounds up to 6.
        assertEquals(107L, ObligationRules.minimumNextBid(1L, 101L, 5));
        // A zero percentage still requires the bid to move.
        assertEquals(101L, ObligationRules.minimumNextBid(1L, 100L, 0));
    }

    @Test void anOutOfRangePercentageIsClamped() {
        assertEquals(101L, ObligationRules.minimumNextBid(1L, 100L, -20));
        assertEquals(200L, ObligationRules.minimumNextBid(1L, 100L, 500));
    }

    @Test void theNextBidSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, ObligationRules.minimumNextBid(1L, Long.MAX_VALUE, 5));
        assertEquals(Long.MAX_VALUE, ObligationRules.minimumNextBid(1L, Long.MAX_VALUE - 1L, 100));
        assertEquals(Long.MAX_VALUE, ObligationRules.minimumNextBid(1L, Long.MAX_VALUE / 2L + 1L, 100));
    }

    @Test void aLotMinimumAboveTheNextStepStillApplies() {
        assertEquals(500L, ObligationRules.minimumNextBid(500L, 100L, 5));
    }

    @Test void aClaimLapsesExactlyAtItsTimeout() {
        long claimedAt = 1_000_000L;
        long window = 120L * 60_000L;
        assertEquals(claimedAt + window, ObligationRules.claimDeadline(claimedAt, 120));
        assertFalse(ObligationRules.claimTimedOut(claimedAt, claimedAt + window - 1L, 120));
        assertTrue(ObligationRules.claimTimedOut(claimedAt, claimedAt + window, 120));
    }

    @Test void aZeroTimeoutNeverReleasesAClaim() {
        assertEquals(Long.MAX_VALUE, ObligationRules.claimDeadline(1_000L, 0));
        assertFalse(ObligationRules.claimTimedOut(1_000L, Long.MAX_VALUE - 1L, 0));
    }

    @Test void anUnstampedClaimIsNotTimedOut() {
        // A claim saved by an older build has no time; the tick stamps it rather than releasing it.
        assertEquals(Long.MAX_VALUE, ObligationRules.claimDeadline(0L, 120));
        assertFalse(ObligationRules.claimTimedOut(0L, 9_000_000_000L, 120));
    }

    @Test void theDeadlineSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, ObligationRules.claimDeadline(Long.MAX_VALUE - 5L, 10080));
    }
}
