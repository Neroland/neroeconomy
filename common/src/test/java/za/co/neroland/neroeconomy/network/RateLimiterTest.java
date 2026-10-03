package za.co.neroland.neroeconomy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.network.RateLimiter.Budget;

class RateLimiterTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong clock = new AtomicLong(5 * SECOND);
    private final RateLimiter limiter = new RateLimiter(clock::get);
    private final UUID player = UUID.randomUUID();

    private int drain(UUID who, Budget budget) {
        int taken = 0;
        while (taken < 1_000 && limiter.tryAcquire(who, budget)) taken++;
        return taken;
    }

    @Test void aFreshBucketAllowsExactlyItsBurst() {
        assertEquals(Budget.QUERY.burst(), drain(player, Budget.QUERY));
        assertFalse(limiter.tryAcquire(player, Budget.QUERY));
    }

    @Test void valueMovingIntentsGetTheTighterBudget() {
        assertTrue(Budget.VALUE.burst() < Budget.QUERY.burst());
        assertTrue(Budget.VALUE.perSecond() < Budget.QUERY.perSecond());
        assertEquals(Budget.VALUE.burst(), drain(player, Budget.VALUE));
    }

    @Test void theTwoBudgetsAreSeparateBuckets() {
        drain(player, Budget.VALUE);
        assertTrue(limiter.tryAcquire(player, Budget.QUERY));
        drain(player, Budget.QUERY);
        assertFalse(limiter.tryAcquire(player, Budget.VALUE));
    }

    @Test void tokensComeBackAtTheRefillRate() {
        drain(player, Budget.VALUE);
        clock.addAndGet(SECOND);
        assertEquals(Budget.VALUE.perSecond(), drain(player, Budget.VALUE));
        clock.addAndGet(SECOND / 2);
        assertEquals(Budget.VALUE.perSecond() / 2, drain(player, Budget.VALUE));
    }

    @Test void aPartialTokenIsNotEnough() {
        drain(player, Budget.VALUE);
        // One millisecond short of a whole token, then one millisecond past it.
        clock.addAndGet(SECOND / Budget.VALUE.perSecond() - 1_000_000L);
        assertFalse(limiter.tryAcquire(player, Budget.VALUE));
        clock.addAndGet(2_000_000L);
        assertTrue(limiter.tryAcquire(player, Budget.VALUE));
        assertFalse(limiter.tryAcquire(player, Budget.VALUE));
    }

    @Test void aLongIdleNeverBanksMoreThanTheBurst() {
        drain(player, Budget.QUERY);
        clock.addAndGet(3_600L * SECOND);
        assertEquals(Budget.QUERY.burst(), drain(player, Budget.QUERY));
    }

    @Test void aClockThatStepsBackwardsNeitherRefillsNorBreaksTheBucket() {
        drain(player, Budget.QUERY);
        clock.addAndGet(-10L * SECOND);
        assertFalse(limiter.tryAcquire(player, Budget.QUERY));
        clock.addAndGet(SECOND);
        assertEquals(Budget.QUERY.perSecond(), drain(player, Budget.QUERY));
    }

    @Test void onePlayersFloodDoesNotSpendAnothersBudget() {
        UUID other = UUID.randomUUID();
        drain(player, Budget.VALUE);
        assertEquals(Budget.VALUE.burst(), drain(other, Budget.VALUE));
    }

    @Test void logoutForgetsThePlayerAndOnlyThatPlayer() {
        UUID other = UUID.randomUUID();
        drain(player, Budget.VALUE);
        drain(other, Budget.VALUE);
        assertEquals(2, limiter.tracked());
        limiter.forget(player);
        assertEquals(1, limiter.tracked());
        // Nothing is remembered about them: a returning player starts with a full bucket.
        assertEquals(Budget.VALUE.burst(), drain(player, Budget.VALUE));
        assertFalse(limiter.tryAcquire(other, Budget.VALUE));
    }

    @Test void serverStopClearsEverything() {
        drain(player, Budget.VALUE);
        drain(UUID.randomUUID(), Budget.QUERY);
        limiter.clear();
        assertEquals(0, limiter.tracked());
    }

    @Test void aMissingPlayerOrBudgetIsRefusedAndTracksNothing() {
        assertFalse(limiter.tryAcquire(null, Budget.QUERY));
        assertFalse(limiter.tryAcquire(player, null));
        limiter.forget(null);
        assertEquals(0, limiter.tracked());
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(null));
    }
}
