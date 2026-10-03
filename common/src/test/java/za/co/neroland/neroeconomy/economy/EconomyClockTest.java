package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EconomyClockTest {
    private static final long T0 = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @Test void normalAdvanceTracksTheWallClockExactly() {
        EconomyClock clock = new EconomyClock();
        assertEquals(T0, clock.observe(T0));
        assertEquals(T0 + 50L, clock.observe(T0 + 50L));
        assertEquals(T0 + HOUR, clock.observe(T0 + HOUR));
        assertEquals(0L, clock.offsetMillis());
        assertEquals(T0 + HOUR, clock.lastWallMillis());
    }

    @Test void backwardsJumpIsAbsorbedSoEconomyTimeNeverRunsBackwards() {
        EconomyClock clock = new EconomyClock();
        assertEquals(T0, clock.observe(T0));
        // The wall clock is set back five hours: economy time holds still instead of following it.
        assertEquals(T0, clock.observe(T0 - 5L * HOUR));
        assertEquals(5L * HOUR, clock.offsetMillis());
        // ...and then keeps advancing at the normal rate from where it was.
        assertEquals(T0 + 1_000L, clock.observe(T0 - 5L * HOUR + 1_000L));
    }

    @Test void forwardJumpBeyondTheCapAdvancesByTheCapOnly() {
        EconomyClock clock = new EconomyClock();
        assertEquals(T0, clock.observe(T0));
        assertEquals(T0 + EconomyClock.MAX_FORWARD_STEP_MILLIS, clock.observe(T0 + 100L * HOUR));
        assertEquals(-28L * HOUR, clock.offsetMillis());
        assertEquals(T0 + EconomyClock.MAX_FORWARD_STEP_MILLIS + HOUR, clock.observe(T0 + 101L * HOUR));
    }

    @Test void forwardStepOfExactlyTheCapIsNotClipped() {
        EconomyClock clock = new EconomyClock();
        clock.observe(T0);
        assertEquals(T0 + EconomyClock.MAX_FORWARD_STEP_MILLIS, clock.observe(T0 + EconomyClock.MAX_FORWARD_STEP_MILLIS));
        assertEquals(0L, clock.offsetMillis());
    }

    @Test void theTwoPersistedFieldsRestoreTheClockExactly() {
        EconomyClock clock = new EconomyClock();
        clock.observe(T0);
        clock.observe(T0 - 2L * HOUR);
        clock.observe(T0 + 200L * HOUR);
        EconomyClock restored = new EconomyClock(clock.offsetMillis(), clock.lastWallMillis());
        assertEquals(clock.offsetMillis(), restored.offsetMillis());
        assertEquals(clock.lastWallMillis(), restored.lastWallMillis());
        long next = T0 + 201L * HOUR;
        assertEquals(clock.observe(next), restored.observe(next));
    }

    @Test void aLongShutdownAfterRestoreIsCappedLikeAnyOtherForwardJump() {
        EconomyClock clock = new EconomyClock();
        long before = clock.observe(T0);
        EconomyClock restored = new EconomyClock(clock.offsetMillis(), clock.lastWallMillis());
        // Thirty days later the server starts again: economy time moves on by the cap, not by a month.
        assertEquals(before + EconomyClock.MAX_FORWARD_STEP_MILLIS, restored.observe(T0 + 30L * 24L * HOUR));
    }

    @Test void anOldSaveWithNeitherFieldStartsAtTheFirstObservation() {
        // Offset 0 and "never observed": the first reading is taken as-is, however large it is.
        EconomyClock fromOldSave = new EconomyClock(0L, 0L);
        assertEquals(T0, fromOldSave.observe(T0));
        assertEquals(0L, fromOldSave.offsetMillis());
        // A corrupt negative watermark is treated the same way rather than as a huge forward jump.
        EconomyClock corrupt = new EconomyClock(0L, -5L);
        assertEquals(0L, corrupt.lastWallMillis());
        assertEquals(T0, corrupt.observe(T0));
    }

    @Test void toWallMillisUndoesTheOffsetAndLeavesSentinelsAlone() {
        EconomyClock clock = new EconomyClock();
        clock.observe(T0);
        // No offset yet: the two clocks agree.
        assertEquals(T0 + HOUR, clock.toWallMillis(T0 + HOUR));
        clock.observe(T0 + 100L * HOUR);
        long economyNow = clock.observe(T0 + 100L * HOUR);
        // "Now" maps back onto the wall clock, so a deadline an hour away is an hour away on the wall too.
        assertEquals(T0 + 100L * HOUR, clock.toWallMillis(economyNow));
        assertEquals(T0 + 101L * HOUR, clock.toWallMillis(economyNow + HOUR));
        assertEquals(0L, clock.toWallMillis(0L));
        assertEquals(-1L, clock.toWallMillis(-1L));
        assertEquals(Long.MAX_VALUE, clock.toWallMillis(Long.MAX_VALUE));
        // A value so large that removing a negative offset would overflow is returned unchanged.
        assertEquals(Long.MAX_VALUE - 1L, clock.toWallMillis(Long.MAX_VALUE - 1L));
    }

    @Test void economyTimeIsMonotonicAcrossMixedJumps() {
        EconomyClock clock = new EconomyClock();
        long[] wall = { T0, T0 + 10L, T0 - HOUR, T0 - HOUR + 5L, T0 + 500L * HOUR, T0 + 499L * HOUR, T0, T0 + 1L };
        long previous = Long.MIN_VALUE;
        for (long reading : wall) {
            long economy = clock.observe(reading);
            assertTrue(economy >= previous, "economy time ran backwards");
            previous = economy;
        }
    }
}
