package za.co.neroland.neroeconomy.trade;

import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * Every configured number the trading rules use — the order book, offers and auto-trade rules —
 * read once per operation and handed to the pure rules, which hold no configuration of their own.
 * The game builds one with {@link #fromConfig()}; a test builds one with the numbers it wants.
 *
 * @param marketFeePercent       fee on the seller's proceeds of every fill
 * @param listingFee             flat fee for every posted order
 * @param maxSellOrders          open sell orders one account may have ({@code maxListingsPerPlayer})
 * @param maxBuyOrders           open buy orders one account may have ({@code maxBuyOffersPerPlayer})
 * @param orderDurationMillis    how long a posted order stays open
 * @param vaultCap               the vault cap a system delivery is reported against
 * @param vaultTradeCap          the cap a voluntary trade is checked against before it may deliver
 * @param vaultHardBound         the stack count past which an operator is warned
 * @param tradeTaxPercent        tax on each credit side of an accepted offer
 * @param offerDurationMillis    how long an unanswered offer stays open
 * @param maxOffersOut           open offers one account may have sent
 * @param maxOffersIn            open offers one account may have waiting for an answer
 * @param maxRules               auto-trade rules one account may have
 * @param ruleDailyValueCap      credits of filled value per account per economy-day across its rules
 * @param ruleEvaluationsPerTick rule evaluations one tick may run; the rest are carried over
 */
public record TradeSettings(double marketFeePercent, long listingFee, int maxSellOrders, int maxBuyOrders,
        long orderDurationMillis, int vaultCap, int vaultTradeCap, int vaultHardBound, double tradeTaxPercent,
        long offerDurationMillis, int maxOffersOut, int maxOffersIn, int maxRules, long ruleDailyValueCap,
        int ruleEvaluationsPerTick) {
    /** Most open offers one account may have waiting for its answer, so nobody can be flooded. */
    public static final int MAX_OFFERS_IN = 64;
    /** Most rule evaluations in one tick; the rest wait their turn. */
    public static final int RULE_EVALUATIONS_PER_TICK = 64;
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /** The settings in effect now; read on every call, so a configuration reload applies at once. */
    public static TradeSettings fromConfig() {
        return new TradeSettings(EconomyConfig.MARKET_FEE_PERCENT.get(), EconomyConfig.LISTING_FEE.get(),
                EconomyConfig.MAX_LISTINGS_PER_PLAYER.get(), EconomyConfig.MAX_BUY_OFFERS_PER_PLAYER.get(),
                EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * MILLIS_PER_HOUR, VaultStacks.cap(),
                VaultStacks.tradeCap(), VaultStacks.hardBound(), EconomyConfig.TRADE_TAX_PERCENT.get(),
                EconomyConfig.OFFER_DURATION_HOURS.get() * MILLIS_PER_HOUR, EconomyConfig.MAX_OFFERS_PER_PLAYER.get(),
                MAX_OFFERS_IN, EconomyConfig.MAX_RULES_PER_PLAYER.get(), EconomyConfig.RULE_DAILY_VALUE_CAP.get(),
                RULE_EVALUATIONS_PER_TICK);
    }

    /** The same settings with other fees: what a test of the fee rules changes. */
    public TradeSettings withFees(double marketFee, long listing, double tradeTax) {
        return new TradeSettings(marketFee, listing, maxSellOrders, maxBuyOrders, orderDurationMillis, vaultCap,
                vaultTradeCap, vaultHardBound, tradeTax, offerDurationMillis, maxOffersOut, maxOffersIn, maxRules,
                ruleDailyValueCap, ruleEvaluationsPerTick);
    }

    /** The same settings with other per-account caps on open orders. */
    public TradeSettings withOrderCaps(int sells, int buys) {
        return new TradeSettings(marketFeePercent, listingFee, sells, buys, orderDurationMillis, vaultCap,
                vaultTradeCap, vaultHardBound, tradeTaxPercent, offerDurationMillis, maxOffersOut, maxOffersIn,
                maxRules, ruleDailyValueCap, ruleEvaluationsPerTick);
    }

    /** The same settings with other vault caps. */
    public TradeSettings withVault(int cap, int tradeCap, int hardBound) {
        return new TradeSettings(marketFeePercent, listingFee, maxSellOrders, maxBuyOrders, orderDurationMillis, cap,
                tradeCap, hardBound, tradeTaxPercent, offerDurationMillis, maxOffersOut, maxOffersIn, maxRules,
                ruleDailyValueCap, ruleEvaluationsPerTick);
    }

    /** The same settings with other offer caps. */
    public TradeSettings withOfferCaps(int out, int in) {
        return new TradeSettings(marketFeePercent, listingFee, maxSellOrders, maxBuyOrders, orderDurationMillis,
                vaultCap, vaultTradeCap, vaultHardBound, tradeTaxPercent, offerDurationMillis, out, in, maxRules,
                ruleDailyValueCap, ruleEvaluationsPerTick);
    }

    /** The same settings with other rule limits. */
    public TradeSettings withRules(int rules, long dailyValueCap, int evaluationsPerTick) {
        return new TradeSettings(marketFeePercent, listingFee, maxSellOrders, maxBuyOrders, orderDurationMillis,
                vaultCap, vaultTradeCap, vaultHardBound, tradeTaxPercent, offerDurationMillis, maxOffersOut,
                maxOffersIn, rules, dailyValueCap, evaluationsPerTick);
    }
}
