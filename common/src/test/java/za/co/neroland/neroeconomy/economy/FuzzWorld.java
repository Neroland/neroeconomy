package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.direct.TradeEscrowSection;
import za.co.neroland.neroeconomy.direct.TradeSession;
import za.co.neroland.neroeconomy.exchange.ActionLimiter;
import za.co.neroland.neroeconomy.exchange.ExchangeEnv;
import za.co.neroland.neroeconomy.exchange.ExchangeSection;
import za.co.neroland.neroeconomy.exchange.ExchangeSettings;
import za.co.neroland.neroeconomy.exchange.ExchangeStep;
import za.co.neroland.neroeconomy.exchange.ExchangeSteps;
import za.co.neroland.neroeconomy.exchange.StockDefinition;
import za.co.neroland.neroeconomy.exchange.StockRegistry;
import za.co.neroland.neroeconomy.exchange.TradingTerms;
import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.house.HouseEnv;
import za.co.neroland.neroeconomy.house.HouseRegistry;
import za.co.neroland.neroeconomy.house.HouseSettings;
import za.co.neroland.neroeconomy.house.HouseStep;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.goods.StackLimit;
import za.co.neroland.neroeconomy.simulation.Housekeeping;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimRandom;
import za.co.neroland.neroeconomy.simulation.SimRunner;
import za.co.neroland.neroeconomy.simulation.SimStep;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeSettings;
import za.co.neroland.neroeconomy.trade.TradingSteps;
import za.co.neroland.neroeconomy.trader.FakeSlots;
import za.co.neroland.neroeconomy.trader.TraderRules;
import za.co.neroland.neroeconomy.vault.VaultSection;

/**
 * Everything one fuzz run works on: the real store, and beside it three small models of what must
 * be true, each kept from the outcomes of the operations alone and never read back from the store.
 *
 * <ul>
 *   <li>{@link #supply} — minted less burned. <b>Invariant A:</b> every balance together equals it.</li>
 *   <li>{@link #escrow} — who is owed what out of the escrow account. <b>Invariant B:</b> the escrow
 *       balance equals the sum of it plus whatever the store's own sections record.</li>
 *   <li><b>Invariant C</b> needs no model: the exchange account holds exactly what the exchange
 *       section records for open positions — share cost, bond principal, reserved bond interest —
 *       and nothing but the exchange's own operations ever moves a credit into or out of it.</li>
 *   <li>{@link #goods} and {@link #outside} — how many of each kind of goods exist, and how many
 *       of them are outside the store (in inventories, as far as the model cares). <b>Item
 *       conservation:</b> for every kind, what the store holds plus what is outside equals what
 *       exists; only an operation that explicitly creates or destroys goods changes the total.</li>
 * </ul>
 *
 * <p>Two things live in the world rather than in the store, as they do in the game: the slots of
 * the Auto-Trader blocks ({@link #traders}) — whose contents are counted where they lie, as a third
 * place goods can be — and the direct trades that are open ({@link #trades}), which no restart
 * survives.
 */
