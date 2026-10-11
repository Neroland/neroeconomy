package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.exchange.ActionLimiter;
import za.co.neroland.neroeconomy.exchange.ExchangeEnv;
import za.co.neroland.neroeconomy.exchange.ExchangeSettings;
import za.co.neroland.neroeconomy.exchange.ExchangeStep;
import za.co.neroland.neroeconomy.exchange.ExchangeSteps;
import za.co.neroland.neroeconomy.exchange.ExchangeTesting;
import za.co.neroland.neroeconomy.exchange.StockDefinition;
import za.co.neroland.neroeconomy.exchange.StockRegistry;
import za.co.neroland.neroeconomy.exchange.TradingTerms;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.house.HouseDealSettings;
import za.co.neroland.neroeconomy.house.HouseDealsEnv;
import za.co.neroland.neroeconomy.house.HouseDealsStep;
import za.co.neroland.neroeconomy.house.HouseEnv;
import za.co.neroland.neroeconomy.house.HouseSettings;
import za.co.neroland.neroeconomy.house.HouseStep;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.simulation.Housekeeping;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimRunner;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeSettings;
import za.co.neroland.neroeconomy.trade.TradingSteps;

/**
 * K3 (prompt section 16.5): what a large server's economy costs, timed with plain {@code System.nanoTime}
 * through the game's own sections, pipeline and runner. 200 accounts, 5 000 open orders on the book,
 * 500 auto-trade rules, the twelve shipped companies (each account holding shares), and the
 * Merchant Houses with their auctions and jobs. Measured: one simulation step, a 72-hour catch-up,
 * placing into and sweeping a deep book, a server tick's rule evaluation, and the encoded size of
 * every store section.
 *
 * <p>Tagged {@code benchmark}, so the ordinary test run leaves it out; run it with
 * {@code gradlew :neoforge:26.2:test -Pbenchmarks --tests *EconomyBenchmark}. It prints its numbers
 * ({@code [bench]} lines) and asserts only that the world it measured is the one described.
 */
@Tag("benchmark")
class EconomyBenchmark {
    private static final int ACCOUNTS = 200;
    private static final int SELLS_EACH = 16;
    private static final int BUYS_EACH = 9;
    private static final int RULES = 500;
    private static final long STEP = 600_000L;
    /** 72 hours of ten-minute steps: the most a catch-up ever replays. */
    private static final int CATCH_UP = 432;
    private static final int ITEMS = 20;
    private static final long START_WALL = 1_800_000_000_000L;

    private long wall = START_WALL;
    private final List<UUID> players = new ArrayList<>();
    private EconomyStore store = new EconomyStore();
    private final StockRegistry registry = ExchangeTesting.shipped();
    private final TradeEnv env = new TradeEnv(TradeSettings.fromConfig(), FuzzWorld.LIMIT, null, ItemCatalog.OPEN, null,
            new SystemDelivery() {
                @Override public boolean handles(SystemAccountId account) { return account != null && account.isHouse(); }
                @Override public void deliver(SystemAccountId account, Lot lot) { store.houses().deliver(account, lot); }
            });
    private final ExchangeEnv exchangeEnv = new ExchangeEnv(TradingTerms.DEFAULTS, registry, (account, kind) -> { },
            new ActionLimiter(), new ActionLimiter());
    private final SimPipeline pipeline = new SimPipeline();

    @AfterEach void realClockAgain() { StoreTesting.wallClock(null); }

    private static String item(int index) { return "bench:item_" + index; }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    private HouseEnv houseEnv() {
        return new HouseEnv(FuzzWorld.COMMODITIES, FuzzWorld.HOUSES, env, HouseSettings.DEFAULTS, quotes -> Set.of());
    }

    /** Goods from a hand that always has them: the benchmark counts the book, not the inventory. */
    private static final GoodsOrigin HAND = new GoodsOrigin() {
        @Override public boolean holds(Lot lot) { return true; }
        @Override public boolean take(Lot lot) { return true; }
        @Override public void restore(Lot lot) { }
    };

