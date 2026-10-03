package za.co.neroland.neroeconomy.network;

/**
 * Client-only mailbox for the newest Auto-Trader rule state; contains no player identity. It holds
 * one state at most: a newer one replaces the last, the screen takes it out, and closing the
 * screen empties it, so nothing a closed Auto-Trader was sent lingers.
 */
public final class ClientAutoTrader {
    private static AutoTraderStatePayload latest;

    private ClientAutoTrader() { }

    public static synchronized void accept(AutoTraderStatePayload value) { latest = value; }

    /** Takes the waiting state if it is addressed to {@code containerId}; null otherwise. */
    public static synchronized AutoTraderStatePayload poll(int containerId) {
        AutoTraderStatePayload value = latest;
        if (value == null || value.containerId() != containerId) return null;
        latest = null;
        return value;
    }

    /** Screen close: drops whatever is waiting, whichever container it was for. */
    public static synchronized void clear() { latest = null; }
}
