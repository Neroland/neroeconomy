package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Pages go out at most once a tick, and otherwise only when what they show has changed. */
class PageCoalescerTest {
    @Test void theFirstPageGoesOutAtOnce() {
        assertTrue(new PageCoalescer().due(5L, 20, () -> 1L));
    }

    @Test void neverTwiceInOneTickEvenWhenForced() {
        PageCoalescer coalescer = new PageCoalescer();
        assertTrue(coalescer.due(5L, 1, () -> 1L));
        coalescer.force();
        assertFalse(coalescer.due(5L, 1, () -> 2L));
        assertTrue(coalescer.due(6L, 1, () -> 2L));
    }

    @Test void anUnchangedRevisionSendsNothing() {
        PageCoalescer coalescer = new PageCoalescer();
        AtomicLong revision = new AtomicLong(4L);
        assertTrue(coalescer.due(0L, 1, revision::get));
        for (long tick = 1L; tick < 200L; tick++) assertFalse(coalescer.due(tick, 1, revision::get));
        revision.incrementAndGet();
        assertTrue(coalescer.due(200L, 1, revision::get));
        assertFalse(coalescer.due(201L, 1, revision::get));
    }

    @Test void betweenChecksTheSignatureIsNotEvenLookedAt() {
        PageCoalescer coalescer = new PageCoalescer();
        assertTrue(coalescer.due(0L, 20, () -> 1L));
        AtomicLong looks = new AtomicLong();
        for (long tick = 1L; tick < 20L; tick++)
            assertFalse(coalescer.due(tick, 20, () -> { looks.incrementAndGet(); return 2L; }));
        assertTrue(looks.get() == 0L);
        assertTrue(coalescer.due(20L, 20, () -> 2L));
    }

    @Test void aForcedPushGoesOutWithoutAChangeAndClearsItself() {
        PageCoalescer coalescer = new PageCoalescer();
        assertTrue(coalescer.due(0L, 20, () -> 1L));
        coalescer.force();
        assertTrue(coalescer.forced());
        assertTrue(coalescer.due(3L, 20, () -> 1L));
        assertFalse(coalescer.forced());
        assertFalse(coalescer.due(4L, 20, () -> 1L));
    }
}
