package za.co.neroland.neroeconomy.link;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.economy.CurrencyEvents;
import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.nerolandcore.link.LinkEvent;
import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.market.FillOutcome;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketBuyOffer;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.market.Posted;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.platform.Services;

/**
 * Player-scoped NeroLink module; the bridge stores no economy state. This class only gathers values
 * from the stores and runs trades: every JSON shape is built by {@link LinkPayloads}.
 *
 * <p><b>Threading.</b> Core's contract is that a snapshot provider and an action handler are called
 * on the server thread, and the bridge does so for both its REST and its WebSocket paths. The work
 * therefore runs inline. A guard remains for a caller that breaks the contract, because a market
 * read expires listings and a trade moves goods and credits: off the server thread the work is
 * handed to it and awaited for a bounded time, never run where it was called.
 *
 * <p><b>Sections</b> — {@code balance}, {@code transactions} ({@code cursor}, {@code limit}) and
 * {@code market} ({@code q}, {@code page}). Each read takes one token from the player's
 * {@code QUERY} budget; over budget, the last snapshot served for the same query comes back with
 * {@code "throttled": true}.
 *
 * <p><b>Action</b> — {@code trade}, one object, taking one token from the {@code VALUE} budget:
 * <ul>
 *   <li>{@code requestId} (required): a UUID. A missing or malformed one is refused, and one that
 *       was already acted on in the last ten minutes is refused as a duplicate.</li>
 *   <li>{@code op} (required): {@code "buy"} or {@code "sell"}.</li>
 *   <li>{@code listingId} (required unless posting): the market row's id.</li>
 *   <li>{@code qty} (optional, default 1): a whole number from 1 to 64.</li>
 *   <li>{@code quotedUnitPrice} (buy, optional): the most the buyer agreed to pay per item. Without
 *       it the server quotes at execution.</li>
 *   <li>{@code unitPrice} (sell, optional): given, the main-hand stack is posted as a new Market
 *       Board listing at that price; absent, {@code listingId} must name a standing buy offer, which
 *       is filled from the main hand.</li>
 * </ul>
 * A buy works while the player is logged out: the goods are delivered to their vault and wait at a
 * Bank Terminal (the result still names that delivery {@code "claim"}, the schema 1 word). A sell always
 * takes the main-hand stack, so it needs the player in game.
 *
 * <p><b>Event</b> — topic {@code balance}, player-scoped, carrying the balance object; see
 * {@link BalancePulse} for when one is published.
 */
public final class NeroEconomyLinkModule implements LinkSnapshotProvider, LinkActionHandler {
    public static final String MODULE_ID = "neroeconomy";
    public static final int SCHEMA_VERSION = 1;
    private static final String BALANCE = "balance";
    private static final String TRANSACTIONS = "transactions";
    private static final String MARKET = "market";
    private static final String TRADE = "trade";
    private static final List<String> SECTIONS = List.of(BALANCE, TRANSACTIONS, MARKET);
    private static final List<String> ACTIONS = List.of(TRADE);
    private static final int MAX_QUERY_LENGTH = 64;
    /** How long a caller off the server thread waits for the server thread to do the work. */
    private static final long MARSHAL_TIMEOUT_SECONDS = 5L;

    private static final LinkSnapshotCache CACHE = new LinkSnapshotCache();
    private static final BalancePulse PULSE = new BalancePulse();

    public static void register() {
        NeroEconomyLinkModule module = new NeroEconomyLinkModule();
        LinkModuleInfo info = new LinkModuleInfo(MODULE_ID, Services.PLATFORM.getModVersion(), SCHEMA_VERSION,
                SECTIONS, ACTIONS);
        NeroLinkRegistry.registerSnapshotProvider(module, info);
        NeroLinkRegistry.registerActionHandler(module, info);
        // Live balance: Core reports every change, the economy tick publishes what is due.
        CurrencyEvents.onChange(NeroEconomyLinkModule::onBalanceChange);
        NeroEconomyCommon.CURRENCY_PROVIDER.setLifecycleHooks(NeroEconomyLinkModule::publishDueBalances,
                NeroEconomyLinkModule::reset);
        PlayerDataErasure.register((server, player) -> forget(player));
    }

