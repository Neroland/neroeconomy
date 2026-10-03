package za.co.neroland.neroeconomy.obligation;

/** Pure auction and job-claim rules, kept free of the game so they are unit tested directly. */
public final class ObligationRules {
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private ObligationRules() { }

    /**
     * The lowest bid an auction will accept next. With no standing bid that is the lot's minimum;
     * otherwise it is the standing bid plus {@code incrementPercent} of it, rounded up and never
     * less than one credit, so a bid always moves the price. Saturates instead of overflowing.
     */
    public static long minimumNextBid(long minimumBid, long highestBid, int incrementPercent) {
        long floor = Math.max(1L, minimumBid);
        if (highestBid <= 0L) return floor;
        int percent = Math.max(0, Math.min(100, incrementPercent));
        // ceil(highestBid * percent / 100) without the intermediate product overflowing.
        long step = highestBid / 100L * percent + (highestBid % 100L * percent + 99L) / 100L;
        step = Math.max(1L, step);
        long next = highestBid > Long.MAX_VALUE - step ? Long.MAX_VALUE : highestBid + step;
        return Math.max(floor, next);
    }

    /**
     * When a claim made at {@code claimedAt} lapses, in economy time; {@link Long#MAX_VALUE} when
     * claims never lapse ({@code timeoutMinutes <= 0}) or the claim has not been stamped yet.
     */
    public static long claimDeadline(long claimedAt, int timeoutMinutes) {
        if (timeoutMinutes <= 0 || claimedAt <= 0L) return Long.MAX_VALUE;
        long window = timeoutMinutes * MILLIS_PER_MINUTE;
        return claimedAt > Long.MAX_VALUE - window ? Long.MAX_VALUE : claimedAt + window;
    }

    /** Whether a claim made at {@code claimedAt} has gone unfulfilled for the whole timeout. */
    public static boolean claimTimedOut(long claimedAt, long now, int timeoutMinutes) {
        return now >= claimDeadline(claimedAt, timeoutMinutes);
    }
}
