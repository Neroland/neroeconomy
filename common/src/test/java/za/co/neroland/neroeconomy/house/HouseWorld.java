package za.co.neroland.neroeconomy.house;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.BuyerPricing;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * A {@link TradeWorld} with Merchant Houses: commodities and Houses the test chooses, a trading
 * environment whose system delivery books into the store's House section (whichever store is
 * current, so a reload is followed), and a step that advances the clock by one step length.
 */
final class HouseWorld {
    static final long STEP = 600_000L;
    static final String WHEAT = "minecraft:wheat";
    static final String DIAMOND = "minecraft:diamond";
    static final String STONE = "minecraft:stone";

    final TradeWorld world = new TradeWorld();
    CommodityRegistry commodities;
    HouseRegistry houses;
    HouseSettings settings = HouseSettings.DEFAULTS;
    HouseEnv.GuardCheck guard = HouseEnv.GuardCheck.NONE;

    HouseWorld(List<CommodityDefinition> definitions, List<HouseDefinition> houseList) {
        Set<String> items = new HashSet<>();
        for (CommodityDefinition definition : definitions) if (!definition.tag()) items.add(definition.key());
        commodities = CommodityRegistry.of(definitions, CommodityCatalog.of(Set.of(), items,
                Map.of("test:logs", List.of("minecraft:birch_log", "minecraft:oak_log")), Set.of()));
        houses = HouseRegistry.of(houseList);
        SystemDelivery delivery = new SystemDelivery() {
            @Override public boolean handles(SystemAccountId account) { return account.isHouse(); }
            @Override public void deliver(SystemAccountId account, Lot lot) { section().deliver(account, lot); }
        };
        world.env = new TradeEnv(TradeWorld.settings(), TradeWorld.LIMIT, BuyerPricing.NONE, ItemCatalog.OPEN,
                (account, kind) -> { }, delivery);
    }

    /** Wheat (staple-like but farmable), sold and bought by the growers. */
    static CommodityDefinition wheat() {
        return new CommodityDefinition(WHEAT, CommodityDefinition.DIRECT_FILE, 10L, 512L, 512L, 64L, 64L, 0.8D, 0.05D,
                1_000, CommodityClass.FARMABLE, "growers", "farming", true, true);
    }

    /** Diamonds: only bought, by the general House. */
    static CommodityDefinition diamond() {
        return new CommodityDefinition(DIAMOND, CommodityDefinition.DIRECT_FILE, 400L, 0L, 32L, 0L, 8L, 0.8D, 0.01D,
                1_000, CommodityClass.RARE, "general", "mining", true, true);
    }

    static HouseWorld standard() {
        return new HouseWorld(List.of(wheat(), diamond()),
                List.of(house("growers", 4_000L), house("general", 10_000L)));
    }

    /** A House that restocks a whole day's supply every hour, so a test has goods to trade at once. */
    static HouseDefinition house(String id, long capital) {
        return new HouseDefinition(id, HouseDefinition.BUILT_IN_FILE, "house.neroeconomy." + id, List.of(), List.of(),
                capital, 24.0D);
    }

    HouseSection section() { return world.store.houses(); }

    HouseEnv env() { return new HouseEnv(commodities, houses, world.env, settings, guard); }

    long day() { return EconomySavedData.economyDay(world.now()); }

    /** One simulation step at the current time, then the clock moves on by a step. */
    void step() {
        section().step(env(), day(), STEP);
        world.wall += STEP;
    }

    void steps(int count) { for (int index = 0; index < count; index++) step(); }

    long balance(String house) { return world.ledger().balance(SystemAccountId.house(house)); }

    /** The House's resting sell order for an item, or null. */
    SellOrder ask(String item) {
        for (SellOrder order : world.store.book().asks(item))
            if (order.owner() instanceof SystemAccountId system && system.isHouse()) return order;
        return null;
    }

    /** The House's resting buy order for an item, or null. */
    BuyOrder bid(String item) {
        for (BuyOrder order : world.store.book().bids(item))
            if (order.owner() instanceof SystemAccountId system && system.isHouse()) return order;
        return null;
    }

    UUID player(int number, long credits) { return world.player(number, credits); }
}
