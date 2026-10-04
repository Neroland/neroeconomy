package za.co.neroland.neroeconomy.exchange;

import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.AlertSink;

/**
 * The running game's side of the exchange's trading rules: the one set of entry points the
 * commands, the hub screens and the companion-app module all call. Nothing is decided here — each
 * method checks that the economy is not frozen, takes the request id through the shared request
 * ledger so a replayed intent cannot act twice, builds the {@link ExchangeEnv} from the
 * configuration and the data packs, and hands over to the pure rules ({@link ExchangeDesk} for
 * real credits, {@link PracticeBook} for play credits).
 *
 * <p>Everything takes the account's player id, so it works with the player away from the game.
 * The server re-prices every trade when it runs: what a caller was quoted is only ever a ceiling
 * ({@code maxSpend}) or an acceptance ({@code acceptReducedGain}), never a price.
 *
 * <h2>For a screen or the companion app</h2>
 * <ul>
 *   <li>What is on offer: {@link #products}, {@link #bondOffers}.</li>
 *   <li>A confirm step: {@link #quoteBuy}, {@link #quoteBuyForCredits}, {@link #quoteSell} — a
 *       {@link TradeQuote} with the units, the unit price, the total, the fee, what of a gain can
 *       be paid and what would be given up, when held units may be sold, and
 *       {@link TradeQuote#reasonKey()} when it cannot happen.</li>
 *   <li>The trade: {@link #buy}, {@link #buyForCredits}, {@link #sell}, {@link #buyBond},
 *       {@link #redeemBond}.</li>
 *   <li>What the account has: {@link #portfolio}.</li>
 *   <li>The same in practice mode: the {@code practice…} methods.</li>
 *   <li>"What could happen in a week": {@link #outlook}, {@link #bondOutlook}.</li>
 * </ul>
 * Everything returned is a copy with lang keys for its wording; times are economy time.
 */
public final class Investing {
    /** Counts real exchange actions per account; in memory only, dropped on erasure and at server stop. */
    private static final ActionLimiter LIMITER = new ActionLimiter();
    /** The same for practice actions, counted separately. */
    private static final ActionLimiter PRACTICE_LIMITER = new ActionLimiter();

