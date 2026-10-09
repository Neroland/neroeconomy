package za.co.neroland.neroeconomy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.house.HouseRegistry;
import za.co.neroland.neroeconomy.house.HouseSection;
import za.co.neroland.neroeconomy.market.MarketSavedData;

/**
 * Read-only view of the commodities for other mods and for screens: which items the Merchant
 * Houses trade, what they are worth at their reference price, and the daily limits.
 *
 * <p>Server-side only. Everything returned is a copy. Prices are whole credits per unit. The
 * prices of {@link CommodityView} are the <b>reference</b> prices from the data packs, with the
 * spread applied; what a House is quoting at this moment is {@link #houseQuote}. Only
 * {@link #remainingToday} is about a player, and only the one asked about.
 *
 * <p>Only an <b>unmodified</b> stack is ever a commodity: a stack with a custom name,
 * enchantments, damage or contents is not, whatever its item.
 */
public final class CommodityApi {
    private CommodityApi() { }

    /**
     * One commodity.
     *
     * @param key                 what it covers: an item id, or an item tag id with a leading {@code #}
     * @param items               every item id it covers in this world, in id order
     * @param referencePrice      whole credits per unit
     * @param npcAsk              what the Merchant Houses sell one unit for at the reference price
     * @param npcBid              what they pay for one unit at the reference price; 0 = they do not buy it
     * @param npcSells            whether the Houses sell it at all; false too while the arbitrage
     *                            guard has that side switched off
     * @param npcBuys             whether the Houses buy it at all; false too while the arbitrage
     *                            guard has that side switched off
     * @param npcDailySupply      units the Houses sell per economy-day in total
     * @param npcDailyDemand      units the Houses buy per economy-day in total
     * @param perAccountDailyBuy  units one account may buy from the Houses per economy-day
     * @param perAccountDailySell units one account may sell to the Houses per economy-day
     * @param commodityClass      staple, farmable, crafted or rare
     * @param house               the id of the Merchant House that trades it
     * @param sector              the exchange sector its trade counts toward; empty for none
     */
    public record CommodityView(String key, List<String> items, long referencePrice, long npcAsk, long npcBid,
            boolean npcSells, boolean npcBuys, long npcDailySupply, long npcDailyDemand, long perAccountDailyBuy,
            long perAccountDailySell, CommodityClass commodityClass, String house, String sector) {
        public CommodityView {
            items = List.copyOf(items);
        }

        /** Whether the commodity is a whole item tag rather than one item. */
        public boolean tag() { return key.charAt(0) == '#'; }
    }

    /** Whether this stack is a commodity: it is unmodified and its item is one. */
    public static boolean isCommodity(MinecraftServer server, ItemStack stack) {
        return commodity(server, stack).isPresent();
    }

