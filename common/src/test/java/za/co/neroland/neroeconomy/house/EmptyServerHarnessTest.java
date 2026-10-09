package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.exchange.ActionLimiter;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeDesk;
import za.co.neroland.neroeconomy.exchange.ExchangeEnv;
import za.co.neroland.neroeconomy.exchange.ExchangeSettings;
import za.co.neroland.neroeconomy.exchange.ExchangeStep;
import za.co.neroland.neroeconomy.exchange.ExchangeSteps;
import za.co.neroland.neroeconomy.exchange.ExchangeWorld;
import za.co.neroland.neroeconomy.exchange.StockRegistry;
import za.co.neroland.neroeconomy.exchange.TradingTerms;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.guard.GuardTesting;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.obligation.AuctionTerms;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.HouseAuction;
import za.co.neroland.neroeconomy.obligation.JobState;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.simulation.Housekeeping;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimRunner;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.trade.BuyerPricing;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeWorld;
import za.co.neroland.neroeconomy.trade.TradingSteps;

/**
 * E5 — the empty-server proof. The whole simulation pipeline as the game registers it (Reserve
 * top-up, day roll-over, expiry, the trading rules, exchange prices, dividends, bonds, the Merchant
 * Houses, their auctions and jobs, server bounties) runs on the shipped commodity, House and bounty
 * data with nobody online for seven economy-days, after a few players set things up and left:
 *
 * <ol type="a">
 *   <li>the economy moves on its own — prices change, the Houses re-quote, a vault-backed rule fills
 *       against House orders, bonds mature, dividends are paid, House auctions with no bidders end
 *       and go back to stock, House jobs nobody took expire back into the Reserve;</li>
 *   <li>Invariants A, B and C and item conservation hold after every tick;</li>
 *   <li>a 72-hour pause followed by its catch-up ends in exactly the state of an uninterrupted run;</li>
 *   <li>the work per tick during the catch-up stays under a fixed budget: at most
 *       {@link SimRunner.Settings#maxPerTick()} steps per tick, timed.</li>
 * </ol>
 *
 * There is no game and no server here; the simulation driver, the store and every rule are the
 * ones the game runs. The numbers measured are printed for the report.
 */
class EmptyServerHarnessTest {
    static final long MINUTE = 60_000L;
    static final long STEP = 10L * MINUTE;
    static final long DAY = TradeWorld.DAY;
    static final long HOUR = TradeWorld.HOUR;
    /** Ticks in this harness are five minutes of economy time apart while running normally. */
    static final long TICK = 5L * MINUTE;
    static final List<String> HOUSE_FILES = List.of("artisans", "engineers", "general", "growers", "hunters", "masons",
            "settlers", "smiths", "starport");
    static final CommodityRegistry COMMODITIES = GuardTesting.shipped(GuardTesting.vanillaWorld());
    static final HouseRegistry HOUSES = shippedHouses();
    static final BountyTable BOUNTIES = BountyTable.parse(Map.of("neroeconomy:hostile",
            GuardTesting.read("common/src/main/resources/", "data/neroeconomy/neroeconomy/bounties/hostile.json")));
    static final StockRegistry STOCKS = StockRegistry.of(List.of(ExchangeWorld.CALM, ExchangeWorld.PLAIN,
            ExchangeWorld.WILD));