    @Override public String moduleId() { return MODULE_ID; }
    @Override public int schemaVersion() { return SCHEMA_VERSION; }
    @Override public List<String> sections() { return SECTIONS; }
    @Override public List<String> actionIds() { return ACTIONS; }

    /** The handler decides per operation: a buy may run for a logged-out player, a sell may not. */
    @Override public boolean allowOffline(String actionId) { return TRADE.equals(actionId); }

    // --- snapshots ---------------------------------------------------------------------------

    @Override public JsonObject snapshot(UUID playerId, String section, Map<String, String> params) {
        MinecraftServer server = serverOrNull();
        if (playerId == null || section == null || server == null || !SECTIONS.contains(section))
            return new JsonObject();
        Map<String, String> safeParams = params == null ? Map.of() : params;
        return onServerThread(server, () -> section(server, playerId, section, safeParams), new JsonObject());
    }

    private static JsonObject section(MinecraftServer server, UUID player, String section,
            Map<String, String> params) {
        PULSE.interested(player, System.nanoTime());
        String query = "";
        int page = 0;
        int cursor = 0;
        int limit = LinkPayloads.DEFAULT_PAGE_SIZE;
        if (MARKET.equals(section)) {
            query = params.getOrDefault("q", "");
            if (query == null) query = "";
            if (query.length() > MAX_QUERY_LENGTH) query = query.substring(0, MAX_QUERY_LENGTH);
            // The store matches item ids. Someone typing a name types spaces where an id has underscores.
            query = query.strip().replace(' ', '_');
            page = LinkPayloads.bounded(params.get("page"), 0, 0, 10_000);
        } else if (TRANSACTIONS.equals(section)) {
            cursor = LinkPayloads.bounded(params.get("cursor"), 0, 0, 1_000_000);
            limit = LinkPayloads.bounded(params.get("limit"), LinkPayloads.DEFAULT_PAGE_SIZE, 1,
                    LinkPayloads.MAX_PAGE_SIZE);
        }
        String variant = variant(query, page, cursor, limit);
        if (!IntentGuard.withinBudget(player, RateLimiter.Budget.QUERY)) {
            JsonObject last = CACHE.get(player, section, variant);
            return LinkPayloads.throttled(last != null ? last : placeholder(player, section, page));
        }
        JsonObject fresh = switch (section) {
            case BALANCE -> balance(player);
            case TRANSACTIONS -> transactions(player, cursor, limit);
            default -> market(server, player, query, page);
        };
        CACHE.put(player, section, variant, fresh);
        return fresh;
    }

    /** The cache key for one section's query; a section that takes no parameters always has the same one. */
    private static String variant(String query, int page, int cursor, int limit) {
        return query.toLowerCase(Locale.ROOT).strip() + '|' + page + '|' + cursor + '|' + limit;
    }

    /**
     * What an over-budget player gets when nothing was served for this query before. It does no
     * search and no ledger scan; the balance itself is one map lookup, so it is still the real one.
     */
    private static JsonObject placeholder(UUID player, String section, int page) {
        long asOf = wallNow();
        if (TRANSACTIONS.equals(section)) return LinkPayloads.transactions(List.of(), 0, 1, asOf);
        if (MARKET.equals(section))
            return LinkPayloads.market(List.of(), List.of(), page, LinkPayloads.DEFAULT_PAGE_SIZE, 0, 0, asOf);
        EconomySavedData data = dataOrNull();
        return LinkPayloads.balance(NeroEconomyCommon.CREDITS.id().toString(),
                data == null ? 0L : data.getBalance(player, NeroEconomyCommon.CREDITS), List.of(), asOf);
    }