    private static final GoodsReceiver INVENTORY = new GoodsReceiver() {
        @Override public boolean canTake(Lot lot) { return true; }
        @Override public boolean give(Lot lot) { return false; }
    };

    private SimRunner.Progress advance(EconomyStore target, int maxSteps) {
        return SimRunner.advance(target, null, pipeline, target.ledger().now(),
                new SimRunner.Settings(STEP, Math.max(1, maxSteps), Math.max(1, maxSteps), true),
                (handler, failure) -> { throw new AssertionError(handler, failure); });
    }

    /** The world: built once, then copied through the store's codec for each measurement. */
    private long[] build() {
        StoreTesting.wallClock(() -> this.wall);
        SimTesting.fixSecret(store, SimTesting.secret(165));
        store.ledger().seedReserve(Ledgers.RESERVE);
        Housekeeping.register(pipeline);
        TradingSteps.register(pipeline, context -> env);
        pipeline.register(new ExchangeStep(new ExchangeStep.Wiring() {
            @Override public ExchangeSettings settings() { return ExchangeSettings.DEFAULTS; }
            @Override public StockRegistry registry(SimContext context) { return registry; }
            @Override public ItemCatalog items(SimContext context) { return ItemCatalog.OPEN; }
            @Override public double shock(List<String> keys, long now) { return 1.0D; }
        }));
        ExchangeSteps.register(pipeline, context -> exchangeEnv);
        pipeline.register(new HouseStep(context -> houseEnv()));
        HouseDealsStep.register(pipeline, context -> new HouseDealsEnv(houseEnv(), FuzzWorld.AUCTION_TERMS,
                HouseDealSettings.DEFAULTS, FuzzWorld.BOUNTIES, FuzzWorld.DESTINATION));
        store.exchange().reconcile(registry);
        // The first observation records where the simulation starts.
        advance(store, 1);

        EconomySavedData ledger = store.ledger();
        for (int index = 0; index < ACCOUNTS; index++) {
            UUID player = new UUID(0xBE7CL, index + 1L);
            players.add(player);
            assertTrue(ledger.deposit(account(player), NeroEconomyCommon.CREDITS, 1_000_000L, TransactionType.ADMIN_GRANT));
        }
        // Shares: every account holds one of the twelve companies.
        List<StockDefinition> stocks = new ArrayList<>(registry.stocks());
        int holders = 0;
        for (int index = 0; index < ACCOUNTS; index++)
            if (store.exchange().desk().buyForCredits(exchangeEnv, account(players.get(index)),
                    stocks.get(index % stocks.size()).id(), 2_000L).ok()) holders++;
        // 5 000 orders that rest: asks from 500 up, bids up to 200, over twenty items.
        long placeNanos = 0L;
        int placed = 0;
        for (int index = 0; index < ACCOUNTS; index++) {
            AccountId owner = account(players.get(index));
            for (int k = 0; k < SELLS_EACH; k++) {
                long started = System.nanoTime();
                Placed done = store.book().placeSell(env, owner, new Lot(item((index + k) % ITEMS), 1 + k % 8, Lot.PRISTINE),
                        500L + (index * 7L + k * 13L) % 400L, OrderSource.HAND, HAND, null);
                placeNanos += System.nanoTime() - started;
                assertTrue(done.ok() && done.rested() > 0, "sell " + index + "/" + k);
                placed++;
            }
            for (int k = 0; k < BUYS_EACH; k++) {
                long started = System.nanoTime();
                Placed done = store.book().placeBuy(env, owner, item((index * 3 + k) % ITEMS), 1 + k % 10,
                        1L + (index * 3L + k * 11L) % 200L, OrderSource.VAULT, false, INVENTORY, null);
                placeNanos += System.nanoTime() - started;
                assertTrue(done.ok() && done.rested() > 0, "buy " + index + "/" + k);
                placed++;
            }
        }
        // 500 rules: two for every account and a third for half of them; buy rules bid low, sell
        // rules (from goods in the vault) ask high, so they rest beside the orders.
        RulesSection rules = store.rules();
        for (int number = 0; number < RULES; number++) {
            int index = number % ACCOUNTS;
            AccountId owner = account(players.get(index));
            String target = item((index + number) % ITEMS);
            boolean buy = number % 2 == 0;
            if (!buy) store.vault().deliver(owner, List.of(new Lot(target, 32, Lot.PRISTINE)), env.settings().vaultCap(),
                    env.settings().vaultHardBound(), FuzzWorld.LIMIT);
            RuleOutcome added = rules.add(env, owner, buy ? RuleSide.BUY : RuleSide.SELL, false, target,
                    buy ? 1L + number % 150 : 1_000L + number % 300, 16, buy ? 2_000L : 32L, RulesSection.VAULT, null);
            assertTrue(added.ok(), "rule " + number + ": " + added.result());
        }
        return new long[] {placed, placeNanos, holders};
    }