    /** The commodity this stack belongs to; empty for a modified stack or an item that is none. */
    public static Optional<CommodityView> commodity(MinecraftServer server, ItemStack stack) {
        if (server == null || stack == null || stack.isEmpty() || !MarketSavedData.pristine(stack)) return Optional.empty();
        return commodity(server, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /**
     * The commodity that covers unmodified stacks of this item id ({@code minecraft:diamond});
     * empty when the item is none.
     */
    public static Optional<CommodityView> commodity(MinecraftServer server, String itemId) {
        if (server == null || itemId == null) return Optional.empty();
        CommodityRegistry registry = CommodityData.forServer(server);
        CommodityDefinition definition = registry.forItem(itemId);
        return definition == null ? Optional.empty() : Optional.of(view(registry, definition));
    }

    /** Every commodity of this world, in key order. */
    public static List<CommodityView> commodities(MinecraftServer server) {
        if (server == null) return List.of();
        CommodityRegistry registry = CommodityData.forServer(server);
        List<CommodityView> out = new ArrayList<>(registry.commodities().size());
        for (CommodityDefinition definition : registry.commodities()) out.add(view(registry, definition));
        return List.copyOf(out);
    }

    // --- the Merchant Houses, live -----------------------------------------------------------

    /**
     * One Merchant House, as the data packs define it.
     *
     * @param id             its id; its ledger account is {@code system:house/<id>}
     * @param nameKey        the lang key of its display name
     * @param workingCapital the credits it keeps to buy with
     */
    public record HouseView(String id, String nameKey, long workingCapital) { }

    /**
     * A commodity as its Merchant House quotes it <b>now</b>: the drifted reference price, and the
     * ask and bid with today's price pressure on them. Prices are whole credits per unit, except
     * {@code referenceMillis}.
     *
     * @param key             the commodity's key
     * @param house           the id of the House that trades it
     * @param referencePrice  the current reference price, rounded to whole credits
     * @param referenceMillis the current reference price in thousandths of a credit
     * @param ask             what the House sells one unit for now
     * @param bid             what it pays for one unit now; 0 = it does not buy now
     * @param sells           whether it sells the commodity now
     * @param buys            whether it buys the commodity now (the arbitrage guard's daily re-check included)
     * @param stock           units it holds, in stock and on its sell order
     * @param askQuantity     units on its resting sell order now
     * @param bidQuantity     units its resting buy orders still want now
     */
    public record HouseQuote(String key, String house, long referencePrice, long referenceMillis, long ask, long bid,
            boolean sells, boolean buys, long stock, long askQuantity, long bidQuantity) { }

    /**
     * What one account may still trade of a commodity with the Merchant Houses today: the smaller of
     * its own daily cap and what is left of the commodity's. Only ever about the account asked for.
     *
     * @param buy  units it may still buy from the Houses today
     * @param sell units it may still sell to them today
     */
    public record DailyRemaining(String key, long buy, long sell) { }

    /** Every Merchant House of this world, in id order. There is always a {@code general} one. */
    public static List<HouseView> houses(MinecraftServer server) {
        if (server == null) return List.of();
        HouseRegistry registry = HouseData.forServer(server);
        List<HouseView> out = new ArrayList<>();
        for (HouseDefinition house : registry.houses())
            out.add(new HouseView(house.id(), house.nameKey(), house.workingCapital()));
        return List.copyOf(out);
    }

    /**
     * How the Merchant House quotes the commodity that covers unmodified stacks of this item id now;
     * empty when the item is no commodity or its House has not quoted it yet (the first simulation step).
     */
    public static Optional<HouseQuote> houseQuote(MinecraftServer server, String itemId) {
        if (server == null || itemId == null) return Optional.empty();
        CommodityDefinition definition = CommodityData.forServer(server).forItem(itemId);
        if (definition == null) return Optional.empty();
        return EconomyStore.get(server).houses().quote(definition.key()).map(CommodityApi::houseQuote);
    }

    private static HouseQuote houseQuote(HouseSection.Quote quote) {
        return new HouseQuote(quote.key(), quote.house(), Math.round(quote.referenceMillis() / 1_000.0D),
                quote.referenceMillis(), quote.ask(), quote.bid(), quote.sells(), quote.buys(), quote.stock(),
                quote.askQuantity(), quote.bidQuantity());
    }

    /**
     * What {@code player} may still trade with the Merchant Houses today of the commodity that covers
     * this item id; empty when the item is no commodity. Nothing about any other account.
     */
    public static Optional<DailyRemaining> remainingToday(MinecraftServer server, UUID player, String itemId) {
        if (server == null || player == null || itemId == null) return Optional.empty();
        CommodityDefinition definition = CommodityData.forServer(server).forItem(itemId);
        if (definition == null) return Optional.empty();
        EconomyStore store = EconomyStore.get(server);
        long[] left = store.houses().remainingToday(player, definition.key(), store.ledger().now());
        return Optional.of(new DailyRemaining(definition.key(), left[0], left[1]));
    }

    private static CommodityView view(CommodityRegistry registry, CommodityDefinition definition) {
        return new CommodityView(definition.key(), registry.itemsOf(definition.key()), definition.referencePrice(),
                definition.ask(), definition.bid(), registry.npcSells(definition), registry.npcBuys(definition),
                definition.npcDailySupply(), definition.npcDailyDemand(), definition.perAccountDailyBuy(),
                definition.perAccountDailySell(), definition.commodityClass(), definition.house(), definition.sector());
    }
}
