package za.co.neroland.neroeconomy.link;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
import za.co.neroland.neroeconomy.api.PricingEngine;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.platform.Services;

/**
 * Player-scoped NeroLink module; the bridge stores no economy state.
 *
 * <p><b>Threading.</b> The bridge calls in from its own thread. Every snapshot and every action is
 * therefore executed on the server thread and the result is handed back, because reads such as a
 * market search expire listings and actions move goods and credits.
 *
 * <p><b>Action schema</b> — {@code trade}, one object:
 * <ul>
 *   <li>{@code op} (required): {@code "buy"} or {@code "sell"}.</li>
 *   <li>{@code listingId} (buy, required): listing UUID.</li>
 *   <li>{@code qty} (optional, default 1, 1-64).</li>
 *   <li>{@code unitPrice} (sell, required): asking price per item, at least 1.</li>
 *   <li>{@code quotedUnitPrice} (buy, optional): the price the client displayed. Omitting it makes
 *       the server quote at execution instead of rejecting the trade.</li>
 *   <li>{@code requestId} (optional): client UUID for idempotent retries; generated when absent, in
 *       which case a retry is a second trade.</li>
 * </ul>
 * A sell always sources the player's main-hand stack, so it requires the player to be online.
 */
public final class NeroEconomyLinkModule implements LinkSnapshotProvider, LinkActionHandler {
    public static final String MODULE_ID = "neroeconomy";
    public static final int SCHEMA_VERSION = 1;
    private static final List<String> SECTIONS = List.of("balance", "transactions", "market");
    private static final List<String> ACTIONS = List.of("trade");
    private static final int MAX_TRADE_QUANTITY = 64;

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
        MinecraftServer server = serverOrNull();
        if (playerId == null || section == null || server == null) return new JsonObject();
        Map<String, String> safeParams = params == null ? Map.of() : params;
        Supplier<JsonObject> task = () -> switch (section) {
            case "balance" -> balance(playerId);
            case "transactions" -> transactions(playerId, safeParams);
            case "market" -> market(server, playerId, safeParams);
            default -> new JsonObject();
        };
        return onServerThread(server, task, new JsonObject());
    }

    private JsonObject balance(UUID player) {
        EconomySavedData data = dataOrNull();
        JsonObject out = new JsonObject();
        out.addProperty("currency", NeroEconomyCommon.CREDITS.id().toString());
        if (data == null) return out;
        out.addProperty("balance", data.getBalance(player, NeroEconomyCommon.CREDITS));
        JsonArray trend = new JsonArray();
        data.history(player, 10).forEach(row -> {
            JsonObject point = new JsonObject(); point.addProperty("at", row.timestamp());
            point.addProperty("amount", row.amount()); point.addProperty("reason", row.type().name()); trend.add(point);
        });
        out.add("recentTrend", trend); return out;
    }

    private JsonObject transactions(UUID player, Map<String, String> params) {
        JsonObject out = new JsonObject();
        EconomySavedData data = dataOrNull();
        if (data == null) { out.add("items", new JsonArray()); out.addProperty("nextCursor", -1); return out; }
        int cursor = parseBounded(params.get("cursor"), 0, 0, 1_000_000);
        int limit = parseBounded(params.get("limit"), 25, 1, 50);
        var rows = data.history(player, Math.min(EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get(), cursor + limit));
        JsonArray values = new JsonArray();
        rows.stream().skip(cursor).limit(limit).forEach(row -> {
            JsonObject value = new JsonObject(); value.addProperty("reason", row.type().name());
            value.addProperty("amount", row.amount()); value.addProperty("currency", row.currency());
            value.addProperty("timestamp", row.timestamp()); values.add(value);
        });
        out.add("items", values);
        out.addProperty("nextCursor", cursor + values.size() < rows.size() ? cursor + values.size() : -1); return out;
    }

    private JsonObject market(MinecraftServer server, UUID player, Map<String, String> params) {
        String query = params.getOrDefault("q", ""); if (query.length() > 64) query = query.substring(0, 64);
        int page = parseBounded(params.get("page"), 0, 0, 10_000);
        MarketSavedData store = MarketSavedData.get(server); JsonArray values = new JsonArray();
        store.search(query, page, 25, System.currentTimeMillis()).forEach(listing -> {
            JsonObject value = new JsonObject(); value.addProperty("listingId", listing.listingId().toString());
            value.addProperty("item", listing.itemId().toString()); value.addProperty("stock", listing.availableQuantity());
            value.addProperty("unitPrice", PricingEngine.quote(listing, player).unitPrice());
            values.add(value);
        });
        JsonObject out = new JsonObject(); out.add("items", values); out.addProperty("page", page); return out;
    }

    @Override public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        if (!"trade".equals(actionId) || !EconomyConfig.REMOTE_TRADE_ENABLED.get())
            return LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, "Remote trade disabled");
        MinecraftServer server = serverOrNull();
        if (server == null || playerId == null)
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "No active server");
        JsonObject safeParams = params == null ? new JsonObject() : params;
        Supplier<LinkActionResult> task = () -> trade(server, playerId, safeParams);
        return onServerThread(server, task,
                LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Trade could not be scheduled"));
    }

    private LinkActionResult trade(MinecraftServer server, UUID playerId, JsonObject params) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED,
                "Trading requires the player online");
        String op = string(params, "op");
        MarketSavedData store = MarketSavedData.get(server);
        try {
            int quantity = integer(params, "qty", 1, 1, MAX_TRADE_QUANTITY);
            UUID request = uuid(params, "requestId", UUID.randomUUID());
            if ("buy".equals(op)) {
                UUID listingId = uuid(params, "listingId", null);
                if (listingId == null) return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "listingId");
                long quoted = longValue(params, "quotedUnitPrice", 0L);
                if (quoted < 1L) {
                    // No client quote: price it here rather than refusing the trade outright.
                    MarketListing listing = store.listing(listingId);
                    if (listing == null) return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                            PurchaseResult.NOT_FOUND.name());
                    quoted = PricingEngine.quote(listing, playerId).unitPrice();
                }
                PurchaseResult result = store.purchase(player, listingId, quantity, quoted, request);
                if (result != PurchaseResult.SUCCESS) return LinkActionResult.error(
                        LinkActionResult.Error.VALIDATION, result.name());
            } else if ("sell".equals(op)) {
                long price = longValue(params, "unitPrice", 0L);
                if (price < 1L) return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "unitPrice");
                long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
                if (store.postFromHand(player, quantity, price, expires, ListingSource.MARKET_BOARD, "link",
                        request) == null)
                    return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Offer rejected");
            } else return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Unknown trade operation");
            return LinkActionResult.ok(balance(playerId));
        } catch (RuntimeException invalid) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "Invalid trade parameters");
        }
    }

    /** Runs on the server thread and blocks for the answer; inline when already on that thread. */
    private static <T> T onServerThread(MinecraftServer server, Supplier<T> task, T fallback) {
        try { return server.submit(task).join(); }
        catch (RuntimeException failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Link request failed on the server thread.");
            return fallback;
        }
    }

    private static MinecraftServer serverOrNull() {
        try { return NeroEconomyCommon.CURRENCY_PROVIDER.activeServer(); }
        catch (IllegalStateException unbound) { return null; }
    }
    private static EconomySavedData dataOrNull() {
        try { return NeroEconomyCommon.CURRENCY_PROVIDER.activeData(); }
        catch (IllegalStateException unbound) { return null; }
    }
    private static String string(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
    private static UUID uuid(JsonObject value, String key, UUID fallback) {
        String raw = string(value, key);
        if (raw.isEmpty()) return fallback;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException invalid) { return fallback; }
    }
    private static int integer(JsonObject value, String key, int fallback, int min, int max) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) return fallback;
        int result = value.get(key).getAsInt();
        if (result < min || result > max) throw new IllegalArgumentException(key);
        return result;
    }
    private static long longValue(JsonObject value, String key, long fallback) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) return fallback;
        return value.get(key).getAsLong();
    }
    private static int parseBounded(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
