package za.co.neroland.neroeconomy.trade;

import za.co.neroland.neroeconomy.goods.StackLimit;
import za.co.neroland.neroeconomy.rules.RuleBinder;

/**
 * Everything the pure trading rules need from outside the economy store, for one operation: the
 * configured numbers, the game's stack limits, the buyer-price rule, the item catalogue, where
 * alerts go, who keeps the stock of system accounts, and who knows the inventories auto-trade
 * rules are bound to. The order book, offers and auto-trade rules take one as the first argument
 * of every operation and keep nothing from it, so the very same code runs in the game (see
 * {@code Trading.env}) and in plain JUnit.
 *
 * @param systemDelivery null until something keeps system-account stock; the book then creates no
 *                       order for a system account
 * @param ruleBinder     null until something other than the vault can back a rule; a rule bound
 *                       to anything else is then unreachable
 */
public record TradeEnv(TradeSettings settings, StackLimit limit, BuyerPricing pricing, ItemCatalog items,
        AlertSink alerts, SystemDelivery systemDelivery, RuleBinder ruleBinder) {
    public TradeEnv {
        if (settings == null || limit == null) throw new IllegalArgumentException("trade environment");
        if (pricing == null) pricing = BuyerPricing.NONE;
        if (items == null) items = ItemCatalog.OPEN;
        if (alerts == null) alerts = AlertSink.NONE;
    }

    /** An environment in which rules can only be bound to the vault. */
    public TradeEnv(TradeSettings settings, StackLimit limit, BuyerPricing pricing, ItemCatalog items, AlertSink alerts,
            SystemDelivery systemDelivery) {
        this(settings, limit, pricing, items, alerts, systemDelivery, null);
    }

    /** The same environment with other settings. */
    public TradeEnv with(TradeSettings next) {
        return new TradeEnv(next, limit, pricing, items, alerts, systemDelivery, ruleBinder);
    }

    /** The same environment with another buyer-price rule. */
    public TradeEnv with(BuyerPricing next) {
        return new TradeEnv(settings, limit, next, items, alerts, systemDelivery, ruleBinder);
    }

    /** The same environment with another item catalogue. */
    public TradeEnv withItems(ItemCatalog next) {
        return new TradeEnv(settings, limit, pricing, next, alerts, systemDelivery, ruleBinder);
    }

    /** The same environment with something that knows more rule inventories than the vault. */
    public TradeEnv withBinder(RuleBinder next) {
        return new TradeEnv(settings, limit, pricing, items, alerts, systemDelivery, next);
    }
}
