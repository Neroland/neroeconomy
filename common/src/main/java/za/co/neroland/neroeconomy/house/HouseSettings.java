package za.co.neroland.neroeconomy.house;

import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * The configured numbers of the Merchant Houses and of reference-price drift, read once per step
 * and handed to the pure rules. The game builds one with {@link #fromConfig()}; a test builds one
 * with the numbers it wants.
 *
 * @param driftStepBps    the most a reference may move in one step, in hundredths of a percent of
 *                        its data reference
 * @param bandMinBps      the lowest a reference may drift to, in hundredths of a percent of its data reference
 * @param bandMaxBps      the highest
 * @param minTradeValue   a player-to-player fill worth less than this many credits does not count
 *                        toward reference drift
 * @param accountDailyCap the most credits of one account's player-to-player fills that count toward
 *                        reference drift per economy-day
 * @param minPairs        how many distinct counterparty pairs a step needs before a reference moves;
 *                        no pair counts for more than the pair ranked this many places from the top
 * @param driftDayBps     the most a reference may move in one economy-day from where the day began,
 *                        in hundredths of a percent of its data reference
 */
public record HouseSettings(int driftStepBps, int bandMinBps, int bandMaxBps, long minTradeValue,
        long accountDailyCap, int minPairs, int driftDayBps) {
    public static final int DEFAULT_MIN_PAIRS = 3;
    public static final int DEFAULT_DRIFT_DAY_BPS = 1_000;
    public static final HouseSettings DEFAULTS = new HouseSettings(100, 5_000, 20_000, 16L, 2_000L);

    public HouseSettings {
        if (driftStepBps < 0 || bandMinBps < 0 || bandMinBps > 10_000 || bandMaxBps < 10_000 || minTradeValue < 0L
                || accountDailyCap < 0L || minPairs < 1 || driftDayBps < 0)
            throw new IllegalArgumentException("house settings");
    }

    /** The settings with the default pair rule and daily reach. */
    public HouseSettings(int driftStepBps, int bandMinBps, int bandMaxBps, long minTradeValue, long accountDailyCap) {
        this(driftStepBps, bandMinBps, bandMaxBps, minTradeValue, accountDailyCap, DEFAULT_MIN_PAIRS,
                DEFAULT_DRIFT_DAY_BPS);
    }

    /** The settings in effect now; read on every call, so a configuration reload applies at the next step. */
    public static HouseSettings fromConfig() {
        return new HouseSettings((int) Math.round(EconomyConfig.REFERENCE_DRIFT_STEP_PERCENT.get() * 100.0D),
                (int) Math.round(EconomyConfig.REFERENCE_BAND_MIN_PERCENT.get() * 100.0D),
                (int) Math.round(EconomyConfig.REFERENCE_BAND_MAX_PERCENT.get() * 100.0D),
                EconomyConfig.REFERENCE_MIN_TRADE_VALUE.get(), EconomyConfig.REFERENCE_ACCOUNT_DAILY_CAP.get(),
                EconomyConfig.REFERENCE_MIN_PAIRS.get(),
                (int) Math.round(EconomyConfig.REFERENCE_DRIFT_DAY_PERCENT.get() * 100.0D));
    }
}