final class FuzzWorld {
    static final int PLAYERS = 36;
    /** A small vault, so that the cap and the over-cap rules are hit all the time. */
    static final int CAP = 18;
    static final int BOUND = CAP * 4;
    static final long STEP_MILLIS = 600_000L;
    static final long DAY_MILLIS = 86_400_000L;
    static final String[] ITEMS = {"minecraft:diamond", "minecraft:ender_pearl", "minecraft:diamond_sword",
            "minecraft:oak_log", "minecraft:iron_ingot"};
    static final int PAYLOADS = 3;
    static final StackLimit LIMIT = (item, payload) -> switch (item) {
        case "minecraft:ender_pearl" -> 16;
        case "minecraft:diamond_sword" -> 1;
        default -> 64;
    };
    /**
     * Every system account an ordinary ledger operation may touch. Escrow moves only through the
     * escrow model, and the exchange account only through the exchange's own operations.
     */
    static final SystemAccountId[] SYSTEM = {SystemAccountId.RESERVE, SystemAccountId.house("quarry"),
            SystemAccountId.house("ironworks"), SystemAccountId.house("granary")};
    /**
     * The companies of the world's exchange: a steady one that pays a dividend, a risky one, and a
     * dear one that grows fast enough to split. None is linked to a commodity, so no price depends
     * on the order book's volume tally, which is not saved.
     */
    static final List<StockDefinition> STOCKS = List.of(
            new StockDefinition("fuzz:calm", "stock.fuzz.calm", "stock.fuzz.calm.description", "farming",
                    StockType.STEADY, 20L, 3.0D, 6.0D, 2.0D, List.of(), true),
            new StockDefinition("fuzz:wild", "stock.fuzz.wild", "stock.fuzz.wild.description", "space",
                    StockType.RISKY, 60L, 20.0D, 20.0D, 0.0D, List.of(), true),
            new StockDefinition("fuzz:pricey", "stock.fuzz.pricey", "stock.fuzz.pricey.description", "mining",
                    StockType.RISKY, 1_900L, 10.0D, 40.0D, 0.7D, List.of(), true));
    /**
     * The exchange's terms: a two-minute holding period so sales happen, small limits and a tight
     * rate limit so they are hit all the time, three bonds an account, 2000 play credits.
     */
    static final TradingTerms EXCHANGE_TERMS = new TradingTerms(true, 100, 100, 200, 100, 120_000L, 4_000L, 8_000L, 3,
            80, 300, 800, 2_000L, 3);
    /** Who the escrow of a server-funded job is recorded for. */
    static final UUID SYSTEM_ISSUER = new UUID(0L, 0L);
    private static final long START_WALL = 1_800_000_000_000L;
    private static final Map<String, Integer> ITEM_INDEX = itemIndex();

    private static Map<String, Integer> itemIndex() {
        Map<String, Integer> index = new HashMap<>();
        for (int item = 0; item < ITEMS.length; item++) index.put(ITEMS[item], item);
        return index;
    }

    final long seed;
    final Random random;
    final List<UUID> players = new ArrayList<>();
    final SimPipeline pipeline = new SimPipeline();
    final SimRunner.Settings settings = new SimRunner.Settings(STEP_MILLIS, 432L, 2, true);
    /** The real store. Replaced by a reloaded copy at a crash point. */
    EconomyStore store = new EconomyStore();
    /** The wall clock the ledger reads; only operations move it. */
    long wall = START_WALL;
    long supply;
    final Map<UUID, Long> escrow = new LinkedHashMap<>();
    long escrowTotal;
    final Map<Lot.Key, Long> goods = new LinkedHashMap<>();
    final Map<Lot.Key, Long> outside = new LinkedHashMap<>();
    /** A rolling hash of every operation's outcome: two runs that did the same things agree on it. */
    long digest;
    long stepsRun;
    long stepsSkipped;
    /**
     * The Auto-Trader blocks of the world, by location. They are the world's, not the store's: a
     * crash of the store leaves them as they were, as a chunk on disk is left.
     */
    final Map<String, FakeSlots> traders = new LinkedHashMap<>();
    /** How often a rule wrote a slot of a block that has since been removed; see {@link #slotWrites}. */
    long writesOfRemovedBlocks;
    /** The direct trades open right now. Not saved anywhere: a trade lasts as long as its window. */
    final List<TradeSession> trades = new ArrayList<>();
    /** How many stacks a restart found in trade escrow and returned to their owners' vaults. */
    long tradeStacksReturned;
    /**
     * What the trading rules are given: small caps so they are hit all the time, a short order
     * life so expiry happens, and a price modifier that surcharges some buyers and discounts
     * others, so every branch of a fill's settlement is taken.
     */
    final TradeEnv env = new TradeEnv(new TradeSettings(2.0D, 1L, 5, 4, 5L * 3_600_000L, CAP, CAP, BOUND, 3.0D,
            4L * 3_600_000L, 4, 6, 3, 400L, 8), LIMIT, this::buyerPrice, CATALOG, (account, kind) -> this.alerts++,
            houseDelivery(),
            TraderRules.binder(location -> this.traders.get(location)));
    /** How many alerts the trading rules raised; a count only, part of the fingerprint. */
    long alerts;
    /**
     * Which companies are listed right now. The world's, as a data pack is: a crash of the store
     * leaves it as it was.
     */
    final List<StockDefinition> listed = new ArrayList<>(STOCKS);
    /** The exchange's rate limiters. In memory only in the game too; kept across a crash so both runs agree. */
    final ActionLimiter exchangeLimiter = new ActionLimiter();
    final ActionLimiter practiceLimiter = new ActionLimiter();
    /** How many alerts the exchange raised, by kind: bonds matured, dividends paid, delistings paid out. */
    final Map<za.co.neroland.neroeconomy.link.EconomyAlerts.Kind, Long> exchangeAlerts =
            new java.util.EnumMap<>(za.co.neroland.neroeconomy.link.EconomyAlerts.Kind.class);
    /** How many times the fuzzer played the server tick's rule evaluation; each is its own tick. */
    long ruleTicks;
    /** Credits' worth of fills the rules made by acting; shows the run really traded through them. */
    long ruleTraded;