    /** The world as built, before any measurement touched it. */
    private EconomyStore world;

    private EconomyStore copy() { return StoreTesting.reload(world); }

    @Test void aLargeServer() throws IOException {
        long[] built = build();
        this.world = store;
        System.out.println("[bench] machine: " + Runtime.getRuntime().availableProcessors() + " cores, java "
                + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + "), "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", max heap "
                + Runtime.getRuntime().maxMemory() / 1_048_576L + " MB");
        assertEquals(ACCOUNTS * (SELLS_EACH + BUYS_EACH), (int) built[0]);
        assertEquals(RULES, store.rules().size());
        System.out.println("[bench] world: " + ACCOUNTS + " accounts, " + store.book().size() + " orders on the book, "
                + store.rules().size() + " rules (their own orders go on the book when they are first evaluated), "
                + registry.stocks().size() + " companies, " + built[2] + " accounts holding shares, "
                + FuzzWorld.COMMODITIES.commodities().size() + " House commodities");
        System.out.printf("[bench] placing %d resting orders into the growing book: %.1f µs each on average%n",
                built[0], built[1] / 1_000.0D / built[0]);
        assertTrue(store.checkInvariants());

        System.out.println("[bench] sizes as built (before any step; the Houses and deals sections are still empty):");
        sizes(store);

        // --- a server tick's rule evaluation: every rule queued, 64 a tick ----------------------
        // Throw-away passes first (rules, then sweeps), so the numbers are not the JIT's.
        store = copy();
        store.rules().enqueueAll();
        for (long tick = 1L; tick < 1_000L && store.rules().evaluatePending(env, tick) > 0; tick++) { }
        store = copy();
        for (int index = 0; index < 200; index++)
            store.book().placeBuy(env, account(players.get(index)), item(index % ITEMS), 24, 2_000L, OrderSource.HAND,
                    false, INVENTORY, null);
        EconomyStore ruled = copy();
        store = ruled;
        ruled.rules().enqueueAll();
        List<Long> tickNanos = new ArrayList<>();
        int evaluated = 0;
        for (long tick = 1L; tick < 1_000L; tick++) {
            long started = System.nanoTime();
            int done = ruled.rules().evaluatePending(env, tick);
            long taken = System.nanoTime() - started;
            if (done == 0) break;
            tickNanos.add(taken);
            evaluated += done;
        }
        System.out.println("[bench] rules: " + evaluated + " evaluations over " + tickNanos.size() + " ticks; per tick "
                + stats(tickNanos));

        // --- matching: 200 buys that sweep the cheapest asks of an item -------------------------
        EconomyStore matched = copy();
        store = matched;
        List<Long> sweepNanos = new ArrayList<>();
        int fills = 0;
        for (int index = 0; index < 200; index++) {
            AccountId buyer = account(players.get((index * 37) % ACCOUNTS));
            long started = System.nanoTime();
            Placed done = matched.book().placeBuy(env, buyer, item(index % ITEMS), 24, 2_000L, OrderSource.HAND, false,
                    INVENTORY, null);
            sweepNanos.add(System.nanoTime() - started);
            assertTrue(done.ok(), "sweep " + index);
            fills += done.fills().size();
        }
        System.out.println("[bench] matching: 200 buys of 24 units sweeping the asks (" + fills + " fills, "
                + String.format("%.1f", fills / 200.0D) + " a buy); per buy " + stats(sweepNanos));
        assertTrue(matched.checkInvariants());

        // --- one simulation step, over and over (the first 50 warm the JIT and are not counted) --
        JsonElement snapshot = StoreTesting.encode(copy());
        long snapshotWall = wall;
        EconomyStore stepped = StoreTesting.decode(snapshot);
        store = stepped;
        List<Long> stepNanos = new ArrayList<>();
        for (int step = 0; step < 250; step++) {
            wall += STEP;
            long started = System.nanoTime();
            SimRunner.Progress progress = advance(stepped, 1);
            long taken = System.nanoTime() - started;
            assertEquals(1, progress.ran(), "step " + step);
            if (step >= 50) stepNanos.add(taken);
        }
        System.out.println("[bench] one simulation step (whole pipeline, 200 steps after 50 warm-up): " + stats(stepNanos)
                + "; book after " + stepped.book().size() + " orders");
        assertTrue(stepped.checkInvariants());
        System.out.println("[bench] sizes after those 250 steps (about 42 hours: Houses trading, deals running):");
        sizes(stepped);

        // --- a 72-hour catch-up from the same snapshot, in one call, three times ---------------
        List<Long> catchUps = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            wall = snapshotWall;
            EconomyStore behind = StoreTesting.decode(snapshot);
            store = behind;
            behind.ledger().now();
            wall = snapshotWall + CATCH_UP * STEP;
            long started = System.nanoTime();
            SimRunner.Progress progress = advance(behind, CATCH_UP);
            catchUps.add(System.nanoTime() - started);
            assertEquals(CATCH_UP, progress.ran(), "catch-up " + run);
            assertTrue(behind.checkInvariants());
        }
        long bestCatchUp = catchUps.stream().mapToLong(Long::longValue).min().orElseThrow();
        System.out.printf("[bench] 72-hour catch-up (%d steps in one call): best %.1f ms, runs %s ms; %.1f µs a step%n",
                CATCH_UP, bestCatchUp / 1e6, catchUps.stream().map(n -> String.format("%.1f", n / 1e6)).toList(),
                bestCatchUp / 1e3 / CATCH_UP);
    }

    /** Best, median, 95th percentile and worst, in microseconds. */
    private static String stats(List<Long> nanos) {
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) return "n/a";
        return String.format("best %.1f µs, median %.1f µs, p95 %.1f µs, worst %.1f µs (n=%d)", sorted[0] / 1e3,
                sorted[sorted.length / 2] / 1e3, sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95D))] / 1e3,
                sorted[sorted.length - 1] / 1e3, sorted.length);
    }

    /** Each section of the store as it goes to disk: NBT, raw and gzip-compressed, and as JSON. */
    private static void sizes(EconomyStore target) throws IOException {
        CompoundTag whole = (CompoundTag) EconomyStore.CODEC.encodeStart(NbtOps.INSTANCE, target).getOrThrow();
        JsonObject json = StoreTesting.encode(target).getAsJsonObject();
        List<String> keys = new ArrayList<>(whole.keySet());
        keys.sort(null);
        for (String key : keys) {
            CompoundTag one = new CompoundTag();
            one.put(key, whole.get(key));
            System.out.printf("[bench] size %-16s nbt %,9d B  gzip %,8d B  json %,9d chars%n", key, raw(one),
                    gzip(one), json.has(key) ? json.get(key).toString().length() : 0);
        }
        System.out.printf("[bench] size %-16s nbt %,9d B  gzip %,8d B  json %,9d chars%n", "(whole store)", raw(whole),
                gzip(whole), json.toString().length());
        assertTrue(Arrays.asList("ledger", "rules").stream().allMatch(keys::contains), keys.toString());
    }

    private static int raw(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.size();
    }

    private static int gzip(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.size();
    }
}