    /** Fixed catalogue sentences only, as every alert of this mod is. */
    private static final AlertSink ALERTS = (account, kind) -> {
        UUID player = Accounts.player(account);
        MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
        if (player == null || server == null) return;
        try {
            EconomyAlerts.raise(server, player, kind, LinkAlert.Severity.INFO);
        } catch (RuntimeException | LinkageError failure) {
            // An alert is a courtesy; it never undoes or blocks what raised it.
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] An alert could not be raised ({}).",
                    failure.getClass().getName());
        }
    };

    private Investing() { }

    // --- the environment ---------------------------------------------------------------------

    /** The environment for one operation on {@code server}'s exchange. Cheap; build one per operation. */
    public static ExchangeEnv env(MinecraftServer server) {
        return new ExchangeEnv(TradingTerms.fromConfig(), server == null ? StockRegistry.UNAVAILABLE
                : StockData.forServer(server), ALERTS, LIMITER, PRACTICE_LIMITER);
    }

    /** The environment for a simulation step. */
    public static ExchangeEnv env(SimContext context) { return env(context.server()); }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    private static ExchangeSection section(MinecraftServer server) { return EconomyStore.get(server).exchange(); }

    /** Whether an intent may run at all; the request id is spent here, so a replay is refused. */
    private static TradeResult admit(MinecraftServer server, UUID player, UUID requestId) {
        if (EconomyHealth.frozen()) return TradeResult.FROZEN;
        if (server == null || player == null || requestId == null) return TradeResult.INVALID;
        return EconomyStore.get(server).market().rememberRequest(player, requestId) ? TradeResult.OK
                : TradeResult.STALE_REQUEST;
    }

    private static TradeResult readable(MinecraftServer server, UUID player) {
        if (EconomyHealth.frozen()) return TradeResult.FROZEN;
        return server == null || player == null ? TradeResult.INVALID : TradeResult.OK;
    }

    /**
     * The stock id a player meant: a full id as it is, {@code index} for the index fund, and a
     * bare name (or one the command parser put in the {@code minecraft} namespace) for the one
     * listed company whose id ends in it. Null when it names nothing, or more than one company.
     */
    public static String resolve(MinecraftServer server, String typed) {
        if (server == null || typed == null || typed.isBlank()) return null;
        String text = typed.trim();
        if (text.equals("index") || text.equals("minecraft:index") || text.equals(IndexState.ID)) return IndexState.ID;
        ExchangeSection section = section(server);
        if (section.state(text) != null) return text;
        String path = text.startsWith("minecraft:") ? text.substring("minecraft:".length())
                : text.indexOf(':') < 0 ? text : null;
        if (path == null) return null;
        String found = null;
        for (ExchangeViews.Product product : ExchangeViews.products(section, env(server))) {
            if (product.index() || !product.id().endsWith(":" + path)) continue;
            if (found != null) return null;
            found = product.id();
        }
        return found;
    }

    // --- reads -------------------------------------------------------------------------------

    /** Every company and the index fund, with what each costs to buy and fetches when sold now. */
    public static List<ExchangeViews.Product> products(MinecraftServer server) {
        if (server == null || EconomyHealth.frozen()) return List.of();
        ExchangeEnv env = env(server);
        ExchangeSection section = section(server);
        if (env.terms().enabled()) section.reconcile(env.registry());
        return ExchangeViews.products(section, env);
    }

    /** The three Savings Bond terms and what each pays now. */
    public static List<ExchangeViews.BondOffer> bondOffers() { return ExchangeViews.bonds(TradingTerms.fromConfig()); }

    /** The account's real portfolio: what was put in, what it is worth now, the difference, and its bonds. */
    public static ExchangeViews.PortfolioView portfolio(MinecraftServer server, UUID player) {
        ExchangeEnv env = env(server);
        return ExchangeViews.portfolio(section(server), env, account(player), NeroEconomyCommon.CURRENCY_PROVIDER.now());
    }

    /** The account's practice portfolio, in play credits. */
    public static ExchangeViews.PortfolioView practicePortfolio(MinecraftServer server, UUID player) {
        ExchangeEnv env = env(server);
        return ExchangeViews.practice(section(server), env, account(player), NeroEconomyCommon.CURRENCY_PROVIDER.now());
    }

    /**
     * What a week could do to {@code stake} credits in one company or in the index fund: a bad, a
     * typical and a good outcome, after costs. Null when the id names nothing that is listed.
     */
    public static WeekOutlook.Outcome outlook(MinecraftServer server, String stockId, long stake) {
        if (server == null || stockId == null) return null;
        ExchangeEnv env = env(server);
        ExchangeSettings settings = ExchangeSettings.fromConfig();
        long stepMillis = za.co.neroland.neroeconomy.simulation.Simulation.settings().stepMillis();
        if (IndexState.ID.equals(stockId))
            return WeekOutlook.index(env.registry().stocks(), settings, env.terms(), stepMillis, stake);
        StockDefinition definition = env.registry().get(stockId);
        return definition == null ? null : WeekOutlook.stock(definition, settings, env.terms(), stepMillis, stake);
    }

    /** What a Savings Bond of {@code stake} credits pays at the end of its term. */
    public static WeekOutlook.Outcome bondOutlook(BondTerm term, long stake) {
        return term == null ? null : WeekOutlook.bond(term, TradingTerms.fromConfig(), stake);
    }

    // --- real credits ------------------------------------------------------------------------

    public static TradeQuote quoteBuy(MinecraftServer server, UUID player, String stockId, long units) {
        TradeResult gate = readable(server, player);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
        return section(server).desk().quoteBuy(env(server), account(player), stockId, units);
    }

    public static TradeQuote quoteBuyForCredits(MinecraftServer server, UUID player, String stockId, long credits) {
        TradeResult gate = readable(server, player);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
        return section(server).desk().quoteBuyForCredits(env(server), account(player), stockId, credits);
    }

    /** See {@link ExchangeDesk#buy}. */
    public static TradeQuote buy(MinecraftServer server, UUID player, String stockId, long units, long maxSpend,
            UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
        return section(server).desk().buy(env(server), account(player), stockId, units, maxSpend);
    }

    /** See {@link ExchangeDesk#buyForCredits}. */
    public static TradeQuote buyForCredits(MinecraftServer server, UUID player, String stockId, long credits,
            UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
        return section(server).desk().buyForCredits(env(server), account(player), stockId, credits);
    }

    /** {@code units} may be {@link Quotes#ALL}. */
    public static TradeQuote quoteSell(MinecraftServer server, UUID player, String stockId, long units) {
        TradeResult gate = readable(server, player);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
        return section(server).desk().quoteSell(env(server), account(player), stockId, units);
    }

    /** See {@link ExchangeDesk#sell}; {@code units} may be {@link Quotes#ALL}. */
    public static TradeQuote sell(MinecraftServer server, UUID player, String stockId, long units,
            long acceptReducedGain, UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
        return section(server).desk().sell(env(server), account(player), stockId, units, acceptReducedGain);
    }

    /** See {@link ExchangeDesk#buyBond}. The request id becomes the bond's id. */
    public static BondOutcome buyBond(MinecraftServer server, UUID player, BondTerm term, long principal,
            UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return BondOutcome.refused(gate);
        return section(server).desk().buyBond(env(server), account(player), term, principal, requestId);
    }

    /** See {@link ExchangeDesk#redeemBond}. */
    public static BondOutcome redeemBond(MinecraftServer server, UUID player, UUID bondId, UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return BondOutcome.refused(gate);
        return section(server).desk().redeemBond(env(server), account(player), bondId);
    }

    // --- practice mode: play credits only ----------------------------------------------------

    public static TradeQuote practiceQuoteBuy(MinecraftServer server, UUID player, String stockId, long units) {
        TradeResult gate = readable(server, player);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
        return section(server).practice().quoteBuy(env(server), account(player), stockId, units);
    }

    public static TradeQuote practiceQuoteBuyForCredits(MinecraftServer server, UUID player, String stockId,
            long credits) {
        TradeResult gate = readable(server, player);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
        return section(server).practice().quoteBuyForCredits(env(server), account(player), stockId, credits);
    }

    public static TradeQuote practiceBuy(MinecraftServer server, UUID player, String stockId, long units,
            long maxSpend, UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
        return section(server).practice().buy(env(server), account(player), stockId, units, maxSpend);
    }

    public static TradeQuote practiceBuyForCredits(MinecraftServer server, UUID player, String stockId, long credits,
            UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
        return section(server).practice().buyForCredits(env(server), account(player), stockId, credits);
    }

    public static TradeQuote practiceQuoteSell(MinecraftServer server, UUID player, String stockId, long units) {
        TradeResult gate = readable(server, player);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
        return section(server).practice().quoteSell(env(server), account(player), stockId, units);
    }

    public static TradeQuote practiceSell(MinecraftServer server, UUID player, String stockId, long units,
            UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
        return section(server).practice().sell(env(server), account(player), stockId, units);
    }

    public static BondOutcome practiceBuyBond(MinecraftServer server, UUID player, BondTerm term, long principal,
            UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return BondOutcome.refused(gate);
        return section(server).practice().buyBond(env(server), account(player), term, principal, requestId);
    }

    public static BondOutcome practiceRedeemBond(MinecraftServer server, UUID player, UUID bondId, UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return BondOutcome.refused(gate);
        return section(server).practice().redeemBond(env(server), account(player), bondId);
    }

    /** Starts the practice portfolio afresh; at most once per economy-day. */
    public static TradeResult practiceReset(MinecraftServer server, UUID player, UUID requestId) {
        TradeResult gate = admit(server, player, requestId);
        if (!gate.ok()) return gate;
        return section(server).practice().reset(env(server), account(player));
    }

    // --- lifecycle ---------------------------------------------------------------------------

    /**
     * Erasure entry point for the exchange; tolerates an unknown player. The cost of the erased
     * player's shares, the principal of their bonds and the interest reserved for them move from
     * the exchange account to the Reserve, their holdings and their practice account are removed,
     * and the purge is pushed into Core's recovery backup in the same request.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore.get(server).exchange().forgetPlayer(player);
        LIMITER.forget(account(player));
        PRACTICE_LIMITER.forget(account(player));
        EconomyStore.afterErasure(server, player);
    }

    /** Server stop: the rate limiters hold account ids in memory, and must not outlive the world. */
    public static void clear() {
        LIMITER.clear();
        PRACTICE_LIMITER.clear();
    }
}
