package za.co.neroland.neroeconomy.link;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.nerolandcore.link.LinkAlerts;

/**
 * Per-session alert limiter. The limiter's keys do name players, so they are dropped on erasure
 * ({@link #forget}) and when the server unbinds ({@link #clear}).
 *
 * <p><b>Alert text is a closed catalogue.</b> Core stores an alert's text server-side and the
 * companion app shows it as it is, so the text must never carry a player name, an amount, an item
 * or a place. A caller therefore cannot pass text at all: it names a {@link Kind}, and the only
 * wording that can ever be stored is the constant written beside that kind.
 */
public final class EconomyAlerts {
    /** Every alert this mod can raise, with the fixed English sentence stored and shown for it. */
    public enum Kind {
        CONTRACT_FULFILLED("contract_fulfilled", "An economy job was fulfilled."),
        OUTBID("outbid", "You were outbid."),
        AUCTION_WON("auction_won", "You won an auction."),
        SHOP_SOLD_OUT("shop_sold_out", "A player shop sold out."),
        OFFER_RECEIVED("offer_received", "You received a trade offer."),
        OFFER_ACCEPTED("offer_accepted", "A trade offer you sent was accepted."),
        OFFER_EXPIRED("offer_expired", "A trade offer you sent expired and was returned to you."),
        RULE_PAUSED("rule_paused", "One of your auto-trade rules has paused."),
        RULE_FILLED("rule_filled", "One of your auto-trade rules is complete."),
        BOND_MATURED("bond_matured", "A Savings Bond matured and was paid into your balance."),
        DIVIDEND_PAID("dividend_paid", "Your shares paid a dividend."),
        STOCK_DELISTED("stock_delisted", "A company you held shares in left the exchange, and you were paid out."),
        ORDER_FILLED("order_filled", "One of your market orders was filled."),
        VAULT_FULL("vault_full", "Your vault is full. Take some items out before more can arrive.");

        private final String id;
        private final String text;

        Kind(String id, String text) {
            this.id = id;
            this.text = text;
        }

        /** Stable id suffix; see {@link EconomyAlerts#alertId}. */
        public String id() { return id; }

        /** The sentence stored and shown; a constant, so nothing about a player or a trade can reach it. */
        public String text() { return text; }

        /** The kind with this {@link #id()}, or null. */
        public static Kind byId(String id) {
            for (Kind kind : values()) if (kind.id.equals(id)) return kind;
            return null;
        }
    }

    private static final long MIN_INTERVAL_MS = 30_000L;
    private static final int MAX_TRACKED_KEYS = 256;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    /** Told of every alert that went out, so the companion-app module can publish its alerts topic. */
    private static volatile java.util.function.BiConsumer<UUID, Kind> listener = (player, kind) -> { };

    private EconomyAlerts() { }

    /** Sets the one listener told of each alert that goes out; null removes it. */
    static void listen(java.util.function.BiConsumer<UUID, Kind> next) {
        listener = next == null ? (player, kind) -> { } : next;
    }

    public static void raise(MinecraftServer server, UUID player, Kind kind, LinkAlert.Severity severity) {
        if (server == null || player == null || kind == null) return;
        // A rate limiter measures real elapsed time, so this is the wall clock, not economy time.
        long now = System.currentTimeMillis();
        if (!allow(key(player, kind.id()), now)) return;
        LinkAlerts.get(server).raise(server, player,
                LinkAlert.raise(alertId(kind.id()), "neroeconomy", severity, kind.text()));
        try {
            listener.accept(player, kind);
        } catch (RuntimeException ignored) {
            // A missed live event is harmless; the alert itself is stored.
        }
    }

    /**
     * One id per kind, with no timestamp: Core stores alerts by id, so a new alert of a kind
     * replaces the player's previous one instead of growing their list without bound.
     */
    static String alertId(String kind) { return "neroeconomy:" + kind; }

    private static String key(UUID player, String kind) { return player + "|" + kind; }

    /** Erasure hook: drops every limiter entry for one player. Unknown players are a no-op. */
    public static void forget(UUID player) {
        if (player == null) return;
        String prefix = player + "|";
        LAST.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Drops the whole limiter; called when the server unbinds so nothing leaks into the next world. */
    public static void clear() { LAST.clear(); }

    /** Test seam: whether the limiter currently holds an entry for this player and kind. */
    static boolean tracked(UUID player, String kind) { return LAST.containsKey(key(player, kind)); }

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
