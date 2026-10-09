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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.platform.Services;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * Player-scoped NeroLink module, link schema 2; the bridge stores no economy state. This class
 * checks every request and hands it on: {@link LinkReads} reads the stores, {@link LinkActions}
 * runs actions, and every JSON shape is built by {@link LinkPayloads} (the schema-1 sections and
 * {@code trade}) or {@link LinkViews} (everything added in schema 2).
 *
 * <p><b>Threading.</b> Core's contract is that a snapshot provider and an action handler are called
 * on the server thread, and the bridge does so for both its REST and its WebSocket paths. The work
 * therefore runs inline. A guard remains for a caller that breaks the contract, because a market
 * read expires listings and an action moves goods and credits: off the server thread the work is
 * handed to it and awaited for a bounded time, never run where it was called.
 *
 * <p><b>Sections</b>, all scoped to the requesting player and bounded in size: {@code balance},
 * {@code transactions} ({@code cursor}, {@code limit}) and {@code market} ({@code q}, {@code page})
 * as in schema 1, and with {@code view=book} and {@code item} one item's order book as price
 * levels ({@link LinkViews#book}); {@code summary}, {@code orders}, {@code vault}, {@code stocks},
 * {@code portfolio}, {@code offers}, {@code jobs} ({@code view}: open, contracts, deliveries,
 * bounties or mine) and {@code auctions} ({@code view}: all or mine). Each read takes one token
 * from the player's {@code QUERY} budget; over budget, the last snapshot served for the same query
 * comes back with {@code "throttled": true}.
 *
 * <p><b>Actions</b> — see {@link LinkActions}. Each one needs {@code remoteTradeEnabled}, an
 * economy that is not frozen, a token from the player's shared {@code VALUE} budget and from the
 * action's own limit ({@link LinkActionLimits}), and a {@code requestId} (a UUID; one already acted
 * on is refused as a duplicate). Every action but {@code vault_deposit} and {@code vault_withdraw}
 * also runs for a player who is away ({@link #allowOffline}). What an action spends counts against
 * {@code remoteDailySpendLimit} ({@link RemoteSpendSection}).
 *
 * <p><b>Events</b>, player-scoped and only for a player whose app asked recently: {@code balance}
 * (see {@link BalancePulse}), and {@code orders}, {@code offers}, {@code portfolio} and
 * {@code alerts} (see {@link LinkTopics}), each at most once a second per player.
 */
public final class NeroEconomyLinkModule implements LinkSnapshotProvider, LinkActionHandler {
    public static final String MODULE_ID = "neroeconomy";
    public static final int SCHEMA_VERSION = 2;
    static final String BALANCE = "balance";
    static final String TRANSACTIONS = "transactions";
    static final String MARKET = "market";
    static final String SUMMARY = "summary";
    static final String ORDERS = "orders";
    static final String VAULT = "vault";
    static final String STOCKS = "stocks";
    static final String PORTFOLIO = "portfolio";
    static final String OFFERS = "offers";
    static final String JOBS = "jobs";
    static final String AUCTIONS = "auctions";
    /** The live topics besides {@code balance}. */
    static final String ALERTS = "alerts";
    static final List<String> SECTIONS = List.of(BALANCE, TRANSACTIONS, MARKET, SUMMARY, ORDERS, VAULT, STOCKS,
            PORTFOLIO, OFFERS, JOBS, AUCTIONS);
    static final List<String> ACTIONS = LinkActions.ALL;
    static final List<String> TOPICS = List.of(BALANCE, ORDERS, OFFERS, PORTFOLIO, ALERTS);
    private static final List<String> JOB_VIEWS = List.of("open", "contracts", "deliveries", "bounties", "mine");
    private static final int MAX_QUERY_LENGTH = 64;
    /**
     * Starts the cache key of a {@code market} book view. A market search never contains a space
     * (spaces become underscores), so the two can never share a key.
     */
    private static final String BOOK_VIEW = "book ";
    /** How long a caller off the server thread waits for the server thread to do the work. */
    private static final long MARSHAL_TIMEOUT_SECONDS = 5L;

    private static final LinkSnapshotCache CACHE = new LinkSnapshotCache();
    private static final BalancePulse PULSE = new BalancePulse();
    private static final LinkTopics LIVE = new LinkTopics();
    private static final LinkActionLimits LIMITS = new LinkActionLimits(System::nanoTime);

    public static void register() {
        NeroEconomyLinkModule module = new NeroEconomyLinkModule();
        LinkModuleInfo info = new LinkModuleInfo(MODULE_ID, Services.PLATFORM.getModVersion(), SCHEMA_VERSION,
                SECTIONS, ACTIONS);
        NeroLinkRegistry.registerSnapshotProvider(module, info);
        NeroLinkRegistry.registerActionHandler(module, info);
        // Live balance: Core reports every change, the economy tick publishes what is due.
        CurrencyEvents.onChange(NeroEconomyLinkModule::onBalanceChange);
        NeroEconomyCommon.CURRENCY_PROVIDER.setLifecycleHooks(NeroEconomyLinkModule::publishDue,
                NeroEconomyLinkModule::reset);
        // Every alert that goes out also becomes an alerts event for a player whose app is open.
        EconomyAlerts.listen((player, kind) -> LIVE.mark(player, ALERTS, kind.id()));
        PlayerDataErasure.register((server, player) -> forget(player));
    }

    @Override public String moduleId() { return MODULE_ID; }
    @Override public int schemaVersion() { return SCHEMA_VERSION; }
    @Override public List<String> sections() { return SECTIONS; }
    @Override public List<String> actionIds() { return ACTIONS; }

    /** Every action but the two that touch a live inventory runs for a player who is away. */
    @Override public boolean allowOffline(String actionId) { return LinkActions.OFFLINE.contains(actionId); }

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
        if (MARKET.equals(section) && "book".equals(params.get("view"))) {
            query = BOOK_VIEW + LinkReads.itemId(params.get("item"));
        } else if (MARKET.equals(section)) {
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
        } else if (JOBS.equals(section)) {
            query = jobView(params.get("view"));
        } else if (AUCTIONS.equals(section)) {
            query = "mine".equals(params.get("view")) ? "mine" : "all";
        }
        String variant = variant(query, page, cursor, limit);
        if (!IntentGuard.withinBudget(player, RateLimiter.Budget.QUERY)) {
            JsonObject last = CACHE.get(player, section, variant);
            return LinkPayloads.throttled(last != null ? last : placeholder(server, player, section, page, query));
        }
        JsonObject fresh = switch (section) {
            case BALANCE -> balance(player);
            case TRANSACTIONS -> transactions(player, cursor, limit);
            case MARKET -> query.startsWith(BOOK_VIEW)
                    ? LinkViews.book(LinkReads.book(server, query.substring(BOOK_VIEW.length())))
                    : market(server, player, query, page);
            default -> schema2(server, player, section, query);
        };
        CACHE.put(player, section, variant, fresh);
        return fresh;
    }

    private static String jobView(String asked) { return asked != null && JOB_VIEWS.contains(asked) ? asked : "open"; }

    /** A schema-2 section, read fresh. */
    private static JsonObject schema2(MinecraftServer server, UUID player, String section, String view) {
        return switch (section) {
            case SUMMARY -> LinkViews.summary(LinkReads.summary(server, player));
            case ORDERS -> LinkViews.orders(LinkReads.orders(server, player));
            case VAULT -> LinkViews.vault(LinkReads.vault(server, player));
            case STOCKS -> LinkViews.stocks(LinkReads.stocks(server));
            case PORTFOLIO -> LinkViews.portfolio(LinkReads.portfolio(server, player));
            case OFFERS -> LinkViews.offers(LinkReads.offers(server, player));
            case JOBS -> LinkViews.jobs(LinkReads.jobs(server, player, view));
            default -> LinkViews.auctions(LinkReads.auctions(server, player,
                    server.getPlayerList().getPlayer(player), view));
        };
    }

    /** The cache key for one section's query; a section that takes no parameters always has the same one. */
    private static String variant(String query, int page, int cursor, int limit) {
        return query.toLowerCase(Locale.ROOT).strip() + '|' + page + '|' + cursor + '|' + limit;
    }

    /**
     * What an over-budget player gets when nothing was served for this query before. It does no
     * search and no scan: an empty section of the right shape, except the balance, which is one map
     * lookup and so still the real one.
     */
    private static JsonObject placeholder(MinecraftServer server, UUID player, String section, int page, String view) {
        long asOf = wallNow();
        return switch (section) {
            case TRANSACTIONS -> LinkPayloads.transactions(List.of(), 0, 1, asOf);
            case MARKET -> view.startsWith(BOOK_VIEW)
                    ? LinkViews.book(new LinkViews.ItemBook(view.substring(BOOK_VIEW.length()), "", List.of(), List.of(), asOf))
                    : LinkPayloads.market(List.of(), List.of(), page, LinkPayloads.DEFAULT_PAGE_SIZE, 0, 0, asOf);
            case ORDERS -> LinkViews.orders(new LinkViews.Orders(List.of(), List.of(), List.of(), List.of(),
                    EconomyConfig.MAX_RULES_PER_PLAYER.get(), 0L, EconomyConfig.RULE_DAILY_VALUE_CAP.get(), false, asOf));
            case VAULT -> {
                // Presence is one lookup, so even an empty vault says truly whether the live moves can run.
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                yield LinkViews.vault(new LinkViews.VaultView(List.of(), 0, VaultStacks.cap(), online != null,
                        LinkReads.liveVaultActions(online), asOf));
            }
            case STOCKS -> LinkViews.stocks(LinkReads.noStocks(asOf));
            case PORTFOLIO -> {
                LinkViews.Book empty = new LinkViews.Book(List.of(), List.of(), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L,
                        100, false, 0L, false);
                yield LinkViews.portfolio(new LinkViews.Portfolio(empty, empty, asOf));
            }
            case OFFERS -> LinkViews.offers(new LinkViews.Offers(List.of(), List.of(), false,
                    EconomyConfig.MAX_OFFERS_PER_PLAYER.get(), asOf));
            case JOBS -> LinkViews.jobs(new LinkViews.Jobs(view, List.of(), 0, EconomyConfig.MAX_JOBS_PER_PLAYER.get(),
                    asOf));
            case AUCTIONS -> LinkViews.auctions(new LinkViews.Auctions(view, List.of(), asOf));
            default -> {
                // The balance and the summary: the balance is the real one, everything else empty.
                EconomySavedData data = dataOrNull();
                JsonObject out = LinkPayloads.balance(NeroEconomyCommon.CREDITS.id().toString(),
                        data == null ? 0L : data.getBalance(player, NeroEconomyCommon.CREDITS), List.of(), asOf);
                yield out;
            }
        };
    }

    /** The balance object: the {@code balance} section, the {@code balance} event and the base of every action result. */
    static JsonObject balance(UUID player) {
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
                listing.listingId().toString(), listing.itemId().toString(), LinkReads.itemName(listing.stock()),
                listing.availableQuantity(), MarketSavedData.buyerUnitPrice(listing, player),
                listing.seller().equals(player), MarketSavedData.pristine(listing.stock()))).toList();
        List<LinkPayloads.Row> offers = found.buyOffers().stream().map(offer -> new LinkPayloads.Row(
                offer.offerId().toString(), offer.itemId().toString(), LinkReads.itemName(offer.itemId().toString()),
                offer.remaining(), offer.unitPrice(), offer.buyer().equals(player), true)).toList();
        return LinkPayloads.market(listings, offers, page, LinkPayloads.DEFAULT_PAGE_SIZE, found.listingTotal(),
                found.buyOfferTotal(), wallNow());
    }

    // --- actions -----------------------------------------------------------------------------

    @Override public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        // One plain sentence, and nothing is touched: a frozen economy moves no value from anywhere.
        LinkGate.Refusal early = LinkGate.early(actionId, EconomyConfig.REMOTE_TRADE_ENABLED.get(), EconomyHealth.frozen());
        if (early != LinkGate.Refusal.NONE) return LinkGate.result(early);
        MinecraftServer server = serverOrNull();
        if (server == null || playerId == null) return LinkActions.invalid(LinkText.get("link.neroeconomy.no_server"));
        JsonObject safeParams = params == null ? new JsonObject() : params;
        return onServerThread(server, () -> act(server, playerId, actionId, safeParams), LinkActionResult.error(
                LinkActionResult.Error.INTERNAL, LinkText.get("link.neroeconomy.action_failed")));
    }

    private static LinkActionResult act(MinecraftServer server, UUID playerId, String actionId, JsonObject params) {
        // Never generated here: an id the client did not send cannot make its retry idempotent.
        UUID request = LinkPayloads.requestId(params);
        // The bridge checks presence too; a handler never relies on it.
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        EconomyStore store = EconomyStore.get(server);
        return act(actionId, new LinkActions.Call(server, store, Trading.env(store), LinkActions.Players.of(server),
                playerId, online, params, request, NeroEconomyLinkModule::balance));
    }

    /**
     * The budgets, the gate and the action for a call whose store and presence are already known:
     * what {@link #execute} runs on the server thread, and what the offline proof drives directly.
     */
    static LinkActionResult act(String actionId, LinkActions.Call call) {
        UUID playerId = call.player();
        PULSE.interested(playerId, System.nanoTime());
        boolean budget = IntentGuard.withinBudget(playerId, RateLimiter.Budget.VALUE);
        boolean actionLimit = budget && LIMITS.tryAcquire(playerId, actionId);
        LinkGate.Refusal refusal = LinkGate.check(new LinkGate.Request(actionId, EconomyConfig.REMOTE_TRADE_ENABLED.get(),
                EconomyHealth.frozen(), budget, actionLimit, call.request() != null, call.online() != null,
                call.store().marketClosed()));
        if (refusal != LinkGate.Refusal.NONE) return LinkGate.result(refusal);
        try {
            return LinkActions.run(actionId, call);
        } catch (RuntimeException failure) {
            // The type only: a message could echo client input.
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A Link action failed unexpectedly ({}).",
                    failure.getClass().getName());
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL, LinkText.get("link.neroeconomy.action_failed"));
        }
    }

    // --- live events -------------------------------------------------------------------------

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

    /** Economy tick, server thread: publishes every event that is due. */
    private static void publishDue() {
        if (dataOrNull() == null) return;
        long now = System.nanoTime();
        if (PULSE.hasPending()) for (UUID player : PULSE.due(now)) {
            JsonObject payload = balance(player);
            CACHE.put(player, BALANCE, variant("", 0, 0, LinkPayloads.DEFAULT_PAGE_SIZE), payload);
            NeroLinkRegistry.eventBus().publish(LinkEvent.forPlayer(MODULE_ID, BALANCE, player, payload));
        }
        MinecraftServer server = serverOrNull();
        if (server == null) return;
        try {
            observe(server, now);
            if (!LIVE.hasPending()) return;
            for (LinkTopics.Due due : LIVE.due(now, PULSE::isInterested)) {
                JsonObject payload = topic(server, due);
                if (!ALERTS.equals(due.topic()))
                    CACHE.put(due.player(), due.topic(), variant("", 0, 0, LinkPayloads.DEFAULT_PAGE_SIZE), payload);
                NeroLinkRegistry.eventBus().publish(LinkEvent.forPlayer(MODULE_ID, due.topic(), due.player(), payload));
            }
        } catch (RuntimeException failure) {
            // A missed live event is harmless: the next snapshot carries the same state.
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A Link event could not be built ({}).",
                    failure.getClass().getName());
        }
    }

    /** Reads the cheap signatures of each interested player's topics, at most once a second each. */
    private static void observe(MinecraftServer server, long now) {
        if (EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(server);
        for (UUID player : PULSE.interestedNow(now)) {
            if (!LIVE.checkDue(player, now)) continue;
            LIVE.observe(player, ORDERS, LinkSignatures.orders(store, player), now);
            LIVE.observe(player, OFFERS, LinkSignatures.offers(store, player), now);
            LIVE.observe(player, PORTFOLIO, LinkSignatures.portfolio(server, player), now);
        }
    }

    private static JsonObject topic(MinecraftServer server, LinkTopics.Due due) {
        return switch (due.topic()) {
            case ORDERS -> LinkViews.orders(LinkReads.orders(server, due.player()));
            case OFFERS -> LinkViews.offers(LinkReads.offers(server, due.player()));
            case PORTFOLIO -> LinkViews.portfolio(LinkReads.portfolio(server, due.player()));
            default -> LinkViews.alert(due.detail(), wallNow());
        };
    }

    /** Erasure: nothing this module holds in memory for the player survives it. */
    static void forget(UUID player) {
        CACHE.forget(player);
        PULSE.forget(player);
        LIVE.forget(player);
        LIMITS.forget(player);
    }

    /** Server unbind: no per-player state outlives the world it came from. */
    private static void reset() {
        CACHE.clear();
        PULSE.clear();
        LIVE.clear();
        LIMITS.clear();
    }

    // --- plumbing ----------------------------------------------------------------------------

    private static long wallNow() { return LinkReads.wallNow(); }

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
