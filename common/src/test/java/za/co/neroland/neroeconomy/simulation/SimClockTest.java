package za.co.neroland.neroeconomy.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.EconomyClock;

/**
 * Which steps are due. Everything here is arithmetic on two numbers — the last completed step and
 * the economy time — which is the whole point: nothing else can make the simulation advance.
 */
class SimClockTest {
    private static final long STEP = 600_000L;
    /** 72 hours of ten-minute steps. */
    private static final long CAP = 432L;
    private static final int PER_TICK = SimClock.DEFAULT_STEPS_PER_TICK;

    @Test void theStepIndexIsEconomyTimeDividedByTheStepLength() {
        assertEquals(0L, SimClock.stepIndex(0L, STEP));
        assertEquals(0L, SimClock.stepIndex(STEP - 1L, STEP));
        assertEquals(1L, SimClock.stepIndex(STEP, STEP));
        assertEquals(2_900_000L, SimClock.stepIndex(2_900_000L * STEP + 17L, STEP));
        assertEquals(STEP * 5L, SimClock.stepTime(5L, STEP));
        // A step length that makes no sense is treated as one millisecond rather than dividing by zero.
        assertEquals(42L, SimClock.stepIndex(42L, 0L));
        assertEquals(Long.MAX_VALUE, SimClock.stepTime(Long.MAX_VALUE / 2L, STEP));
    }

    @Test void aSimulationThatNeverRanStartsAtTheCurrentStepWithNothingToReplay() {
        SimClock.Batch batch = SimClock.next(SimClock.NEVER, 1_000L * STEP + 5L, STEP, CAP, PER_TICK);
        assertEquals(0, batch.count());
        assertEquals(1_000L, batch.base());
        assertEquals(1_000L, batch.last());
        assertEquals(0L, batch.skipped());
        assertFalse(batch.catchingUp());
    }

    @Test void nothingIsDueUntilTheStepIndexMovesOn() {
        long now = 1_000L * STEP;
        assertEquals(0, SimClock.next(1_000L, now, STEP, CAP, PER_TICK).count());
        assertEquals(0, SimClock.next(1_000L, now + STEP - 1L, STEP, CAP, PER_TICK).count());
        SimClock.Batch due = SimClock.next(1_000L, now + STEP, STEP, CAP, PER_TICK);
        assertEquals(1, due.count());
        assertEquals(1_001L, due.first());
        assertEquals(1_001L, due.last());
        assertEquals(1_000L, due.base());
        assertFalse(due.catchingUp());
    }

    @Test void atMostAFewStepsAreHandedOutPerTick() {
        // Ten steps behind: two now, eight still waiting.
        SimClock.Batch first = SimClock.next(1_000L, 1_010L * STEP, STEP, CAP, PER_TICK);
        assertEquals(2, first.count());
        assertEquals(1_001L, first.first());
        assertEquals(8L, first.behind());
        assertTrue(first.catchingUp());
        // Tick after tick, every step is handed out exactly once and in order.
        List<Long> ran = new ArrayList<>();
        long last = 1_000L;
        int ticks = 0;
        while (true) {
            SimClock.Batch batch = SimClock.next(last, 1_010L * STEP, STEP, CAP, PER_TICK);
            if (batch.count() == 0) break;
            assertTrue(batch.count() <= PER_TICK);
            for (int index = 0; index < batch.count(); index++) ran.add(batch.first() + index);
            last = batch.last();
            ticks++;
        }
        assertEquals(5, ticks);
        assertEquals(List.of(1_001L, 1_002L, 1_003L, 1_004L, 1_005L, 1_006L, 1_007L, 1_008L, 1_009L, 1_010L), ran);
        // A bound that makes no sense still makes progress, one step at a time.
        assertEquals(1, SimClock.next(1_000L, 1_010L * STEP, STEP, CAP, 0).count());
    }

    @Test void aLongGapIsCutToTheCatchUpCap() {
        // 1,000 steps missed with a cap of 432: 568 are skipped for good.
        SimClock.Batch batch = SimClock.next(5_000L, 6_000L * STEP, STEP, CAP, PER_TICK);
        assertEquals(568L, batch.skipped());
        assertEquals(5_568L, batch.base());
        assertEquals(5_569L, batch.first());
        assertEquals(2, batch.count());
        assertEquals(430L, batch.behind());
        // Replaying from the recorded base: exactly the cap's worth run, and nothing is skipped twice.
        long last = batch.base();
        long ran = 0L;
        long skipped = 0L;
        while (true) {
            SimClock.Batch next = SimClock.next(last, 6_000L * STEP, STEP, CAP, PER_TICK);
            skipped += next.skipped();
            if (next.count() == 0) break;
            ran += next.count();
            last = next.last();
        }
        assertEquals(CAP, ran);
        assertEquals(0L, skipped);
        assertEquals(6_000L, last);
        // Exactly at the cap nothing is skipped.
        assertEquals(0L, SimClock.next(5_000L, 5_432L * STEP, STEP, CAP, PER_TICK).skipped());
        assertEquals(1L, SimClock.next(5_000L, 5_433L * STEP, STEP, CAP, PER_TICK).skipped());
    }