    FuzzWorld(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
        // From here on every ledger reads this world's clock, so the run is repeatable from its seed.
        StoreTesting.wallClock(() -> this.wall);
        for (int index = 0; index < PLAYERS; index++) players.add(new UUID(seed, index + 1L));
        for (String item : ITEMS) for (int payload = 0; payload < PAYLOADS; payload++) {
            goods.put(new Lot.Key(item, payload), 0L);
            outside.put(new Lot.Key(item, payload), 0L);
        }
        SimTesting.fixSecret(store, SimTesting.secret((int) seed));
        supply = store.ledger().seedReserve(Ledgers.RESERVE);
        Housekeeping.register(pipeline);
        TradingSteps.register(pipeline, context -> env);
        pipeline.register(new MerchantStandIn());
        // The exchange, as the game registers it: prices, then dividends, then maturities and payouts.
        pipeline.register(new ExchangeStep(new ExchangeStep.Wiring() {
            @Override public ExchangeSettings settings() { return ExchangeSettings.DEFAULTS; }
            @Override public StockRegistry registry(SimContext context) { return FuzzWorld.this.registry(); }
            @Override public ItemCatalog items(SimContext context) { return CATALOG; }
            @Override public double shock(List<String> keys, long now) { return 1.0D; }
        }));
        ExchangeSteps.register(pipeline, context -> exchangeEnv());
        // The Merchant Houses, as the game registers them: after the exchange.
        pipeline.register(new HouseStep(context -> houseEnv()));
        store.exchange().reconcile(registry());
    }

    // --- the Merchant Houses -----------------------------------------------------------------

    /**
     * The world's commodities: logs and iron the Houses sell and buy, diamonds they only buy (a
     * sink) and ender pearls they only sell. Small caps, so the daily caps are hit all the time.
     */
    static final CommodityRegistry COMMODITIES = CommodityRegistry.of(List.of(
            new CommodityDefinition("minecraft:oak_log", CommodityDefinition.DIRECT_FILE, 4L, 256L, 256L, 32L, 32L, 0.9D,
                    0.05D, 1_000, CommodityClass.FARMABLE, "granary", "farming", true, true),
            new CommodityDefinition("minecraft:iron_ingot", CommodityDefinition.DIRECT_FILE, 12L, 64L, 64L, 16L, 16L,
                    0.6D, 0.03D, 1_500, CommodityClass.CRAFTED, "ironworks", "mining", true, true),
            new CommodityDefinition("minecraft:diamond", CommodityDefinition.DIRECT_FILE, 60L, 0L, 16L, 0L, 4L, 0.8D,
                    0.01D, 1_000, CommodityClass.RARE, "quarry", "mining", true, true),
            new CommodityDefinition("minecraft:ender_pearl", CommodityDefinition.DIRECT_FILE, 20L, 32L, 0L, 8L, 0L,
                    0.8D, 0.01D, 1_000, CommodityClass.RARE, "quarry", "", true, true)),
            CommodityCatalog.of(Set.of(), Set.of(ITEMS), Map.of(), Set.of()));
    static final HouseRegistry HOUSES = HouseRegistry.of(List.of(
            new HouseDefinition("granary", HouseDefinition.BUILT_IN_FILE, "house.fuzz.granary", List.of(), List.of(),
                    600L, 6.0D),
            new HouseDefinition("ironworks", HouseDefinition.BUILT_IN_FILE, "house.fuzz.ironworks", List.of(), List.of(),
                    800L, 6.0D),
            new HouseDefinition("quarry", HouseDefinition.BUILT_IN_FILE, "house.fuzz.quarry", List.of(), List.of(),
                    1_000L, 6.0D)));
    /** How often the daily guard re-check ran; every third one switches the log buy side off. */
    long guardChecks;