    private static JsonObject balance(UUID player) {
        EconomySavedData data = dataOrNull();
        if (data == null) {
            JsonObject out = new JsonObject();
            out.addProperty("currency", NeroEconomyCommon.CREDITS.id().toString());
            return out;
        }
        return LinkPayloads.balance(NeroEconomyCommon.CREDITS.id().toString(),
                data.getBalance(player, NeroEconomyCommon.CREDITS),
                entries(data, player, LinkPayloads.HISTORY_POINTS), wallNow());
    }

    private static JsonObject transactions(UUID player, int cursor, int limit) {
        EconomySavedData data = dataOrNull();
        List<LinkPayloads.Entry> rows = data == null ? List.of()
                : entries(data, player, EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get());
        return LinkPayloads.transactions(rows, cursor, limit, wallNow());
    }

    /** The subject's own rows, direction worked out by the ledger; no account reference leaves it. */
    private static List<LinkPayloads.Entry> entries(EconomySavedData data, UUID player, int limit) {
        return data.subjectHistory(player, limit).stream().map(row -> new LinkPayloads.Entry(row.type(),
                row.amount(), row.incoming(), row.currency(), row.timestamp(),
                NeroEconomyCommon.CURRENCY_PROVIDER.toWallMillis(row.timestamp()))).toList();
    }

    private static JsonObject market(MinecraftServer server, UUID player, String query, int page) {
        // The default search: the gallery's demo rows are never part of it.
        MarketSavedData.SearchPage found = MarketSavedData.get(server).searchPage(query, page,
                LinkPayloads.DEFAULT_PAGE_SIZE, NeroEconomyCommon.CURRENCY_PROVIDER.now(), false, true);
        List<LinkPayloads.Row> listings = found.listings().stream().map(listing -> new LinkPayloads.Row(
                listing.listingId().toString(), listing.itemId().toString(), itemName(listing.stock()),
                listing.availableQuantity(), MarketSavedData.buyerUnitPrice(listing, player),
                listing.seller().equals(player), MarketSavedData.pristine(listing.stock()))).toList();
        List<LinkPayloads.Row> offers = found.buyOffers().stream().map(offer -> new LinkPayloads.Row(
                offer.offerId().toString(), offer.itemId().toString(), itemName(offer.itemId()),
                offer.remaining(), offer.unitPrice(), offer.buyer().equals(player), true)).toList();
        return LinkPayloads.market(listings, offers, page, LinkPayloads.DEFAULT_PAGE_SIZE, found.listingTotal(),
                found.buyOfferTotal(), wallNow());
    }

    /**
     * The item's own name. Deliberately not the hover name: a stack renamed on an anvil carries
     * text a player typed, and a snapshot must not carry that to other players' phones.
     */
    private static String itemName(ItemStack stack) {
        try {
            return stack.getItemName().getString();
        } catch (RuntimeException unresolved) {
            return "";
        }
    }

    private static String itemName(Identifier itemId) {
        return BuiltInRegistries.ITEM.getOptional(itemId).map(item -> itemName(new ItemStack(item))).orElse("");
    }

    // --- trade -------------------------------------------------------------------------------