    @Test void aSimulationAheadOfTheClockWaitsAndNeverRunsAStepTwice() {
        // Forced steps took it to 1,005 while the clock is still in step 1,000.
        for (long now = 1_000L; now <= 1_005L; now++) {
            SimClock.Batch waiting = SimClock.next(1_005L, now * STEP, STEP, CAP, PER_TICK);
            assertEquals(0, waiting.count());
            assertEquals(1_005L, waiting.base());
        }
        SimClock.Batch resumed = SimClock.next(1_005L, 1_006L * STEP, STEP, CAP, PER_TICK);
        assertEquals(1, resumed.count());
        assertEquals(1_006L, resumed.first());
    }

    @Test void changingTheStepLengthRebasesTheIndexInsteadOfFloodingOrStalling() {
        long now = 3_000_000L * STEP;
        long last = SimClock.stepIndex(now, STEP);
        // Ten minutes to one minute: the index is ten times larger, and nothing is suddenly due.
        long minute = 60_000L;
        long rebased = SimClock.rebase(last, STEP, minute);
        assertEquals(last * 10L, rebased);
        assertEquals(0, SimClock.next(rebased, now, minute, CAP, PER_TICK).count());
        // One minute back to ten: the index shrinks, and the next ten-minute step runs on time.
        long back = SimClock.rebase(rebased, minute, STEP);
        assertEquals(last, back);
        assertEquals(1, SimClock.next(back, now + STEP, STEP, CAP, PER_TICK).count());
        // Nothing to rebase before the first step, or when the length did not change.
        assertEquals(SimClock.NEVER, SimClock.rebase(SimClock.NEVER, STEP, minute));
        assertEquals(last, SimClock.rebase(last, STEP, STEP));
        assertEquals(last, SimClock.rebase(last, 0L, STEP));
    }

    @Test void clockJumpsNeverRunAStepTwiceAndNeverReplayMoreThanTheCap() {
        // The wall clock misbehaves; the economy clock turns that into time that only moves forward
        // and by at most 72 hours at once, and the simulation follows the economy clock alone.
        EconomyClock clock = new EconomyClock();
        long wall = 1_800_000_000_000L;
        long last = SimClock.next(SimClock.NEVER, clock.observe(wall), STEP, CAP, PER_TICK).base();
        long started = last;
        long highest = last;
        long ranTotal = 0L;
        long skippedTotal = 0L;
        Random random = new Random(3L);
        for (int tick = 0; tick < 20_000; tick++) {
            switch (random.nextInt(400)) {
                case 0 -> wall -= 1L + random.nextInt(86_400_000);             // set back by up to a day
                case 1 -> wall += 30L * 86_400_000L;                           // set a month ahead
                case 2 -> wall += 72L * 3_600_000L + random.nextInt(3_600_000); // a long shutdown
                default -> wall += 50L;                                        // one tick
            }
            long now = clock.observe(wall);
            SimClock.Batch batch = SimClock.next(last, now, STEP, CAP, PER_TICK);
            assertTrue(batch.count() <= PER_TICK, "tick " + tick);
            assertTrue(batch.base() >= last, "tick " + tick);
            for (int index = 0; index < batch.count(); index++) {
                long step = batch.first() + index;
                // Strictly increasing: no step is ever run twice, whatever the wall clock did.
                assertTrue(step > highest, "tick " + tick);
                highest = step;
            }
            ranTotal += batch.count();
            skippedTotal += batch.skipped();
            last = batch.last();
            // The backlog is never more than the cap: the rest was skipped, not queued.
            assertTrue(batch.behind() <= CAP, "tick " + tick);
        }
        assertTrue(ranTotal > 0L);
        // Every step between the start and the last one either ran or was skipped; none is missing.
        assertEquals(last - started, ranTotal + skippedTotal);
    }

    @Test void theSameTimeAlwaysNamesTheSameStepWhateverElseIsGoingOn() {
        // No game tick count, day time or tick rate is an input, so there is nothing to vary but time.
        for (long time : new long[] {0L, 1L, STEP, 7L * STEP + 3L, 1_800_000_000_000L}) {
            SimClock.Batch one = SimClock.next(10L, time, STEP, CAP, PER_TICK);
            SimClock.Batch two = SimClock.next(10L, time, STEP, CAP, PER_TICK);
            assertEquals(one, two);
        }
    }
}
