package za.co.neroland.neroeconomy.hub;

/**
 * A block entity the Economy Hub can be opened at: the Bank Terminal, the Trading Terminal, the
 * Market Board, the Exchange Terminal and the Merchant Stall. Each opens the hub at its own tab,
 * and a hub opened there stays valid only while its player stays at it.
 */
public interface HubAnchor {
    /** The tab the hub opens at when this block is used. */
    HubTab hubTab();

    /** The Merchant House the hub's market is filtered to here; null for none. */
    default String houseFilter() { return null; }
}