    @Override public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        if (!TRADE.equals(actionId)) return invalid("Unknown action.");
        if (!EconomyConfig.REMOTE_TRADE_ENABLED.get())
            return LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, LinkPayloads.TRADE_DISABLED);
        MinecraftServer server = serverOrNull();
        if (server == null || playerId == null) return invalid("No active server.");
        // One plain sentence, and nothing is touched: a frozen economy moves no value from anywhere.
        if (EconomyHealth.frozen())
            return LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, LinkPayloads.ECONOMY_FROZEN);
        JsonObject safeParams = params == null ? new JsonObject() : params;
        return onServerThread(server, () -> trade(server, playerId, safeParams), LinkActionResult.error(
                LinkActionResult.Error.INTERNAL, "The server could not run the trade. Check your balance before retrying."));
    }

    private static LinkActionResult trade(MinecraftServer server, UUID playerId, JsonObject params) {
        PULSE.interested(playerId, System.nanoTime());
        if (!IntentGuard.withinBudget(playerId, RateLimiter.Budget.VALUE)) return invalid(LinkPayloads.TOO_MANY_REQUESTS);
        // Never generated here: an id the client did not send cannot make its retry idempotent.
        UUID request = LinkPayloads.requestId(params);
        if (request == null) return invalid(LinkPayloads.REQUEST_ID_REQUIRED);
        int quantity = LinkPayloads.quantity(params);
        if (quantity < LinkPayloads.MIN_QUANTITY) return invalid(LinkPayloads.QUANTITY_OUT_OF_RANGE);
        MarketSavedData store = MarketSavedData.get(server);
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        try {
            return switch (LinkPayloads.text(params, "op")) {
                case "buy" -> buy(server, store, playerId, online, params, quantity, request);
                case "sell" -> sell(store, playerId, online, params, quantity, request);
                default -> invalid(LinkPayloads.UNKNOWN_OPERATION);
            };
        } catch (RuntimeException failure) {
            // The type only: a message could echo client input.
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A Link trade failed unexpectedly ({}).",
                    failure.getClass().getName());
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL, "The trade could not be completed.");
        }
    }

    /** Buys from a sell listing; the store consumes the request id and re-prices at execution. */
    private static LinkActionResult buy(MinecraftServer server, MarketSavedData store, UUID playerId,
            ServerPlayer online, JsonObject params, int quantity, UUID request) {
        UUID listingId = LinkPayloads.uuid(params, "listingId");
        if (listingId == null) return invalid(LinkPayloads.LISTING_ID_REQUIRED);
        MarketListing listing = store.listing(listingId);
        if (listing == null) return invalid(store.buyOffer(listingId) != null ? LinkPayloads.BUY_NEEDS_LISTING
                : LinkPayloads.purchaseMessage(PurchaseResult.NOT_FOUND));
        // The quoted price is a ceiling. With none sent, the ceiling is the server's own quote now.
        long quoted = LinkPayloads.wholeNumber(params, "quotedUnitPrice", 0L);
        long ceiling = quoted >= 1L ? quoted : MarketSavedData.buyerUnitPrice(listing, playerId);
        PurchaseOutcome outcome = online != null
                ? store.purchaseOutcome(online, listingId, quantity, ceiling, request, null)
                : store.purchaseToClaimOutcome(server, playerId, listingId, quantity, ceiling, request);
        if (!outcome.success()) return invalid(LinkPayloads.purchaseMessage(outcome.result()));
        // What the buyer was actually charged, as the ledger settled it rather than as it was quoted.
        long total = outcome.paid();
        return LinkActionResult.ok(LinkPayloads.tradeResult(balance(playerId), new LinkPayloads.Trade("buy",
                listingId.toString(), quantity, total / quantity, total, true, outcome.toVault() ? "claim" : "inventory")));
    }

    /**
     * With {@code unitPrice}: posts the main-hand stack as a new listing (schema 1). Without it:
     * fills the standing buy offer {@code listingId} names from the main hand.
     */
    private static LinkActionResult sell(MarketSavedData store, UUID playerId, ServerPlayer online,
            JsonObject params, int quantity, UUID request) {
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        long asking = LinkPayloads.wholeNumber(params, "unitPrice", 0L);
        if (asking >= 1L) {
            if (online == null) return offline();
            long expires = now + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
            Posted<MarketListing> posted = store.postFromHandChecked(online, quantity, asking, expires,
                    ListingSource.MARKET_BOARD, "link", request);
            if (posted.result() != PostResult.SUCCESS) return invalid(LinkPayloads.postMessage(posted.result()));
            return LinkActionResult.ok(LinkPayloads.tradeResult(balance(playerId), new LinkPayloads.Trade("sell",
                    posted.row().listingId().toString(), quantity, asking, saturatingTotal(asking, quantity), false,
                    "listed", posted.feeCharged(), 0L)));
        }
        // No id, a sell listing or an unknown id: there is nothing here the player's items could fill.
        UUID offerId = LinkPayloads.uuid(params, "listingId");
        if (offerId == null) return invalid(LinkPayloads.SELL_NEEDS_BUY_OFFER);
        MarketBuyOffer offer = store.buyOffer(offerId);
        if (offer == null) return invalid(LinkPayloads.SELL_NEEDS_BUY_OFFER);
        if (online == null) return offline();
        FillOutcome filled = store.fillBuyOfferOutcome(online, offerId, quantity, request);
        if (!filled.success()) return invalid(LinkPayloads.fillMessage(filled.result()));
        // The proceeds, less the market fee, are booked to the seller's credit claim, so the balance
        // itself has not moved yet.
        return LinkActionResult.ok(LinkPayloads.tradeResult(balance(playerId), new LinkPayloads.Trade("sell",
                offerId.toString(), quantity, offer.unitPrice(), filled.gross(), true, "credit_claim",
                filled.fee(), filled.net())));
    }

    private static long saturatingTotal(long unitPrice, int quantity) {
        try {
            return Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static LinkActionResult invalid(String message) {
        return LinkActionResult.error(LinkActionResult.Error.VALIDATION, message);
    }

    private static LinkActionResult offline() {
        return LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED, LinkPayloads.SELL_NEEDS_PLAYER);
    }

    // --- live balance ------------------------------------------------------------------------

    /**
     * Core runs this inside the ledger call that changed the balance and does not catch what a
     * listener throws, so it only takes a note and lets nothing escape.
     */
    private static void onBalanceChange(CurrencyEvents.BalanceChange change) {
        try {
            if (change == null || change.player() == null || change.oldBalance() == change.newBalance()
                    || !NeroEconomyCommon.CREDITS.equals(change.currency())) return;
            PULSE.changed(change.player(), System.nanoTime());
        } catch (RuntimeException ignored) {
            // A missed live update is harmless; the next snapshot carries the balance anyway.
        }
    }

    /** Economy tick, server thread: publishes one {@code balance} event per player whose change is due. */
    private static void publishDueBalances() {
        if (!PULSE.hasPending() || dataOrNull() == null) return;
        for (UUID player : PULSE.due(System.nanoTime())) {
            JsonObject payload = balance(player);
            CACHE.put(player, BALANCE, variant("", 0, 0, LinkPayloads.DEFAULT_PAGE_SIZE), payload);
            NeroLinkRegistry.eventBus().publish(LinkEvent.forPlayer(MODULE_ID, BALANCE, player, payload));
        }
    }

    /** Erasure: nothing this module holds in memory for the player survives it. */
    private static void forget(UUID player) {
        CACHE.forget(player);
        PULSE.forget(player);
    }

    /** Server unbind: no per-player state outlives the world it came from. */
    private static void reset() {
        CACHE.clear();
        PULSE.clear();
    }

    // --- plumbing ----------------------------------------------------------------------------

    private static long wallNow() {
        return NeroEconomyCommon.CURRENCY_PROVIDER.toWallMillis(NeroEconomyCommon.CURRENCY_PROVIDER.now());
    }

    /**
     * Runs the work inline on the server thread, which is where Core's contract has every call
     * arrive. Off it, the work is handed to the server thread and awaited for a bounded time; a
     * stopped server gets the fallback rather than running store code on a foreign thread.
     */
    private static <T> T onServerThread(MinecraftServer server, Supplier<T> task, T fallback) {
        try {
            if (server.isSameThread()) return task.get();
            if (server.isStopped()) return fallback;
            return server.submit(task).get(MARSHAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A Link request failed ({}).", failure.getClass().getName());
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
}
