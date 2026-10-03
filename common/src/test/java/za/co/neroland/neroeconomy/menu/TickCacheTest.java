package za.co.neroland.neroeconomy.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TickCacheTest {
    @Test void aValueIsComputedOncePerTickHoweverOftenItIsRead() {
        AtomicInteger computed = new AtomicInteger();
        TickCache<Long> cache = new TickCache<>();
        // One menu sync reads four slots for a 64-bit value: one lookup, not four.
        for (int slot = 0; slot < 4; slot++) assertEquals(500L, cache.get(7L, () -> { computed.incrementAndGet(); return 500L; }));
        assertEquals(1, computed.get());
    }

    @Test void theNextTickComputesAgain() {
        AtomicInteger computed = new AtomicInteger();
        TickCache<Integer> cache = new TickCache<>();
        assertEquals(1, cache.get(1L, computed::incrementAndGet));
        assertEquals(1, cache.get(1L, computed::incrementAndGet));
        assertEquals(2, cache.get(2L, computed::incrementAndGet));
    }

    @Test void tickZeroIsNotMistakenForAFilledCache() {
        TickCache<String> cache = new TickCache<>();
        assertEquals("first", cache.get(0L, () -> "first"));
        assertEquals("first", cache.get(0L, () -> "second"));
    }

    @Test void invalidatingForcesARecomputeWithinTheSameTick() {
        TickCache<String> cache = new TickCache<>();
        assertEquals("old", cache.get(3L, () -> "old"));
        cache.invalidate();
        assertEquals("new", cache.get(3L, () -> "new"));
    }
}
