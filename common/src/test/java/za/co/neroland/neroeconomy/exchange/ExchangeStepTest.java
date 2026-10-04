package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.simulation.Housekeeping;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimRunner;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.simulation.Simulation;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The {@code exchange_prices} handler, run exactly as the game runs it — through the pipeline and
 * the runner, with a store and a time — but with companies, settings and tags the test supplies.
 */
class ExchangeStepTest {
    private static final long STEP = ExchangeTesting.STEP;
    private static final long FIRST = ExchangeTesting.DAY_START;
    private static final SimRunner.Settings SETTINGS = new SimRunner.Settings(STEP, 432L, 2, true);
    private static final SimRunner.Settings HOUSEKEEPING_ONLY = new SimRunner.Settings(STEP, 432L, 2, false);
    private static final StockDefinition GEMS = ExchangeTesting.steady("gems", "minecraft:diamond", "#c:gems");
    private static final StockDefinition IRON = ExchangeTesting.steady("iron", "minecraft:iron_ingot");
    private static final StockDefinition ROCKET = ExchangeTesting.risky("rocket");
    private static final StockRegistry REGISTRY = StockRegistry.of(List.of(GEMS, IRON, ROCKET));

    /** Wiring a test controls: the settings, and which shocks are active. */
    private static final class TestWiring implements ExchangeStep.Wiring {
        ExchangeSettings settings = ExchangeSettings.DEFAULTS;
        StockRegistry registry = REGISTRY;
        final Map<String, Double> shocks = new HashMap<>();
        ItemCatalog items = ItemCatalog.OPEN;

        @Override public ExchangeSettings settings() { return settings; }
        @Override public StockRegistry registry(SimContext context) { return registry; }
        @Override public ItemCatalog items(SimContext context) { return items; }
        @Override public double shock(List<String> keys, long now) {
            double result = 1.0D;
            for (String key : keys) result *= shocks.getOrDefault(key, 1.0D);
            return result;
        }
    }

    private TradeWorld world;

    @AfterEach void restoreClock() { if (world != null) world.close(); }