    static HouseRegistry shippedHouses() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (String id : HOUSE_FILES) files.put("neroeconomy:" + id,
                GuardTesting.read("common/src/main/resources/", "data/neroeconomy/neroeconomy/houses/" + id + ".json"));
        return HouseParser.registry(files);
    }

    /** One world: the store, its clock, the pipeline, and what was measured. */
    static final class Harness {
        final TradeWorld world = new TradeWorld();
        final SimPipeline pipeline = new SimPipeline();
        final SimRunner.Settings settings = new SimRunner.Settings(STEP, 72L * 6L, 2, true);
        final Map<EconomyAlerts.Kind, Long> alerts = new EnumMap<>(EconomyAlerts.Kind.class);
        final ActionLimiter limiter = new ActionLimiter();
        final ActionLimiter practice = new ActionLimiter();
        final Map<String, Long> goodsAtStart = new TreeMap<>();
        long houseMadeAtStart;
        long ticks;
        long steps;
        int maxStepsPerTick;
        long catchUpTicks;
        long catchUpNanos;
        long maxTickNanos;
        long skipped;
        UUID ruleId;

        Harness() {
            world.wall = ExchangeWorld.FIRST_DAY * DAY;
            SimTesting.fixSecret(world.store, SimTesting.secret(45));
            world.env = new TradeEnv(TradeWorld.settings(), TradeWorld.LIMIT, BuyerPricing.NONE, ItemCatalog.OPEN,
                    (account, kind) -> alerts.merge(kind, 1L, Long::sum), new SystemDelivery() {
                        @Override public boolean handles(SystemAccountId account) { return account.isHouse(); }
                        @Override public void deliver(SystemAccountId account, Lot lot) {
                            world.store.houses().deliver(account, lot);
                        }
                    });
            world.store.exchange().reconcile(STOCKS);
            // The pipeline exactly as NeroEconomyCommon and Houses register it.
            Housekeeping.register(pipeline);
            TradingSteps.register(pipeline, context -> world.env);
            pipeline.register(new ExchangeStep(new ExchangeStep.Wiring() {
                @Override public ExchangeSettings settings() { return ExchangeSettings.DEFAULTS; }
                @Override public StockRegistry registry(SimContext context) { return STOCKS; }
                @Override public ItemCatalog items(SimContext context) { return ItemCatalog.OPEN; }
                @Override public double shock(List<String> keys, long now) { return 1.0D; }
            }));
            ExchangeSteps.register(pipeline, context -> exchangeEnv());
            pipeline.register(new HouseStep(context -> houseEnv()));
            HouseDealsStep.register(pipeline, context -> new HouseDealsEnv(houseEnv(), AuctionTerms.DEFAULTS,
                    HouseDealSettings.DEFAULTS, BOUNTIES, "minecraft:overworld|0|64|0"));
            assertEquals(List.of("reserve_top_up", "day_rollover", "expiry", "trade_expiry", "auto_trade",
                    "exchange_prices", "exchange_dividends", "exchange_settlement", HouseStep.NAME, HouseDealsStep.SETTLE,
                    HouseDealsStep.DEALS), pipeline.names());
        }

        HouseEnv houseEnv() { return new HouseEnv(COMMODITIES, HOUSES, world.env, HouseSettings.DEFAULTS, null); }

        ExchangeEnv exchangeEnv() {
            return new ExchangeEnv(TradingTerms.DEFAULTS, STOCKS, (account, kind) -> alerts.merge(kind, 1L, Long::sum),
                    limiter, practice);
        }

        /** One server tick: the simulation's share of it, timed. */
        int tick(boolean catchingUp) {
            long started = System.nanoTime();
            SimRunner.Progress progress = SimRunner.advance(world.store, null, pipeline, world.now(), settings,
                    (handler, failure) -> { throw new AssertionError(handler, failure); });
            long spent = System.nanoTime() - started;
            ticks++;
            steps += progress.ran();
            skipped += progress.skipped();
            maxStepsPerTick = Math.max(maxStepsPerTick, progress.ran());
            if (catchingUp) {
                catchUpTicks++;
                catchUpNanos += spent;
                maxTickNanos = Math.max(maxTickNanos, spent);
            }
            check();
            return progress.ran();
        }

        /** Ticks every {@link #TICK} until {@code until} (economy time). */
        void runUntil(long until) {
            while (world.wall + TICK <= until) {
                world.wall += TICK;
                tick(false);
            }
        }

        /** The server was paused for {@code millis}: on resume the missed steps are replayed, two per tick. */
        void pauseAndCatchUp(long millis) {
            world.wall += millis;
            // A tick is about 50 ms of real time; the catch-up spreads over as many as it needs.
            while (true) {
                int ran = tick(true);
                if (SimRunner.behind(world.store.simulation(), world.now(), STEP) == 0L && ran < 2) break;
                world.wall += 50L;
            }
        }

        /** A few players set things up, then leave for good. */
        void setUp() {
            // Let the Houses stock up and quote first.
            runUntil(world.wall + 2L * HOUR);
            UUID saver = world.player(1, 20_000L);
            UUID investor = world.player(2, 20_000L);
            UUID buyer = world.player(3, 5_000L);
            ExchangeDesk desk = world.store.exchange().desk();
            assertTrue(desk.buyBond(exchangeEnv(), TradeWorld.account(saver), BondTerm.ONE_DAY, 2_000L, null).ok());
            assertTrue(desk.buyBond(exchangeEnv(), TradeWorld.account(saver), BondTerm.THREE_DAYS, 2_000L, null).ok());
            assertTrue(desk.buy(exchangeEnv(), TradeWorld.account(investor), ExchangeWorld.CALM.id(), 40L,
                    ExchangeDesk.ANY_PRICE).ok());
            // A vault-backed rule buying what a House sells, a little above its ask.
            SellOrder ask = houseAsks().stream().min(java.util.Comparator.comparing(SellOrder::item)).orElseThrow();
            var outcome = world.store.rules().add(world.env, TradeWorld.account(buyer), RuleSide.BUY, false, ask.item(),
                    ask.unitPrice() * 2L, 4, 2_000L, RulesSection.VAULT, new UUID(45L, 1L));
            assertTrue(outcome.ok(), outcome.result().toString());
            ruleId = outcome.rule().id();
            check();
        }

        List<SellOrder> houseAsks() {
            return world.store.book().sellsMatching(item -> true, world.now()).stream()
                    .filter(order -> house(order.owner())).toList();
        }

        List<BuyOrder> houseBids() {
            return world.store.book().buysMatching(item -> true, world.now()).stream()
                    .filter(order -> house(order.owner())).toList();
        }

        static boolean house(AccountId account) { return account instanceof SystemAccountId system && system.isHouse(); }

        List<Lot> lots() {
            List<Lot> out = new java.util.ArrayList<>();
            world.store.forEachLot(out::add);
            return out;
        }

        /** Invariants A, B, C, and item conservation: goods only appear by restocking and vanish by the sink. */
        void check() {
            assertTrue(world.store.checkInvariants(), "invariants after tick " + ticks);
            assertTrue(world.reserve() >= 0L);
            if (!counting) return;
            Map<String, Long> now = new TreeMap<>();
            for (Lot lot : lots()) now.merge(lot.item(), (long) lot.count(), Long::sum);
            assertTrue(madeBefore.keySet().containsAll(now.keySet()), "goods of an item no commodity covers");
            HouseSection houses = world.store.houses();
            for (String item : madeBefore.keySet()) {
                long expected = goodsAtStart.getOrDefault(item, 0L) + houses.created(item) - houses.destroyed(item)
                        - madeBefore.get(item);
                assertEquals(expected, (long) now.getOrDefault(item, 0L), "goods of " + item + " after tick " + ticks);
            }
        }

        /** Per item, what the Houses had made less what they had destroyed when the count started. */
        final Map<String, Long> madeBefore = new TreeMap<>();
        boolean counting;

        /** From here on every unit of goods in the store is accounted for after every tick. */
        void startCounting() {
            HouseSection houses = world.store.houses();
            goodsAtStart.clear();
            for (Lot lot : lots()) goodsAtStart.merge(lot.item(), (long) lot.count(), Long::sum);
            for (var commodity : COMMODITIES.commodities())
                for (String item : COMMODITIES.itemsOf(commodity.key()))
                    madeBefore.put(item, houses.created(item) - houses.destroyed(item));
            for (String item : goodsAtStart.keySet()) madeBefore.putIfAbsent(item, houses.created(item) - houses.destroyed(item));
            counting = true;
        }

        /** Everything that matters, as saved, without the clock's watermark. */
        String fingerprint() {
            JsonObject saved = StoreTesting.encode(world.store).getAsJsonObject();
            JsonObject ledger = saved.getAsJsonObject("ledger");
            ledger.remove("clock_last_wall");
            ledger.remove("clock_offset");
            // Each store salts its pseudonyms at random: they are compared by order of first use.
            ledger.remove("pseudonym_salt");
            Map<String, String> names = new LinkedHashMap<>();
            if (ledger.has("transactions")) for (JsonElement row : ledger.getAsJsonArray("transactions")) {
                JsonObject fields = row.getAsJsonObject();
                for (String side : List.of("source", "destination")) {
                    if (!fields.has(side)) continue;
                    String name = fields.get(side).getAsString();
                    if (!name.matches("[0-9a-f]{24}")) continue;
                    fields.addProperty(side, names.computeIfAbsent(name, ignored -> "p" + names.size()));
                }
            }
            return saved + "|" + alerts;
        }
    }

    /** Seven economy-days with nobody online; with a 72-hour pause from the third day when asked. */
    static Harness run(boolean pause) {
        Harness harness = new Harness();
        try {
            harness.setUp();
            harness.startCounting();
            long start = harness.world.wall;
            if (pause) {
                harness.runUntil(start + 2L * DAY);
                harness.pauseAndCatchUp(72L * HOUR);
                harness.runUntil(start + 7L * DAY);
            } else harness.runUntil(start + 7L * DAY);
            // Both runs end at the very same instant, with the clock read there once.
            harness.world.wall = start + 7L * DAY + TICK / 2L;
            harness.tick(false);
            return harness;
        } finally {
            harness.world.close();
        }
    }

    @Test void sevenEmptyDaysMoveTheEconomyKeepEveryInvariantAndACatchUpEndsExactlyWhereARunWould() {
        // (a) and (b): the uninterrupted run, checked after every tick as it goes.
        Map<String, Long> pricesBefore = new LinkedHashMap<>();
        Harness steady = new Harness();
        Set<UUID> asksBefore = new HashSet<>();
        long rulesDone;
        try {
            steady.setUp();
            steady.startCounting();
            for (String id : List.of(ExchangeWorld.CALM.id(), ExchangeWorld.PLAIN.id(), ExchangeWorld.WILD.id()))
                pricesBefore.put(id, steady.world.store.exchange().state(id).price());
            for (SellOrder ask : steady.houseAsks()) asksBefore.add(ask.id());
            long reserveRefundsBefore = steady.world.reserve();
            steady.runUntil(steady.world.wall + 7L * DAY);
            assertTrue(reserveRefundsBefore > 0L);
            // Prices changed.
            int moved = 0;
            for (Map.Entry<String, Long> price : pricesBefore.entrySet())
                if (steady.world.store.exchange().state(price.getKey()).price() != price.getValue()) moved++;
            assertTrue(moved >= 2, "exchange prices moved: " + moved);
            // The Houses re-quoted: every order on the book now was placed after the start.
            List<SellOrder> asks = steady.houseAsks();
            assertTrue(!asks.isEmpty() && !steady.houseBids().isEmpty());
            for (SellOrder ask : asks) assertTrue(!asksBefore.contains(ask.id()), "a House order never re-quoted");
            // The vault-backed rule filled against House orders on its own.
            TradeRule rule = steady.world.store.rules().rule(steady.ruleId);
            rulesDone = rule.done();
            assertTrue(rulesDone > 0L, "the rule never filled");
            assertTrue(steady.world.held(new UUID(7L, 3L), rule.target()) > 4L, "the rule's goods are not in the vault");
            // Bonds matured and dividends were paid.
            assertTrue(steady.alerts.getOrDefault(EconomyAlerts.Kind.BOND_MATURED, 0L) >= 2L, steady.alerts.toString());
            assertTrue(steady.alerts.getOrDefault(EconomyAlerts.Kind.DIVIDEND_PAID, 0L) >= 5L, steady.alerts.toString());
            assertTrue(steady.world.store.exchange().desk().heldParts()[1] == 0L, "a bond is still open");
            // House auctions ran with no bidder and went back to stock; House jobs expired back to the Reserve.
            List<HouseAuction> auctions = steady.world.store.obligations().houseAuctionHistory();
            long unsold = auctions.stream().filter(value -> value.state() == JobState.FULFILLED && value.bidderId() == null)
                    .count();
            assertTrue(unsold >= 7L, "unsold House auctions returned: " + unsold);
            assertTrue(auctions.size() <= 7L * HouseDealSettings.DEFAULTS.auctionsPerDay() + 4L);
            List<EconomyJob> jobs = steady.world.store.obligations().systemJobHistory();
            long expired = jobs.stream().filter(job -> job.state() == JobState.EXPIRED).count();
            assertTrue(expired > 0L, "no House job or bounty expired back into the Reserve");
            assertTrue(jobs.stream().anyMatch(job -> steady.world.store.obligations().houseOf(job.id()) != null));
            assertTrue(jobs.stream().anyMatch(job -> job.type() == za.co.neroland.neroeconomy.obligation.JobType.BOUNTY));
            assertEquals(0L, steady.skipped);
            System.out.println("[empty-server] 7 days: ticks " + steady.ticks + ", steps " + steady.steps + ", House auctions "
                    + auctions.size() + " (unsold, returned " + unsold + "), system jobs " + jobs.size() + " (expired "
                    + expired + "), rule spent " + rulesDone + ", alerts " + steady.alerts + ", prices moved " + moved
                    + "/3, Reserve " + steady.world.reserve());
        } finally {
            steady.world.close();
        }

        // (c) and (d): the same seven days twice from the start, once straight through and once with
        // the server paused for 72 hours on the third day.
        Harness straight = run(false);
        Harness paused = run(true);
        assertEquals(0L, paused.skipped, "a 72-hour pause must be replayed in full");
        assertEquals(straight.steps, paused.steps);
        assertTrue(paused.catchUpTicks >= 72L * 6L / 2L);
        assertTrue(paused.maxStepsPerTick <= paused.settings.maxPerTick());
        assertNotEquals(0L, paused.catchUpNanos);
        assertEquals(straight.fingerprint(), paused.fingerprint(), "the catch-up ended somewhere else");
        double average = paused.catchUpNanos / 1e6 / paused.catchUpTicks;
        double worst = paused.maxTickNanos / 1e6;
        System.out.println("[empty-server] 72h catch-up: " + paused.catchUpTicks + " ticks, at most "
                + paused.maxStepsPerTick + " steps per tick, " + String.format(java.util.Locale.ROOT, "%.2f", average)
                + " ms per tick on average, " + String.format(java.util.Locale.ROOT, "%.2f", worst) + " ms at worst");
        // A generous fixed budget: a tick is 50 ms of real time, and the catch-up must leave most of it.
        assertTrue(worst < 250.0D, "a catch-up tick took " + worst + " ms");
        assertTrue(average < 25.0D, "catch-up ticks took " + average + " ms on average");
    }
}
