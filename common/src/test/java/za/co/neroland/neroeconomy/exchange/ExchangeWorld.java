package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.TransactionRecord;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.simulation.Housekeeping;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimRunner;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * What a test of the exchange's trading rules starts from: a real economy store with a filled
 * Reserve and a clock the test moves ({@link TradeWorld}), three made-up companies listed at round
 * prices, the default trading terms, and short-hands for what every such test does. No game.
 * Call {@link #close()} in an {@code @AfterEach}.
 */
public final class ExchangeWorld {
    public static final long MINUTE = 60_000L;
    public static final long HOUR = 3_600_000L;
    public static final long DAY = PriceModel.DAY_MILLIS;
    public static final long STEP = ExchangeTesting.STEP;
    /** A steady company at 100 credits that pays 1.4 % a week: one unit earns 0.2 credits a day. */
    public static final StockDefinition CALM = new StockDefinition("test:calm", "stock.test.calm",
            "stock.test.calm.description", "mining", StockType.STEADY, 100L, 2.0D, 6.0D, 1.4D, List.of(), true);
    /** A second steady company, at 50 credits, that pays nothing. */
    public static final StockDefinition PLAIN = new StockDefinition("test:plain", "stock.test.plain",
            "stock.test.plain.description", "farming", StockType.STEADY, 50L, 2.0D, 6.0D, 0.0D, List.of(), true);
    /** A risky company at 200 credits. */
    public static final StockDefinition WILD = new StockDefinition("test:wild", "stock.test.wild",
            "stock.test.wild.description", "space", StockType.RISKY, 200L, 12.0D, 20.0D, 0.0D, List.of(), true);
    public static final String INDEX = IndexState.ID;
    /** The economy-day every test starts at the very beginning of. */
    public static final long FIRST_DAY = 20_834L;

    public final TradeWorld world = new TradeWorld();
    public StockRegistry registry = StockRegistry.of(List.of(CALM, PLAIN, WILD));
    public TradingTerms terms = TradingTerms.DEFAULTS;
    public ExchangeSettings settings = ExchangeSettings.DEFAULTS;
    public final ActionLimiter limiter = new ActionLimiter();
    public final ActionLimiter practiceLimiter = new ActionLimiter();
    /** Every alert the exchange raised, in order. */
    public final List<Map.Entry<AccountId, EconomyAlerts.Kind>> alerts = new ArrayList<>();
    /** The simulation as the game runs it: housekeeping, prices, dividends, settlement. */
    public final SimPipeline pipeline = new SimPipeline();
    public SimRunner.Settings sim = new SimRunner.Settings(STEP, 432L, 2, true);

    public ExchangeWorld() {
        // A fixed secret, and a clock moved on to exactly the beginning of an economy-day.
        SimTesting.fixSecret(world.store, SimTesting.secret(7));
        world.wall = FIRST_DAY * DAY;
        assertEquals(FIRST_DAY * DAY, world.now());
        exchange().reconcile(registry);
        Housekeeping.register(pipeline);
        pipeline.register(new ExchangeStep(new ExchangeStep.Wiring() {
            @Override public ExchangeSettings settings() { return settings; }
            @Override public StockRegistry registry(SimContext context) { return registry; }
            @Override public ItemCatalog items(SimContext context) { return ItemCatalog.OPEN; }
            @Override public double shock(List<String> keys, long now) { return 1.0D; }
        }));
        ExchangeSteps.register(pipeline, context -> env());
        // The first call only starts the count: a simulation that has never run replays nothing.
        assertEquals(0, runDueSteps());
    }

    public void close() { world.close(); }

    // --- what an operation is handed ---------------------------------------------------------

    public ExchangeEnv env() {
        return new ExchangeEnv(terms, registry, (account, kind) -> alerts.add(Map.entry(account, kind)), limiter,
                practiceLimiter);
    }

    public ExchangeSection exchange() { return world.store.exchange(); }
    public ExchangeDesk desk() { return exchange().desk(); }
    public PracticeBook practice() { return exchange().practice(); }
    public EconomySavedData ledger() { return world.store.ledger(); }
    public long now() { return world.now(); }

    public static AccountId account(UUID player) { return new PlayerAccountId(player); }

    // --- setting the scene -------------------------------------------------------------------

    /** Puts a company's price — and its day-open price — at {@code cents}, as if it had got there. */
    public void price(String stockId, long cents) {
        StockState state = exchange().state(stockId);
        state.price = cents;
        state.dayOpen = cents;
        state.carryMicros = 0L;
    }

    public void indexPrice(long cents) {
        exchange().index().price = cents;
        exchange().index().dayOpen = cents;
    }

    /** Halts a company for a while. */
    public void halt(String stockId) { exchange().state(stockId).haltedUntil = exchange().lastStep() + 6L; }

    /** Takes a company off the exchange, as a data-pack reload without its file does. */
    public void delist(StockDefinition gone) {
        List<StockDefinition> left = new ArrayList<>(registry.stocks());
        left.remove(gone);
        registry = StockRegistry.of(left);
        exchange().reconcile(registry);
    }

    /** Lets economy time pass. In steps of a day at most, because the clock takes no bigger jump at once. */
    public void advance(long millis) {
        for (long left = millis; left > 0L; left -= DAY) {
            world.wall += Math.min(left, DAY);
            world.now();
        }
    }

    /** The economy-day it is now. */
    public long today() { return Math.floorDiv(now(), DAY); }

    /** Runs every simulation step that is due now, as the server tick would over several ticks. */
    public int runDueSteps() {
        int ran = 0;
        SimRunner.Progress progress;
        do {
            progress = SimRunner.advance(world.store, null, pipeline, now(), sim,
                    (handler, failure) -> { throw new AssertionError(handler, failure); });
            ran += progress.ran();
        } while (progress.ran() > 0);
        return ran;
    }

    // --- short-hands -------------------------------------------------------------------------

    public TradeQuote buy(UUID player, String stockId, long units) {
        return desk().buy(env(), account(player), stockId, units, ExchangeDesk.ANY_PRICE);
    }

    public TradeQuote sell(UUID player, String stockId, long units) {
        return desk().sell(env(), account(player), stockId, units, ExchangeDesk.FULL_GAIN_ONLY);
    }

    public long exchangeBalance() { return ledger().balance(SystemAccountId.EXCHANGE); }
    public long budgetLeft() { return ledger().budgetRemaining(BudgetKind.EXCHANGE); }

    public Holding holding(UUID player, String stockId) {
        Portfolio portfolio = desk().portfolio(account(player));
        return portfolio == null ? null : portfolio.holding(stockId);
    }

    /** The player's own ledger rows of one reason, newest first. */
    public List<TransactionRecord> rows(UUID player, TransactionType type) {
        return ledger().history(player, 100).stream().filter(row -> row.type() == type).toList();
    }

    /** Invariants A, B and C, as the store itself checks them, and C once more by hand. */
    public void check() {
        assertEquals(exchange().held(), exchangeBalance(), "Invariant C");
        assertTrue(world.store.checkInvariants(), "invariants A, B, C");
        assertTrue(world.store.supplyReport().healthy());
    }

    /** The ledger as it would be saved, without the clock's watermark: for "nothing moved" comparisons. */
    public JsonElement ledgerSnapshot() {
        JsonElement saved = za.co.neroland.neroeconomy.economy.StoreTesting.encode(world.store).getAsJsonObject()
                .get("ledger");
        saved.getAsJsonObject().remove("clock_last_wall");
        return saved;
    }

    /** The exchange section as it would be saved. */
    public JsonElement exchangeSnapshot() {
        return ExchangeSection.CODEC.encodeStart(JsonOps.INSTANCE, exchange()).getOrThrow();
    }
}
