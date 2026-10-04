package za.co.neroland.neroeconomy.exchange;

import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.Fees;

/**
 * Every configured number the exchange's <em>trading</em> rules use — spreads, the fee, the holding
 * period, the limits, the bond rates and the practice balance — read once per operation and handed
 * to the pure rules. The game builds one with {@link #fromConfig()}; a test uses {@link #DEFAULTS}
 * or changes what it needs with the {@code with…} methods. (The price model's own numbers are
 * {@link ExchangeSettings}.)
 *
 * @param enabled             {@code exchangeEnabled}: false refuses every buy and sell
 * @param feeBps              {@code exchangeFeePercent}, in hundredths of a percent
 * @param spreadSteadyBps     {@code exchangeSpreadSteadyPercent}: ask less bid of a steady share
 * @param spreadRiskyBps      {@code exchangeSpreadRiskyPercent}
 * @param spreadIndexBps      {@code exchangeSpreadIndexPercent}
 * @param holdMillis          {@code shareHoldMinutes}, in milliseconds of economy time
 * @param maxPositionPerStock most credits, at cost, one account may have in one stock
 * @param maxPortfolio        most credits, at cost, one account may have in shares, index and bonds together
 * @param maxBonds            most Savings Bonds one account may hold
 * @param bondRate1DayBps     interest of a 1-day bond for its whole term
 * @param bondRate3DayBps     interest of a 3-day bond for its whole term
 * @param bondRate7DayBps     interest of a 7-day bond for its whole term
 * @param practiceStart       play credits a practice portfolio starts with, and is reset to
 * @param actionsPerMinute    exchange actions one account may make per minute of economy time
 */
