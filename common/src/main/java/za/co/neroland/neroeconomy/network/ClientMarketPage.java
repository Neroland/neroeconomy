package za.co.neroland.neroeconomy.network;

/**
 * Client-only mailbox for the newest market page; contains no player identity. It holds one page at
 * most: a newer page replaces the last, the screen takes it out, and closing the screen empties it,
 * so nothing a closed terminal was sent lingers.
 */
public final class ClientMarketPage {
    private static MarketPagePayload latest;
    private ClientMarketPage() { }
    public static synchronized void accept(MarketPagePayload value) { latest = value; }
    /** Takes the waiting page if it is addressed to {@code containerId}; null otherwise. */
    public static synchronized MarketPagePayload poll(int containerId) {
        MarketPagePayload value = latest;
        if (value == null || value.containerId() != containerId) return null;
        latest = null;
        return value;
    }
    /** Screen close: drops whatever is waiting, whichever container it was for. */
    public static synchronized void clear() { latest = null; }
}
