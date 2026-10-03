package za.co.neroland.neroeconomy.network;

/**
 * Client-only mailbox for the newest jobs/auctions page, stamped with when it arrived so the screen
 * can count its timers down from receipt. Holds one page at most and is emptied when the screen
 * closes.
 */
public final class ClientOpportunityPage {
    /** A page and the client's monotonic clock reading ({@code System.nanoTime()}) when it arrived. */
    public record Received(OpportunityPagePayload page, long receivedNanos) { }

    private static Received latest;
    private ClientOpportunityPage() { }
    public static synchronized void accept(OpportunityPagePayload value) {
        latest = new Received(value, System.nanoTime());
    }
    /** Takes the waiting page if it is addressed to {@code containerId}; null otherwise. */
    public static synchronized Received poll(int containerId) {
        Received value = latest;
        if (value == null || value.page().containerId() != containerId) return null;
        latest = null;
        return value;
    }
    /** Screen close: drops whatever is waiting, whichever container it was for. */
    public static synchronized void clear() { latest = null; }
}