    /** What the Houses work with: small drift caps so they are hit, and a guard that sometimes fails logs. */
    HouseEnv houseEnv() {
        return new HouseEnv(COMMODITIES, HOUSES, env, new HouseSettings(100, 5_000, 20_000, 4L, 300L),
                quotes -> guardChecks++ % 3 == 1 ? Set.of("minecraft:oak_log") : Set.of());
    }

    /** The book delivers the Houses' goods into the House section of whichever store is current. */
    private SystemDelivery houseDelivery() {
        return new SystemDelivery() {
            @Override public boolean handles(SystemAccountId account) { return account != null && account.isHouse(); }
            @Override public void deliver(SystemAccountId account, Lot lot) { store.houses().deliver(account, lot); }
        };
    }

    /** The companies listed right now. */
    StockRegistry registry() { return StockRegistry.of(listed); }

    /** What every exchange operation is handed. */
    ExchangeEnv exchangeEnv() {
        return new ExchangeEnv(EXCHANGE_TERMS, registry(), (account, kind) -> exchangeAlerts.merge(kind, 1L, Long::sum),
                exchangeLimiter, practiceLimiter);
    }

    ExchangeSection exchange() { return store.exchange(); }

    /** One player in seven pays a tenth more than the seller asks; one in five, a tenth less. */
    private long buyerPrice(AccountId buyer, UUID listing, String item, long base, int available) {
        int index = buyer instanceof PlayerAccountId player ? (int) player.playerId().getLeastSignificantBits() : 0;
        if (index % 7 == 0) return base + (base + 9L) / 10L;
        if (index % 5 == 0) return Math.max(1L, base - base / 10L);
        return base;
    }

    /** The items the world trades in, with two tags over them for the rules that work by tag. */
    static final ItemCatalog CATALOG = new ItemCatalog() {
        @Override public boolean known(String item) { return ITEM_INDEX.containsKey(item); }
        @Override public boolean tradeable(String item) { return known(item); }
        @Override public Set<String> tagsOf(String item) {
            return switch (item) {
                case "minecraft:diamond", "minecraft:ender_pearl" -> Set.of(TAG_GEMS);
                case "minecraft:iron_ingot", "minecraft:oak_log" -> Set.of(TAG_BULK);
                default -> Set.of();
            };
        }
        @Override public boolean tagKnown(String tag) { return TAG_GEMS.equals(tag) || TAG_BULK.equals(tag); }
    };
    static final String TAG_GEMS = "fuzz:gems";
    static final String TAG_BULK = "fuzz:bulk";

    // --- reaching the store ------------------------------------------------------------------

    EconomySavedData ledger() { return store.ledger(); }
    VaultSection vault() { return store.vault(); }

    // --- random choices ----------------------------------------------------------------------

    UUID player() { return players.get(random.nextInt(PLAYERS)); }
    static AccountId account(UUID player) { return new PlayerAccountId(player); }

    /** A player or, one time in three, a system account other than escrow. */
    AccountId anyAccount() {
        return random.nextInt(3) == 0 ? SYSTEM[random.nextInt(SYSTEM.length)] : account(player());
    }

