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
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private EconomyAlerts() { }
    public static void raise(MinecraftServer server, UUID player, String kind, String text,
            LinkAlert.Severity severity) {
        if (server == null || player == null || kind == null || text == null) return;
        String key = player + "|" + kind; long now = System.currentTimeMillis();
        Long prior = LAST.put(key, now); if (prior != null && now - prior < MIN_INTERVAL_MS) return;
        LinkAlerts.get(server).raise(server, player, LinkAlert.raise(
                "neroeconomy:" + kind + ':' + Long.toUnsignedString(now), "neroeconomy", severity, text));
    }
}
