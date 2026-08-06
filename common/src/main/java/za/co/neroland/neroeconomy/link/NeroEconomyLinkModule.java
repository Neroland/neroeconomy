package za.co.neroland.neroeconomy.link;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.platform.Services;

/** Player-scoped NeroLink module; the bridge stores no economy state. */
public final class NeroEconomyLinkModule implements LinkSnapshotProvider, LinkActionHandler {
    public static final String MODULE_ID = "neroeconomy";
    public static final int SCHEMA_VERSION = 1;
    private static final List<String> SECTIONS = List.of("balance", "transactions", "market");
    private static final List<String> ACTIONS = List.of("trade");

    public static void register() {
        NeroEconomyLinkModule module = new NeroEconomyLinkModule();
        LinkModuleInfo info = new LinkModuleInfo(MODULE_ID, Services.PLATFORM.getModVersion(), SCHEMA_VERSION,
                SECTIONS, ACTIONS);
        NeroLinkRegistry.registerSnapshotProvider(module, info);
        NeroLinkRegistry.registerActionHandler(module, info);
    }

    @Override public String moduleId() { return MODULE_ID; }
    @Override public int schemaVersion() { return SCHEMA_VERSION; }
    @Override public List<String> sections() { return SECTIONS; }
    @Override public List<String> actionIds() { return ACTIONS; }

    @Override public JsonObject snapshot(UUID playerId, String section, Map<String, String> params) {
        if (playerId == null || section == null) return new JsonObject();
        return switch (section) {
            case "balance" -> balance(playerId);
            case "transactions" -> transactions(playerId, params);
            case "market" -> market(playerId, params);
            default -> new JsonObject();
        };
    }

    private JsonObject balance(UUID player) {
        var data = NeroEconomyCommon.CURRENCY_PROVIDER.activeData();
        JsonObject out = new JsonObject();
        out.addProperty("currency", NeroEconomyCommon.CREDITS.id().toString());
        out.addProperty("balance", data.getBalance(player, NeroEconomyCommon.CREDITS));
        JsonArray trend = new JsonArray();
        data.history(player, 10).forEach(row -> {
            JsonObject point = new JsonObject(); point.addProperty("at", row.timestamp());
            point.addProperty("amount", row.amount()); point.addProperty("reason", row.type().name()); trend.add(point);
        });
        out.add("recentTrend", trend); return out;
    }

    private JsonObject transactions(UUID player, Map<String, String> params) {
        int cursor = parseBounded(params.get("cursor"), 0, 0, 1_000_000);
        int limit = parseBounded(params.get("limit"), 25, 1, 50);
        var rows = NeroEconomyCommon.CURRENCY_PROVIDER.activeData().history(player,
                Math.min(EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get(), cursor + limit));
        JsonArray values = new JsonArray();
        rows.stream().skip(cursor).limit(limit).forEach(row -> {
            JsonObject value = new JsonObject(); value.addProperty("reason", row.type().name());
            value.addProperty("amount", row.amount()); value.addProperty("currency", row.currency());
            value.addProperty("timestamp", row.timestamp()); values.add(value);
        });
        JsonObject out = new JsonObject(); out.add("items", values);
        out.addProperty("nextCursor", cursor + values.size() < rows.size() ? cursor + values.size() : -1); return out;
    }

    private JsonObject market(UUID player, Map<String, String> params) {
        String query = params.getOrDefault("q", ""); if (query.length() > 64) query = query.substring(0, 64);
        int page = parseBounded(params.get("page"), 0, 0, 10_000);
        MarketSavedData store = MarketSavedData.get(server()); JsonArray values = new JsonArray();
        store.search(query, page, 25, System.currentTimeMillis()).forEach(listing -> {
            JsonObject value = new JsonObject(); value.addProperty("listingId", listing.listingId().toString());
            value.addProperty("item", listing.itemId().toString()); value.addProperty("stock", listing.availableQuantity());
            value.addProperty("unitPrice", za.co.neroland.neroeconomy.api.PricingEngine.quote(listing, player).unitPrice());
            values.add(value);
        });
        JsonObject out = new JsonObject(); out.add("items", values); out.addProperty("page", page); return out;
    }

    @Override public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        if (!"trade".equals(actionId) || !EconomyConfig.REMOTE_TRADE_ENABLED.get())
            return LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, "Remote trade disabled");
        ServerPlayer player = server().getPlayerList().getPlayer(playerId);
        if (player == null) return LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED,
                "Trading requires the player online");
        String op = string(params, "op");
        try {
            if ("buy".equals(op)) {
                UUID listing = UUID.fromString(string(params, "listingId"));
                UUID request = UUID.fromString(string(params, "requestId"));
                int quantity = integer(params, "qty", 1, 64); long quoted = longValue(params, "quotedUnitPrice", 1L);
                PurchaseResult result = MarketSavedData.get(server()).purchase(player, listing, quantity, quoted, request);
                if (result != PurchaseResult.SUCCESS) return LinkActionResult.error(
                        LinkActionResult.Error.VALIDATION, result.name());
            } else if ("sell".equals(op)) {
                UUID request = UUID.fromString(string(params, "requestId"));
                int quantity = integer(params, "qty", 1, 64); long price = longValue(params, "unitPrice", 1L);
                long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
                if (MarketSavedData.get(server()).postFromHand(player, quantity, price, expires,
                        ListingSource.MARKET_BOARD, "link", request) == null)
                    return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Offer rejected");
            } else return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Unknown trade operation");
            return LinkActionResult.ok(balance(playerId));
        } catch (RuntimeException invalid) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Invalid trade parameters");
        }
    }

    private static MinecraftServer server() { return NeroEconomyCommon.CURRENCY_PROVIDER.activeServer(); }
    private static String string(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
    private static int integer(JsonObject value, String key, int min, int max) {
        int result = value.get(key).getAsInt(); if (result < min || result > max) throw new IllegalArgumentException(key); return result;
    }
    private static long longValue(JsonObject value, String key, long min) {
        long result = value.get(key).getAsLong(); if (result < min) throw new IllegalArgumentException(key); return result;
    }
    private static int parseBounded(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