    /** Mostly small, sometimes large, sometimes zero, sometimes negative: every way an amount can be wrong. */
    long amount() {
        return switch (random.nextInt(8)) {
            case 0 -> 0L;
            case 1 -> random.nextInt(100_000);
            case 2 -> -1L - random.nextInt(50);
            default -> random.nextInt(300);
        };
    }

    Lot.Key kind() { return new Lot.Key(ITEMS[random.nextInt(ITEMS.length)], random.nextInt(PAYLOADS)); }

    /** A lot of a random kind, of up to two stacks' worth. */
    Lot lot() {
        Lot.Key kind = kind();
        return new Lot(kind.item(), 1 + random.nextInt(2 * LIMIT.limit(kind.item(), kind.payload())), kind.payload());
    }

    // --- the models --------------------------------------------------------------------------

    long owed(UUID player) { return escrow.getOrDefault(player, 0L); }

    /** Records that the escrow account now holds {@code change} more (or less) for {@code player}. */
    void owe(UUID player, long change) {
        long next = owed(player) + change;
        assertTrue(next >= 0L, "escrow model went negative");
        if (next == 0L) escrow.remove(player);
        else escrow.put(player, next);
        escrowTotal += change;
    }

    long outside(Lot.Key kind) { return outside.getOrDefault(kind, 0L); }

    /** Goods came into existence, outside the store. */
    void create(Lot.Key kind, long count) {
        goods.merge(kind, count, Long::sum);
        outside.merge(kind, count, Long::sum);
    }

    /** Goods outside the store ceased to exist. */
    void consume(Lot.Key kind, long count) {
        goods.merge(kind, -count, Long::sum);
        outside.merge(kind, -count, Long::sum);
    }

    /** Goods the store held were destroyed (an erasure). */
    void destroyedInStore(Lot.Key kind, long count) { goods.merge(kind, -count, Long::sum); }

    /** Goods the store made out of nothing (a stand-in for a Merchant House restocking). */
    void createdInStore(Lot.Key kind, long count) { goods.merge(kind, count, Long::sum); }

    /** Goods moved from outside into the store. */
    void intoStore(Lot.Key kind, long count) { outside.merge(kind, -count, Long::sum); }

    /** Goods moved out of the store. */
    void outOfStore(Lot.Key kind, long count) { outside.merge(kind, count, Long::sum); }

    /** Goods moved from outside into a slot of an Auto-Trader; the slots are counted where they lie. */
    void intoSlots(Lot.Key kind, long count) { outside.merge(kind, -count, Long::sum); }

    /** Goods taken out of a slot of an Auto-Trader, or dropped by one that was broken. */
    void outOfSlots(Lot.Key kind, long count) { outside.merge(kind, count, Long::sum); }

    /** Goods in the slots of an Auto-Trader ceased to exist: a command replaced the block. */
    void destroyedInSlots(Lot.Key kind, long count) { goods.merge(kind, -count, Long::sum); }

    /** How often the rules wrote a slot of an Auto-Trader: listed goods taken out, purchases put in. */
    long slotWrites() {
        long writes = writesOfRemovedBlocks;
        for (FakeSlots slots : traders.values()) writes += slots.writes;
        return writes;
    }

    /** The direct trade an account is in, or null. */
    TradeSession tradeOf(AccountId account) {
        for (TradeSession session : trades) if (session.sideOf(account) != null) return session;
        return null;
    }

    // --- the invariants ----------------------------------------------------------------------

