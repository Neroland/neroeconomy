package za.co.neroland.neroeconomy.link;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.nerolandcore.link.LinkAlerts;

/** Per-session alert limiter; payload text is fixed gameplay state and never personal data. */
public final class EconomyAlerts {
    private static final long MIN_INTERVAL_MS = 30_000L;
    private static final int MAX_TRACKED_KEYS = 256;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private EconomyAlerts() { }
    public static void raise(MinecraftServer server, UUID player, String kind, String text,
            LinkAlert.Severity severity) {
        if (server == null || player == null || kind == null || text == null) return;
        long now = System.currentTimeMillis();
        if (!allow(player + "|" + kind, now)) return;
        LinkAlerts.get(server).raise(server, player, LinkAlert.raise(
                "neroeconomy:" + kind + ':' + Long.toUnsignedString(now), "neroeconomy", severity, text));
    }

    /**
     * Decides whether an alert may be sent and records the send. The timestamp is written only when
     * the alert actually goes out: stamping every suppressed attempt would keep pushing the window
     * forward, so a continuously active shop or auction would silence its channel permanently.
     */
    static boolean allow(String key, long now) {
        Long prior = LAST.get(key);
        if (prior != null && now - prior < MIN_INTERVAL_MS && now >= prior) return false;
        if (LAST.size() > MAX_TRACKED_KEYS) LAST.values().removeIf(value -> now - value >= MIN_INTERVAL_MS);
        LAST.put(key, now);
        return true;
    }
}
