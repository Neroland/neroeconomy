package za.co.neroland.neroeconomy.exchange;

import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * Every global number the exchange's price model uses, read once per step and handed to the pure
 * rules. The game builds one with {@link #fromConfig()}; a test uses {@link #DEFAULTS} or changes
 * what it needs with the {@code with…} methods.
 *
 * <p>The {@code DEFAULT_…} constants below are the one place the default numbers live: the
 * configuration keys take their defaults from them, and {@link PriceModel} documents the formulas
 * they go into. Per-company numbers (start price, volatility, drift, dividend) are in the data
 * files, not here.
 *
 * @param enabled             {@code exchangeEnabled}: false freezes every price where it is
 * @param stepLimitSteadyBps  most a steady share may move in one step, in hundredths of a percent
 * @param stepLimitRiskyBps   most a risky share may move in one step
 * @param dayLimitSteadyBps   most a steady share may be away from its day-open price
 * @param dayLimitRiskyBps    most a risky share may be away from its day-open price
 * @param haltSteps           steps a share is halted for after reaching its daily limit; 0 never halts
 * @param reversion           fraction of the gap between price and anchor that closes each step
 * @param volumeNudgeCap      most the traded volume of linked commodities may move a price in one
 *                            step, as a fraction (0.005 is half a percent)
 * @param volumeScale         credits of counted volume above (or below) the usual level that give
 *                            the whole nudge
 * @param volumePairCap       credits of fills between the same two player accounts counted per step
 * @param volumeAccountCap    credits of fills of one player account counted per step
 * @param shockGain           fraction of an active market shock applied to the price each step
 * @param minShock            smallest shock multiplier ({@code minPriceMultiplier})
 * @param maxShock            largest shock multiplier ({@code maxPriceMultiplier})
 * @param floorBps            the floor, as hundredths of a percent of the start price
 * @param bondRate1DayBps     interest of a 1-day Savings Bond for its whole term, in hundredths of a percent
 * @param bondRate3DayBps     interest of a 3-day Savings Bond for its whole term
 * @param bondRate7DayBps     interest of a 7-day Savings Bond for its whole term
 */
public record ExchangeSettings(boolean enabled, int stepLimitSteadyBps, int stepLimitRiskyBps, int dayLimitSteadyBps,
        int dayLimitRiskyBps, int haltSteps, double reversion, double volumeNudgeCap, long volumeScale,
        long volumePairCap, long volumeAccountCap, double shockGain, double minShock, double maxShock, int floorBps,
        int bondRate1DayBps, int bondRate3DayBps, int bondRate7DayBps) {

    // --- the published defaults --------------------------------------------------------------

    /** A steady share moves at most 2 % in one step. */
    public static final double DEFAULT_STEP_LIMIT_STEADY_PERCENT = 2.0D;
    /** A risky share moves at most 6 % in one step. */
    public static final double DEFAULT_STEP_LIMIT_RISKY_PERCENT = 6.0D;
    /** A steady share stays within 10 % of its day-open price. */
    public static final double DEFAULT_DAY_LIMIT_STEADY_PERCENT = 10.0D;
    /** A risky share stays within 40 % of its day-open price. */
    public static final double DEFAULT_DAY_LIMIT_RISKY_PERCENT = 40.0D;
    /** A share that reaches its daily limit is halted for 6 steps: one hour at the default step length. */
    public static final int DEFAULT_HALT_STEPS = 6;
    /** Each step 0.05 % of the gap between price and anchor closes: half of a gap is gone in about ten days. */
    public static final double DEFAULT_REVERSION_PERCENT = 0.05D;
    /** Trade in linked commodities moves a price by at most half a percent per step. */
    public static final double DEFAULT_VOLUME_NUDGE_CAP_PERCENT = 0.5D;
    /** 2000 credits of counted volume above the usual level give the whole nudge. */
    public static final long DEFAULT_VOLUME_SCALE = 2_000L;
    /** Fills between the same two player accounts count up to 250 credits per step. */
    public static final long DEFAULT_VOLUME_PAIR_CAP = 250L;
    /** Fills of one player account count up to 1000 credits per step. */
    public static final long DEFAULT_VOLUME_ACCOUNT_CAP = 1_000L;
    /** Each step 1 % of an active shock reaches the price. */
    public static final double DEFAULT_SHOCK_STEP_PERCENT = 1.0D;
    /** A price never falls below 5 % of the company's start price (nor below 0.01 credit). */
    public static final double DEFAULT_FLOOR_PERCENT = 5.0D;
    /** A 1-day Savings Bond pays 0.8 % for its term. */
    public static final double DEFAULT_BOND_RATE_1_DAY_PERCENT = 0.8D;
    /** A 3-day Savings Bond pays 3 % for its term. */
    public static final double DEFAULT_BOND_RATE_3_DAY_PERCENT = 3.0D;
    /** A 7-day Savings Bond pays 8 % for its term. */
    public static final double DEFAULT_BOND_RATE_7_DAY_PERCENT = 8.0D;
    /** The shock clamp when no configuration is read: {@code minPriceMultiplier} and {@code maxPriceMultiplier}. */
    public static final double DEFAULT_MIN_SHOCK = 0.25D;
    public static final double DEFAULT_MAX_SHOCK = 4.0D;

    /** The defaults above, as settings. */
    public static final ExchangeSettings DEFAULTS = new ExchangeSettings(true,
            bps(DEFAULT_STEP_LIMIT_STEADY_PERCENT), bps(DEFAULT_STEP_LIMIT_RISKY_PERCENT),
            bps(DEFAULT_DAY_LIMIT_STEADY_PERCENT), bps(DEFAULT_DAY_LIMIT_RISKY_PERCENT), DEFAULT_HALT_STEPS,
            DEFAULT_REVERSION_PERCENT / 100.0D, DEFAULT_VOLUME_NUDGE_CAP_PERCENT / 100.0D, DEFAULT_VOLUME_SCALE,
            DEFAULT_VOLUME_PAIR_CAP, DEFAULT_VOLUME_ACCOUNT_CAP, DEFAULT_SHOCK_STEP_PERCENT / 100.0D,
            DEFAULT_MIN_SHOCK, DEFAULT_MAX_SHOCK, bps(DEFAULT_FLOOR_PERCENT), bps(DEFAULT_BOND_RATE_1_DAY_PERCENT),
            bps(DEFAULT_BOND_RATE_3_DAY_PERCENT), bps(DEFAULT_BOND_RATE_7_DAY_PERCENT));

    public ExchangeSettings {
        if (stepLimitSteadyBps < 0 || stepLimitRiskyBps < 0 || dayLimitSteadyBps < 0 || dayLimitRiskyBps < 0
                || haltSteps < 0 || !fraction(reversion) || !fraction(volumeNudgeCap) || volumeScale < 0L
                || volumePairCap < 0L || volumeAccountCap < 0L || !fraction(shockGain) || !(minShock > 0.0D)
                || !(maxShock >= minShock) || !Double.isFinite(maxShock) || floorBps < 0 || bondRate1DayBps < 0
                || bondRate3DayBps < 0 || bondRate7DayBps < 0) throw new IllegalArgumentException("exchange settings");
    }

    private static boolean fraction(double value) { return value >= 0.0D && value <= 1.0D; }

    /** A percentage as hundredths of a percent, rounded to the nearest. */
    public static int bps(double percent) { return (int) Math.round(percent * 100.0D); }

    /** The settings in effect now; read on every call, so a configuration reload applies at the next step. */
    public static ExchangeSettings fromConfig() {
        double minShock = EconomyConfig.MIN_PRICE_MULTIPLIER.get();
        double maxShock = Math.max(minShock, EconomyConfig.MAX_PRICE_MULTIPLIER.get());
        return new ExchangeSettings(EconomyConfig.EXCHANGE_ENABLED.get(),
                bps(EconomyConfig.EXCHANGE_STEP_LIMIT_STEADY_PERCENT.get()),
                bps(EconomyConfig.EXCHANGE_STEP_LIMIT_RISKY_PERCENT.get()),
                bps(EconomyConfig.EXCHANGE_DAY_LIMIT_STEADY_PERCENT.get()),
                bps(EconomyConfig.EXCHANGE_DAY_LIMIT_RISKY_PERCENT.get()), EconomyConfig.EXCHANGE_HALT_STEPS.get(),
                EconomyConfig.EXCHANGE_REVERSION_PERCENT.get() / 100.0D,
                EconomyConfig.EXCHANGE_VOLUME_NUDGE_CAP_PERCENT.get() / 100.0D,
                EconomyConfig.EXCHANGE_VOLUME_SCALE.get(), EconomyConfig.EXCHANGE_VOLUME_PAIR_CAP.get(),
                EconomyConfig.EXCHANGE_VOLUME_ACCOUNT_CAP.get(),
                EconomyConfig.EXCHANGE_SHOCK_STEP_PERCENT.get() / 100.0D, minShock, maxShock,
                bps(EconomyConfig.EXCHANGE_FLOOR_PERCENT.get()), bps(EconomyConfig.BOND_RATE_1_DAY_PERCENT.get()),
                bps(EconomyConfig.BOND_RATE_3_DAY_PERCENT.get()), bps(EconomyConfig.BOND_RATE_7_DAY_PERCENT.get()));
    }

    /** The most a share of this type may move in one step, in hundredths of a percent. */
    public int stepLimitBps(StockType type) { return type == StockType.STEADY ? stepLimitSteadyBps : stepLimitRiskyBps; }

    /** The most a share of this type may be away from its day-open price, in hundredths of a percent. */
    public int dayLimitBps(StockType type) { return type == StockType.STEADY ? dayLimitSteadyBps : dayLimitRiskyBps; }

    /** The interest of a bond of this term for its whole term, in hundredths of a percent. */
    public int bondRateBps(BondTerm term) {
        return switch (term) {
            case ONE_DAY -> bondRate1DayBps;
            case THREE_DAYS -> bondRate3DayBps;
            case SEVEN_DAYS -> bondRate7DayBps;
        };
    }

    // --- what a test changes -----------------------------------------------------------------

    public ExchangeSettings withEnabled(boolean next) {
        return new ExchangeSettings(next, stepLimitSteadyBps, stepLimitRiskyBps, dayLimitSteadyBps, dayLimitRiskyBps,
                haltSteps, reversion, volumeNudgeCap, volumeScale, volumePairCap, volumeAccountCap, shockGain, minShock,
                maxShock, floorBps, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps);
    }

    /** The same settings with other move limits, each in hundredths of a percent. */
    public ExchangeSettings withLimits(int stepSteady, int stepRisky, int daySteady, int dayRisky, int halt) {
        return new ExchangeSettings(enabled, stepSteady, stepRisky, daySteady, dayRisky, halt, reversion,
                volumeNudgeCap, volumeScale, volumePairCap, volumeAccountCap, shockGain, minShock, maxShock, floorBps,
                bondRate1DayBps, bondRate3DayBps, bondRate7DayBps);
    }

    /** The same settings with another shock clamp and gain. */
    public ExchangeSettings withShock(double gain, double min, double max) {
        return new ExchangeSettings(enabled, stepLimitSteadyBps, stepLimitRiskyBps, dayLimitSteadyBps, dayLimitRiskyBps,
                haltSteps, reversion, volumeNudgeCap, volumeScale, volumePairCap, volumeAccountCap, gain, min, max,
                floorBps, bondRate1DayBps, bondRate3DayBps, bondRate7DayBps);
    }
}
