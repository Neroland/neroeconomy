package za.co.neroland.neroeconomy.network;

import za.co.neroland.neroeconomy.hub.HubPage;

/**
 * Client-only mailbox for the newest hub page; it holds no player identity. One page at most: a
 * newer page replaces the last, the screen takes it out, and closing the screen empties it.
 */
public final class ClientHubPage {
    private static HubPage latest;
    private ClientHubPage() { }
    public static synchronized void accept(HubPagePayload value) { latest = value == null ? null : value.page(); }
    /** Takes the waiting page if it is addressed to {@code containerId}; null otherwise. */
    public static synchronized HubPage poll(int containerId) {
        HubPage value = latest;
        if (value == null || value.containerId() != containerId) return null;
        latest = null;
        return value;
    }
    /** Screen close: drops whatever is waiting. */
    public static synchronized void clear() { latest = null; }
}