public record TradingTerms(boolean enabled, int feeBps, int spreadSteadyBps, int spreadRiskyBps, int spreadIndexBps,
        long holdMillis, long maxPositionPerStock, long maxPortfolio, int maxBonds, int bondRate1DayBps,
        int bondRate3DayBps, int bondRate7DayBps, long practiceStart, int actionsPerMinute) {

    // --- the published defaults --------------------------------------------------------------

    /** Buying and selling a steady share at once costs 1 % of its price, besides the fees. */
    public static final double DEFAULT_SPREAD_STEADY_PERCENT = 1.0D;
    /** A risky share: 2 %. */
    public static final double DEFAULT_SPREAD_RISKY_PERCENT = 2.0D;
    /** The index fund: 1 %. */
    public static final double DEFAULT_SPREAD_INDEX_PERCENT = 1.0D;
    /** A purchase cannot be sold for an hour. */
    public static final int DEFAULT_HOLD_MINUTES = 60;
    public static final long DEFAULT_MAX_POSITION = 5_000L;
    public static final long DEFAULT_MAX_PORTFOLIO = 20_000L;
    public static final int DEFAULT_MAX_BONDS = 8;
    public static final long DEFAULT_PRACTICE_START = 10_000L;
    public static final int DEFAULT_ACTIONS_PER_MINUTE = 20;
    /** {@code exchangeFeePercent}'s default. */
    public static final double DEFAULT_FEE_PERCENT = 1.0D;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    /** The defaults above, as terms. */
    public static final TradingTerms DEFAULTS = new TradingTerms(true, Fees.basisPoints(DEFAULT_FEE_PERCENT),
            ExchangeMath.bps(DEFAULT_SPREAD_STEADY_PERCENT), ExchangeMath.bps(DEFAULT_SPREAD_RISKY_PERCENT),
            ExchangeMath.bps(DEFAULT_SPREAD_INDEX_PERCENT), DEFAULT_HOLD_MINUTES * MILLIS_PER_MINUTE, DEFAULT_MAX_POSITION,
            DEFAULT_MAX_PORTFOLIO, DEFAULT_MAX_BONDS, ExchangeSettings.DEFAULTS.bondRate1DayBps(),
            ExchangeSettings.DEFAULTS.bondRate3DayBps(), ExchangeSettings.DEFAULTS.bondRate7DayBps(),
            DEFAULT_PRACTICE_START, DEFAULT_ACTIONS_PER_MINUTE);

    public TradingTerms {
        if (feeBps < 0 || spreadSteadyBps < 0 || spreadRiskyBps < 0 || spreadIndexBps < 0 || holdMillis < 0L
                || maxPositionPerStock < 0L || maxPortfolio < 0L || maxBonds < 0 || bondRate1DayBps < 0
                || bondRate3DayBps < 0 || bondRate7DayBps < 0 || practiceStart < 0L || actionsPerMinute < 1)
            throw new IllegalArgumentException("trading terms");
    }

    /** The terms in effect now; read on every call, so a configuration reload applies at once. */
    public static TradingTerms fromConfig() {
        ExchangeSettings model = ExchangeSettings.fromConfig();
        return new TradingTerms(model.enabled(), Fees.basisPoints(EconomyConfig.EXCHANGE_FEE_PERCENT.get()),
                ExchangeMath.bps(EconomyConfig.EXCHANGE_SPREAD_STEADY_PERCENT.get()),
                ExchangeMath.bps(EconomyConfig.EXCHANGE_SPREAD_RISKY_PERCENT.get()),
                ExchangeMath.bps(EconomyConfig.EXCHANGE_SPREAD_INDEX_PERCENT.get()),
                EconomyConfig.SHARE_HOLD_MINUTES.get() * MILLIS_PER_MINUTE, EconomyConfig.MAX_POSITION_PER_STOCK.get(),
                EconomyConfig.MAX_PORTFOLIO.get(), EconomyConfig.MAX_BONDS_PER_PLAYER.get(), model.bondRate1DayBps(),
                model.bondRate3DayBps(), model.bondRate7DayBps(), EconomyConfig.PRACTICE_STARTING_CREDITS.get(),
                EconomyConfig.EXCHANGE_ACTIONS_PER_MINUTE.get());
    }

    /** The spread of a share of this type, in hundredths of a percent. */
    public int spreadBps(StockType type) { return type == StockType.RISKY ? spreadRiskyBps : spreadSteadyBps; }

    /** The interest of a bond of this term for its whole term, in hundredths of a percent. */
    public int bondRateBps(BondTerm term) {
        return switch (term) {
            case ONE_DAY -> bondRate1DayBps;
            case THREE_DAYS -> bondRate3DayBps;
            case SEVEN_DAYS -> bondRate7DayBps;
        };
    }

    // --- what a test changes -----------------------------------------------------------------

    public TradingTerms withEnabled(boolean next) {
        return new TradingTerms(next, feeBps, spreadSteadyBps, spreadRiskyBps, spreadIndexBps, holdMillis,
                maxPositionPerStock, maxPortfolio, maxBonds, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps,
                practiceStart, actionsPerMinute);
    }

    /** The same terms with another fee and other spreads, each in hundredths of a percent. */
    public TradingTerms withCosts(int fee, int steady, int risky, int index) {
        return new TradingTerms(enabled, fee, steady, risky, index, holdMillis, maxPositionPerStock, maxPortfolio,
                maxBonds, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps, practiceStart, actionsPerMinute);
    }

    public TradingTerms withHold(long millis) {
        return new TradingTerms(enabled, feeBps, spreadSteadyBps, spreadRiskyBps, spreadIndexBps, millis,
                maxPositionPerStock, maxPortfolio, maxBonds, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps,
                practiceStart, actionsPerMinute);
    }

    /** The same terms with other limits. */
    public TradingTerms withLimits(long position, long portfolio, int bonds) {
        return new TradingTerms(enabled, feeBps, spreadSteadyBps, spreadRiskyBps, spreadIndexBps, holdMillis, position,
                portfolio, bonds, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps, practiceStart, actionsPerMinute);
    }

    public TradingTerms withPractice(long start) {
        return new TradingTerms(enabled, feeBps, spreadSteadyBps, spreadRiskyBps, spreadIndexBps, holdMillis,
                maxPositionPerStock, maxPortfolio, maxBonds, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps, start,
                actionsPerMinute);
    }

    public TradingTerms withActionsPerMinute(int actions) {
        return new TradingTerms(enabled, feeBps, spreadSteadyBps, spreadRiskyBps, spreadIndexBps, holdMillis,
                maxPositionPerStock, maxPortfolio, maxBonds, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps,
                practiceStart, actions);
    }
}