    /** Checked after every operation, successful or refused. */
    void check(String operation, int step) {
        // Only put together when something fails: this runs a hundred thousand times.
        Supplier<String> where = () -> "seed " + seed + ", step " + step + ", after " + operation;
        EconomySavedData ledger = ledger();
        // Invariant A: every balance together equals what was minted less what was burned.
        assertEquals(supply, ledger.totalBalancesForTest(), where);
        assertTrue(ledger.supplyBalanced(), where);
        assertTrue(ledger.balance(SystemAccountId.RESERVE) >= 0L, where);
        // Invariant B: the escrow account holds exactly what is recorded as owed from it.
        assertEquals(escrowTotal + store.escrowObligations(), ledger.balance(SystemAccountId.ESCROW), where);
        // Invariant C: the exchange account holds exactly the open share cost, bond principal and reserved interest.
        assertEquals(store.exchangeObligations(), ledger.balance(SystemAccountId.EXCHANGE), where);
        // Item conservation: for every kind, in the store plus outside equals what exists.
        // Counted into a plain array: this walks every lot in the store after every operation.
        long[] held = new long[ITEMS.length * PAYLOADS];
        store.forEachLot(lot -> held[slotOf(lot.item(), lot.payload())] += lot.count());
        // The slots of the Auto-Trader blocks are a place of their own: neither the store nor a hand.
        for (FakeSlots slots : traders.values())
            for (Lot lot : slots.held) if (lot != null) held[slotOf(lot.item(), lot.payload())] += lot.count();
        // The Merchant Houses make goods when they restock and destroy what they cannot keep: both counted.
        za.co.neroland.neroeconomy.house.HouseSection houses = store.houses();
        for (Map.Entry<Lot.Key, Long> kind : goods.entrySet()) {
            long out = outside(kind.getKey());
            assertTrue(out >= 0L, where);
            long made = kind.getKey().pristine()
                    ? houses.created(kind.getKey().item()) - houses.destroyed(kind.getKey().item()) : 0L;
            assertEquals(kind.getValue() + made, held[slotOf(kind.getKey().item(), kind.getKey().payload())] + out,
                    () -> where.get() + ", " + kind.getKey());
        }
        // Trade escrow is only ever held for a side of a trade that is open: nothing is stranded in it.
        TradeEscrowSection tradeEscrow = store.tradeEscrow();
        if (!tradeEscrow.isEmpty())
            for (AccountId owner : tradeEscrow.owners()) assertTrue(tradeOf(owner) != null, where);
    }

    /** Where a kind of goods is counted. A kind the world does not know is a failure in itself. */
    private static int slotOf(String item, int payload) {
        Integer index = ITEM_INDEX.get(item);
        if (index == null || payload < 0 || payload >= PAYLOADS)
            throw new AssertionError("goods of an unknown kind in the store: " + item + " / " + payload);
        return index * PAYLOADS + payload;
    }

    // --- crash points ------------------------------------------------------------------------

    /**
     * Brings the clock's saved watermark up to date, as the minute-by-minute save of it does. Done
     * in both runs of a crash test, at the same points, so the two differ only in the reload.
     */
    void checkpoint() {
        wall += 60_000L;
        ledger().now();
    }

    /**
     * A crash and a restart: the store is written with its codecs and read into a fresh one. No
     * trade window survives that, so — as the game does when it loads the store — whatever the
     * sides of open trades had put in is returned to their own vaults. It must have come through
     * the reload exactly as it was, and all of it must arrive.
     */
    void crash() {
        Map<AccountId, List<Lot>> inEscrow = new LinkedHashMap<>();
        for (TradeSession session : trades) for (TradeSession.Side side : TradeSession.Side.values())
            inEscrow.put(session.account(side), store.tradeEscrow().held(session.account(side)));
        store = StoreTesting.reload(store);
        Map<AccountId, Map<Lot.Key, Long>> expected = new LinkedHashMap<>();
        inEscrow.forEach((owner, lots) -> {
            assertEquals(lots, store.tradeEscrow().held(owner), "trade escrow changed across a reload");
            Map<Lot.Key, Long> totals = new LinkedHashMap<>();
            for (Lot lot : lots) totals.merge(lot.key(), (long) lot.count(), Long::sum);
            totals.replaceAll((kind, count) -> count + vault().total(owner, kind.item(), kind.payload()));
            expected.put(owner, totals);
        });
        tradeStacksReturned += store.tradeEscrow().recover(env);
        assertTrue(store.tradeEscrow().isEmpty());
        expected.forEach((owner, totals) -> totals.forEach((kind, count) ->
                assertEquals(count, vault().total(owner, kind.item(), kind.payload()), "trade escrow not returned")));
        trades.clear();
    }

