package za.co.neroland.neroeconomy.house;

import java.util.Set;

import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.guard.ArbitrageGuard;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * Everything the Merchant Houses' rules need from outside the economy store for one step or one
 * re-quote: the commodities (with the arbitrage guard's verdict), the Houses, the trading
 * environment (whose {@code systemDelivery} must keep the Houses' stock), the settings, and a way
 * to run the arbitrage guard again at other prices. The game builds one from its data packs and
 * configuration ({@code Houses.env}); a test builds its own.
 */
public record HouseEnv(CommodityRegistry commodities, HouseRegistry houses, TradeEnv trade, HouseSettings settings,
        GuardCheck guard) {
    public HouseEnv {
        if (commodities == null || houses == null || trade == null) throw new IllegalArgumentException("house env");
        if (settings == null) settings = HouseSettings.DEFAULTS;
        if (guard == null) guard = GuardCheck.NONE;
    }

    /** Runs the arbitrage guard at the given prices. */
    @FunctionalInterface
    public interface GuardCheck {
        /** The keys of the commodities whose NPC buy side must be off at these prices. */
        Set<String> buyOff(ArbitrageGuard.Quotes quotes);

        /** No recipes to check: nothing is switched off. */
        GuardCheck NONE = quotes -> Set.of();
    }
}
