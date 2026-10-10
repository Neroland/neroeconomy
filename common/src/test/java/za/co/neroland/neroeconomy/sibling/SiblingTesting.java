package za.co.neroland.neroeconomy.sibling;

import java.util.function.LongSupplier;

import za.co.neroland.neroeconomy.network.RateLimiter;

/** What a test outside this package needs from the cross-mod API's seams. */
public final class SiblingTesting {
    private SiblingTesting() { }

    /** Puts the per-caller rate limit on a nanosecond clock the test controls; null restores the real one. */
    public static void rateClock(LongSupplier nanos) {
        SiblingPayments.limiterForTest(nanos == null ? null : new RateLimiter(nanos));
    }
}
