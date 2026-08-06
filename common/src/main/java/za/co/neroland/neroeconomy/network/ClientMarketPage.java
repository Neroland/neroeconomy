package za.co.neroland.neroeconomy.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-only mailbox keyed by open container id; contains no player identity. */
public final class ClientMarketPage {
    private static final Map<Integer, MarketPagePayload> PAGES = new ConcurrentHashMap<>();
    private ClientMarketPage() { }
    public static void accept(MarketPagePayload value) { PAGES.put(value.containerId(), value); }
    public static MarketPagePayload poll(int containerId) { return PAGES.remove(containerId); }
}
