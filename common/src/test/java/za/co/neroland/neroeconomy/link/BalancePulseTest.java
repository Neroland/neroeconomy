package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BalancePulseTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long SECOND = BalancePulse.MIN_INTERVAL_NANOS;

    @Test void aChangeForAPlayerWhoseAppNeverAskedProducesNoEvent() {
        BalancePulse pulse = new BalancePulse();
        pulse.changed(ALICE, 0L);
        assertFalse(pulse.hasPending());
        assertTrue(pulse.due(SECOND).isEmpty());
        assertEquals(0, pulse.tracked());
    }

    @Test void manyChangesInsideOneSecondBecomeOneEvent() {
        BalancePulse pulse = new BalancePulse();
        pulse.interested(ALICE, 0L);
        pulse.changed(ALICE, 10L);
        pulse.changed(ALICE, 20L);
        pulse.changed(ALICE, 30L);
        assertTrue(pulse.hasPending());
        assertEquals(List.of(ALICE), pulse.due(40L));
        assertFalse(pulse.hasPending());
        assertTrue(pulse.due(50L).isEmpty());
    }

    @Test void aSecondChangeWaitsOutTheIntervalAndIsThenPublishedByTheTick() {
        BalancePulse pulse = new BalancePulse();
        pulse.interested(ALICE, 0L);
        pulse.changed(ALICE, 10L);
        assertEquals(List.of(ALICE), pulse.due(10L));
        pulse.changed(ALICE, 20L);
        // Still inside the interval: held back, but kept.
        assertTrue(pulse.due(10L + SECOND - 1L).isEmpty());
        assertTrue(pulse.hasPending());
        assertEquals(List.of(ALICE), pulse.due(10L + SECOND));
        assertFalse(pulse.hasPending());
    }

    @Test void playersAreCoalescedIndependently() {
        BalancePulse pulse = new BalancePulse();
        pulse.interested(ALICE, 0L);
        pulse.interested(BOB, 0L);
        pulse.changed(ALICE, 10L);
        assertEquals(List.of(ALICE), pulse.due(10L));
        pulse.changed(ALICE, 20L);
        pulse.changed(BOB, 20L);
        assertEquals(List.of(BOB), pulse.due(30L));
        assertEquals(List.of(ALICE), pulse.due(10L + SECOND));
    }

    @Test void eventsStopOnceTheAppHasBeenQuietForTheInterestWindow() {
        BalancePulse pulse = new BalancePulse();
        pulse.interested(ALICE, 0L);
        long justInside = BalancePulse.INTEREST_WINDOW_NANOS - 1L;
        pulse.changed(ALICE, justInside);
        assertEquals(List.of(ALICE), pulse.due(justInside));
        // The window has passed with no request from the app: a closed app gets nothing more.
        pulse.changed(ALICE, BalancePulse.INTEREST_WINDOW_NANOS);
        assertFalse(pulse.hasPending());
        // A change noted inside the window is dropped if the window closes before it is published.
        pulse.interested(BOB, 0L);
        pulse.changed(BOB, justInside);
        assertTrue(pulse.due(BalancePulse.INTEREST_WINDOW_NANOS).isEmpty());
        assertFalse(pulse.hasPending());
        // Any new request from the app opens the window again.
        pulse.interested(ALICE, BalancePulse.INTEREST_WINDOW_NANOS + 5L);
        pulse.changed(ALICE, BalancePulse.INTEREST_WINDOW_NANOS + 2L * SECOND);
        assertEquals(List.of(ALICE), pulse.due(BalancePulse.INTEREST_WINDOW_NANOS + 2L * SECOND));
    }

    @Test void erasureAndUnbindLeaveNothingBehind() {
        BalancePulse pulse = new BalancePulse();
        pulse.interested(ALICE, 0L);
        pulse.interested(BOB, 0L);
        pulse.changed(ALICE, 1L);
        pulse.changed(BOB, 1L);
        pulse.forget(ALICE);
        assertEquals(List.of(BOB), pulse.due(2L));
        assertEquals(1, pulse.tracked());
        pulse.forget(null);
        pulse.changed(BOB, 3L);
        pulse.clear();
        assertFalse(pulse.hasPending());
        assertEquals(0, pulse.tracked());
        assertTrue(pulse.due(10L * SECOND).isEmpty());
    }

    @Test void nullPlayersAreIgnored() {
        BalancePulse pulse = new BalancePulse();
        pulse.interested(null, 0L);
        pulse.changed(null, 0L);
        assertFalse(pulse.hasPending());
        assertEquals(0, pulse.tracked());
    }
}
