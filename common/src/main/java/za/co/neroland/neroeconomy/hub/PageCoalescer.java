package za.co.neroland.neroeconomy.hub;

import java.util.function.LongSupplier;

/**
 * Decides when an open hub sends its page again. A page goes out at most once per server tick, and
 * only when something asked for it (a tab switch, a search, an action: {@link #force}) or when the
 * data behind it changed, which is read as a signature: the order book's revision, the vault's
 * revision, a balance. Nothing changed means nothing is sent, however long the hub stays open.
 *
 * <p>The signature is only worked out every {@code interval} ticks unless a push is already
 * forced, so a tab whose signature costs a lookup does not pay for it every tick. Pure Java.
 */
public final class PageCoalescer {
    private long lastTick = Long.MIN_VALUE;
    private long lastSignature;
    private boolean sentOnce;
    private boolean forced = true;

    /** The next check sends the page whatever the signature says (still at most once a tick). */
    public void force() { forced = true; }

    /** Whether a push is waiting for its tick. */
    public boolean forced() { return forced; }

    /**
     * @param tick      the server tick now
     * @param interval  how many ticks apart the signature is read when nothing is forced; 1 = every tick
     * @param signature reads what the page depends on; only called when it may matter
     * @return true when the page must be sent now
     */
    public boolean due(long tick, int interval, LongSupplier signature) {
        if (tick == lastTick) return false;
        boolean check = forced || !sentOnce || interval <= 1 || Math.floorMod(tick, (long) interval) == 0L;
        if (!check) return false;
        long current = signature.getAsLong();
        if (!forced && sentOnce && current == lastSignature) return false;
        lastTick = tick;
        lastSignature = current;
        sentOnce = true;
        forced = false;
        return true;
    }
}