    private static SimPipeline pipeline(ExchangeStep.Wiring wiring) {
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new ExchangeStep(wiring));
        return pipeline;
    }

    private static EconomyStore store(int seed) {
        EconomyStore store = new EconomyStore();
        SimTesting.fixSecret(store, SimTesting.secret(seed));
        return store;
    }

    private static JsonElement exchange(EconomyStore store) {
        return ExchangeSection.CODEC.encodeStart(JsonOps.INSTANCE, store.exchange()).getOrThrow();
    }

    private static long price(EconomyStore store, StockDefinition definition) {
        return store.exchange().state(definition.id()).price();
    }

    // --- the handler in the pipeline -------------------------------------------------------------

    @Test void theGameRegistersTheHandlerAfterHousekeepingAndItIsNotHousekeeping() {
        ExchangeStep step = new ExchangeStep(new TestWiring());
        assertEquals("exchange_prices", step.name());
        assertFalse(step.housekeeping());
        // The game's own pipeline starts with the housekeeping handlers, whatever is added after.
        assertEquals(List.of(Housekeeping.RESERVE.name(), Housekeeping.DAY.name(), Housekeeping.EXPIRY.name()),
                Simulation.PIPELINE.names().subList(0, 3));
    }

    @Test void oneStepOfTheSimulationIsOneUpdateOfEveryPrice() {
        EconomyStore store = store(1);
        SimPipeline pipeline = pipeline(new TestWiring());
        // The first call starts the count and replays nothing: nothing is listed yet.
        SimRunner.advance(store, null, pipeline, FIRST * STEP, SETTINGS, null);
        assertTrue(store.exchange().isEmpty());
        SimRunner.advance(store, null, pipeline, (FIRST + 1L) * STEP, SETTINGS, null);
        assertEquals(3, store.exchange().size());
        assertEquals(FIRST + 1L, store.exchange().lastStep());
        assertEquals(1, store.exchange().state(GEMS.id()).history().recentSize());
        long after = price(store, ROCKET);
        // Later within the same step nothing moves.
        SimRunner.advance(store, null, pipeline, (FIRST + 1L) * STEP + 599_999L, SETTINGS, null);
        assertEquals(after, price(store, ROCKET));
        assertEquals(1, store.exchange().state(ROCKET.id()).history().recentSize());
    }

    @Test void theSameSecretGivesTheSamePricesAndAnotherSecretOtherPrices() {
        EconomyStore one = store(1);
        EconomyStore two = store(1);
        EconomyStore other = store(2);
        for (EconomyStore store : List.of(one, two, other)) {
            SimPipeline pipeline = pipeline(new TestWiring());
            for (long step = FIRST; step <= FIRST + 200L; step++)
                SimRunner.advance(store, null, pipeline, step * STEP, SETTINGS, null);
        }
        assertEquals(exchange(one), exchange(two));
        assertNotEquals(price(one, ROCKET), price(other, ROCKET));
        assertNotEquals(exchange(one), exchange(other));
    }

    @Test void catchingUpAfterAPauseGivesExactlyThePricesOfAnUninterruptedRun() {
        long last = FIRST + 432L;
        // One server never stops: a step every ten minutes.
        EconomyStore steady = store(3);
        SimPipeline steadyPipeline = pipeline(new TestWiring());
        for (long step = FIRST; step <= last; step++)
            SimRunner.advance(steady, null, steadyPipeline, step * STEP, SETTINGS, null);

        // The other runs thirty steps, is shut down for the rest of three days, and catches up two
        // steps per tick — with a restart in the middle of the catch-up for good measure.
        EconomyStore paused = store(3);
        SimPipeline pausedPipeline = pipeline(new TestWiring());
        for (long step = FIRST; step <= FIRST + 30L; step++)
            SimRunner.advance(paused, null, pausedPipeline, step * STEP, SETTINGS, null);
        long now = last * STEP + 1_234L;
        int ticks = 0;
        while (SimRunner.advance(paused, null, pausedPipeline, now, SETTINGS, null).ran() > 0) {
            if (++ticks == 70) paused = StoreTesting.reload(paused);
        }
        assertEquals(201, ticks);
        assertEquals(last, paused.exchange().lastStep());
        assertEquals(exchange(steady), exchange(paused));
        for (StockDefinition definition : REGISTRY.stocks())
            assertEquals(price(steady, definition), price(paused, definition));
    }

    @Test void withTheAutonomousEconomyOffOrTheExchangeOffPricesStandStill() {
        EconomyStore store = store(4);
        TestWiring wiring = new TestWiring();
        SimPipeline pipeline = pipeline(wiring);
        for (long step = FIRST; step <= FIRST + 20L; step++)
            SimRunner.advance(store, null, pipeline, step * STEP, SETTINGS, null);
        JsonElement before = exchange(store);
        // autonomousEconomy=false: the handler is not housekeeping, so it does not run.
        for (long step = FIRST + 21L; step <= FIRST + 40L; step++)
            SimRunner.advance(store, null, pipeline, step * STEP, HOUSEKEEPING_ONLY, null);
        assertEquals(before, exchange(store));
        // exchangeEnabled=false: it runs and does nothing.
        wiring.settings = ExchangeSettings.DEFAULTS.withEnabled(false);
        for (long step = FIRST + 41L; step <= FIRST + 60L; step++)
            SimRunner.advance(store, null, pipeline, step * STEP, SETTINGS, null);
        assertEquals(before, exchange(store));
        // Back on: prices move again, from where they were.
        wiring.settings = ExchangeSettings.DEFAULTS;
        SimRunner.advance(store, null, pipeline, (FIRST + 61L) * STEP, SETTINGS, null);
        assertNotEquals(before, exchange(store));
        assertEquals(FIRST + 61L, store.exchange().lastStep());
    }

    @Test void dataThatCouldNotBeReadLeavesTheExchangeAsItIs() {
        EconomyStore store = store(5);
        TestWiring wiring = new TestWiring();
        SimPipeline pipeline = pipeline(wiring);
        for (long step = FIRST; step <= FIRST + 5L; step++)
            SimRunner.advance(store, null, pipeline, step * STEP, SETTINGS, null);
        JsonElement before = exchange(store);
        wiring.registry = StockRegistry.UNAVAILABLE;
        SimRunner.advance(store, null, pipeline, (FIRST + 6L) * STEP, SETTINGS, null);
        assertEquals(before, exchange(store));
        assertTrue(store.exchange().delisted().isEmpty());
    }

    // --- shocks ----------------------------------------------------------------------------------

    @Test void aShockOnACompanyOrOnWhatItIsLinkedToReachesItsPriceAndNoOther() {
        EconomyStore calm = store(6);
        EconomyStore shocked = store(6);
        TestWiring wiring = new TestWiring();
        // One shock on a linked tag, one on a company's own id.
        wiring.shocks.put("#c:gems", 2.0D);
        wiring.shocks.put(ROCKET.id(), 0.5D);
        SimPipeline calmPipeline = pipeline(new TestWiring());
        SimPipeline shockedPipeline = pipeline(wiring);
        for (long step = FIRST; step <= FIRST + 3L; step++) {
            SimRunner.advance(calm, null, calmPipeline, step * STEP, SETTINGS, null);
            SimRunner.advance(shocked, null, shockedPipeline, step * STEP, SETTINGS, null);
        }
        assertTrue(price(shocked, GEMS) > price(calm, GEMS));
        assertTrue(price(shocked, ROCKET) < price(calm, ROCKET));
        assertEquals(price(calm, IRON), price(shocked, IRON));
    }

    // --- traded volume ---------------------------------------------------------------------------

    /** Sells {@code count} diamonds from one player to another at {@code price} each, through the book. */
    private void trade(UUID seller, UUID buyer, String item, int count, long price) {
        AccountId selling = TradeWorld.account(seller);
        AccountId buying = TradeWorld.account(buyer);
        world.stock(seller, Lot.of(item, count));
        assertTrue(world.store.book().placeSell(world.env, selling, new Lot(item, count, Lot.PRISTINE), price,
                OrderSource.VAULT, VaultGoods.origin(world.store, world.env, selling), null).ok());
        assertEquals(count, world.store.book().placeBuy(world.env, buying, item, count, price, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, buying), null).filled());
    }

    /** A world whose first exchange step has been run, with whatever was traded before it. */
    private long[] pricesAfterTrading(String item, int count, long price, int pairs) {
        world = new TradeWorld();
        SimTesting.fixSecret(world.store, SimTesting.secret(7));
        TestWiring wiring = new TestWiring();
        // The catalogue of a real game: diamonds are in the gems tag, and nothing else is.
        wiring.items = new ItemCatalog() {
            @Override public boolean known(String id) { return true; }
            @Override public boolean tradeable(String id) { return true; }
            @Override public Set<String> tagsOf(String id) {
                return TradeWorld.DIAMOND.equals(id) ? Set.of("c:gems") : Set.of();
            }
            @Override public boolean tagKnown(String tag) { return "c:gems".equals(tag); }
        };
        SimPipeline pipeline = pipeline(wiring);
        SimRunner.advance(world.store, null, pipeline, FIRST * STEP, SETTINGS, null);
        for (int pair = 0; pair < pairs; pair++)
            trade(world.player(10 + 2 * pair, 1_000_000L), world.player(11 + 2 * pair, 1_000_000L), item, count, price);
        SimRunner.advance(world.store, null, pipeline, (FIRST + 1L) * STEP, SETTINGS, null);
        long[] prices = {price(world.store, GEMS), price(world.store, IRON), price(world.store, ROCKET)};
        world.close();
        world = null;
        return prices;
    }

    @Test void tradeInALinkedItemNudgesThatCompanyOnlyAndIsCappedPerPairOfAccounts() {
        long[] quiet = pricesAfterTrading(TradeWorld.DIAMOND, 0, 0L, 0);
        // 250 credits between two players: the whole of what one pair may count.
        long[] capped = pricesAfterTrading(TradeWorld.DIAMOND, 25, 10L, 1);
        // 20000 credits between the same two players counts no more than that.
        long[] flooded = pricesAfterTrading(TradeWorld.DIAMOND, 2_000, 10L, 1);
        assertTrue(capped[0] > quiet[0], "trade in diamonds lifts the gem company");
        assertEquals(capped[0], flooded[0]);
        // 250 of the 2000 credits that give the whole half percent: a sixteenth of a percent.
        assertEquals(Math.round(quiet[0] * (1.0D + 0.005D * 250.0D / 2_000.0D)), capped[0], 1L);
        // The companies that are not linked to diamonds did not move for it.
        assertEquals(quiet[1], capped[1]);
        assertEquals(quiet[2], flooded[2]);

        // Eight pairs of accounts trading 20000 credits each reach the cap of the nudge, and no more.
        long[] crowd = pricesAfterTrading(TradeWorld.DIAMOND, 2_000, 10L, 8);
        long[] bigger = pricesAfterTrading(TradeWorld.DIAMOND, 2_000, 10L, 12);
        assertEquals(Math.round(quiet[0] * 1.005D), crowd[0], 1L);
        assertEquals(crowd[0], bigger[0]);
        assertTrue((double) bigger[0] / quiet[0] <= 1.005D + 1.0e-4D);

        // Trade in something no company is linked to moves nothing.
        long[] gold = pricesAfterTrading(TradeWorld.GOLD, 2_000, 10L, 8);
        assertEquals(quiet[0], gold[0]);
        assertEquals(quiet[1], gold[1]);
    }

    @Test void theTallyIsTakenEveryStepSoOldTradeIsNeverCountedLater() {
        world = new TradeWorld();
        SimTesting.fixSecret(world.store, SimTesting.secret(8));
        TestWiring wiring = new TestWiring();
        wiring.settings = ExchangeSettings.DEFAULTS.withEnabled(false);
        SimPipeline pipeline = pipeline(wiring);
        SimRunner.advance(world.store, null, pipeline, FIRST * STEP, SETTINGS, null);
        trade(world.player(1, 100_000L), world.player(2, 100_000L), TradeWorld.DIAMOND, 10, 10L);
        // The exchange is off: the step still empties the tally.
        SimRunner.advance(world.store, null, pipeline, (FIRST + 1L) * STEP, SETTINGS, null);
        assertTrue(world.store.book().drainTradedVolume(1_000L, 1_000L).isEmpty());
        assertTrue(world.invariants());
    }
}