    /**
     * What the run that does not crash does at the same point: the server stops cleanly, every
     * open trade is cancelled and what its sides had put in goes back to their vaults. The two
     * runs must then be indistinguishable.
     */
    void stopTrades() {
        for (TradeSession session : trades) {
            session.cancel(TradeSession.CancelReason.STOPPED);
            for (TradeSession.Side side : TradeSession.Side.values())
                tradeStacksReturned += store.tradeEscrow().returnToVault(env, session.account(side)).size();
        }
        trades.clear();
    }

    /** Everything that matters about the world, as text: equal for two runs that ended the same. */
    String fingerprint() {
        StringBuilder out = new StringBuilder();
        EconomySavedData ledger = ledger();
        for (UUID player : players) out.append(ledger.getBalance(player, NeroEconomyCommon.CREDITS)).append('/')
                .append(ledger.locked(player)).append('/').append(ledger.unvested(player)).append('/')
                .append(owed(player)).append('/').append(vault().stacks(player)).append(';');
        for (SystemAccountId system : SYSTEM) out.append(ledger.balance(system)).append(';');
        // The exchange: what its account holds, every price, every portfolio and every practice sandbox.
        out.append(ledger.balance(SystemAccountId.EXCHANGE)).append(';').append(exchangeAlerts).append(';')
                .append(listed.size()).append(';').append(ExchangeSection.CODEC.encodeStart(
                        com.mojang.serialization.JsonOps.INSTANCE, store.exchange()).getOrThrow()).append(';');
        out.append(ledger.balance(SystemAccountId.ESCROW)).append(';').append(owed(SYSTEM_ISSUER)).append(';');
        for (BudgetKind kind : BudgetKind.values()) out.append(ledger.budgetUsed(kind)).append(';');
        out.append(supply).append(';').append(store.simulation().lastStep()).append(';').append(outside).append(';')
                .append(goods).append(';').append(ledger.now()).append(';').append(stepsRun).append(';')
                .append(stepsSkipped).append(';').append(digest).append(';').append(store.book().size()).append(';')
                .append(store.offers().size()).append(';').append(store.rules().size()).append(';')
                .append(store.rules().waiting()).append(';').append(ruleTicks).append(';').append(ruleTraded).append(';')
                .append(store.escrowObligations()).append(';').append(alerts).append(';')
                .append(store.autoTraders().locations()).append(';').append(trades.size()).append(';')
                .append(slotWrites()).append(';').append(guardChecks).append(';')
                .append(za.co.neroland.neroeconomy.house.HouseSection.CODEC.encodeStart(
                        com.mojang.serialization.JsonOps.INSTANCE, store.houses()).getOrThrow());
        traders.forEach((location, slots) -> out.append(';').append(location).append(slots.loaded ? '+' : '-')
                .append(java.util.Arrays.toString(slots.held)));
        return out.toString();
    }

    // --- the simulation ----------------------------------------------------------------------

    /**
     * A handler of the kind later stages add: it acts in the market on its own, from nothing but
     * the store, the step and the secret. Each step it pays one player a little out of the Reserve
     * under the Merchant House budget, and one step in three it delivers freshly made goods to a vault.
     */
    private final class MerchantStandIn implements SimStep {
        @Override public String name() { return "fuzz_merchant"; }

        @Override public void run(SimContext context, long step) {
            SimRandom pick = context.random("merchant");
            UUID paid = players.get(pick.nextInt(PLAYERS));
            // A payout is a transfer out of the Reserve; refused or not, the supply does not move.
            context.ledger().payFromReserve(BudgetKind.NPC, account(paid), pick.nextInt(40), TransactionType.MARKET_PAYOUT);
            if (pick.nextInt(3) != 0) return;
            UUID supplied = players.get(pick.nextInt(PLAYERS));
            Lot.Key kind = new Lot.Key(ITEMS[pick.nextInt(ITEMS.length)], pick.nextInt(PAYLOADS));
            int count = 1 + pick.nextInt(4);
            context.vault().deliver(account(supplied), List.of(new Lot(kind.item(), count, kind.payload())), CAP, BOUND,
                    LIMIT);
            createdInStore(kind, count);
        }
    }
}
