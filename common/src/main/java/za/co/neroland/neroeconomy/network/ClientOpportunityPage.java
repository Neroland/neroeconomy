package za.co.neroland.neroeconomy.network;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
public final class ClientOpportunityPage {
    private static final Map<Integer, OpportunityPagePayload> PAGES = new ConcurrentHashMap<>();
    private ClientOpportunityPage() { }
    public static void accept(OpportunityPagePayload value) { PAGES.put(value.containerId(), value); }
    public static OpportunityPagePayload poll(int id) { return PAGES.remove(id); }
}
