package za.co.neroland.neroeconomy.exchange;

import java.util.Collection;

/**
 * "What could happen in a week": for a product and a stake, a bad, a typical and a good outcome —
 * the 10th, 50th and 90th percentile of the product's weekly return, worked out from the model's
 * own published numbers (drift, volatility, reversion, the daily limit, the dividend) and from
 * nothing else. Pure and deterministic: it draws no random number, reads no price, no anchor and
 * no secret, and so says nothing about where any one price is actually going.
 *
 * <h2>How</h2>
 * A company's price is its anchor times {@code e^x}. Over a week the anchor grows by the drift,
 * and {@code x} — the random part, pulled back by the reversion — ends up normally distributed
 * with variance
 *
 * <pre>   σ² × (1 − q^n) / (1 − q),   q = (1 − reversion)²,   n = steps in a week</pre>
 *
 * That week is treated as seven equal days, each day's move cut off at the daily limit (a share
 * that reaches it is halted, so a day cannot move further). The mean and the variance of a cut-off
 * normal are closed formulas; seven of them add up to the week, and the percentiles are
 * {@code mean ± 1.2816 × deviation}. A dividend adds six sevenths of the weekly yield — a new
 * purchase earns on six of the seven day rolls. The index fund is the average of its members.
 *
 * <p>The outcomes are <em>after</em> costs: the stake buys at the ask with the fee on top and is
 * sold at the bid with the fee off it. A Savings Bond has one outcome: the stake and its interest
 * at the end of its own term. It is an estimate for a screen, not a promise.
 */
public final class WeekOutlook {
    /** The 90th percentile of the standard normal distribution; the 10th is its negative. */
    static final double Z = 1.2815515655446004D;
    private static final int DAYS = 7;
    private static final double BPS = 10_000.0D;

    private WeekOutlook() { }

    /**
     * Three outcomes for a stake.
     *
     * @param stake              what was put in, fee included
     * @param bad                what comes back in a bad week: one week in ten is worse
     * @param typical            what comes back in a typical week
     * @param good               what comes back in a good week: one week in ten is better
     * @param badBasisPoints     the same as a change of the stake, in hundredths of a percent
     * @param days               how long the outcome takes: 7, or a bond's own term
     * @param grossBadBasisPoints     the price's own move before costs, in hundredths of a percent;
     *                                with the two below, what a test compares with the model
     */
    public record Outcome(long stake, long bad, long typical, long good, int badBasisPoints, int typicalBasisPoints,
            int goodBasisPoints, int days, int grossBadBasisPoints, int grossTypicalBasisPoints,
            int grossGoodBasisPoints) { }

    /** A week in one company's shares. */
    public static Outcome stock(StockDefinition definition, ExchangeSettings settings, TradingTerms terms,
            long stepMillis, long stake) {
        double[] week = week(definition, settings, stepMillis);
        double dividend = definition.dividendYield() / 100.0D * (DAYS - 1) / DAYS;
        return outcome(week[0], week[1], dividend, terms.spreadBps(definition.type()), terms.feeBps(), stake);
    }

    /** A week in the index fund: the average of every listed company. */
    public static Outcome index(Collection<StockDefinition> members, ExchangeSettings settings, TradingTerms terms,
            long stepMillis, long stake) {
        if (members == null || members.isEmpty()) return outcome(0.0D, 0.0D, 0.0D, terms.spreadIndexBps(), terms.feeBps(), stake);
        // The fund moves by the mean of its members' moves: the mean of their expected ratios, and
        // — the members moving independently — a deviation that shrinks with their number.
        double ratio = 0.0D;
        double variance = 0.0D;
        for (StockDefinition member : members) {
            double[] week = week(member, settings, stepMillis);
            double expected = StrictMath.exp(week[0] + week[1] * week[1] / 2.0D);
            ratio += expected;
            variance += expected * expected * StrictMath.expm1(week[1] * week[1]);
        }
        int count = members.size();
        ratio /= count;
        variance /= (double) count * count;
        // Back to a mean and a deviation of the logarithm, for a ratio that is roughly log-normal.
        double logVariance = StrictMath.log1p(variance / (ratio * ratio));
        return outcome(StrictMath.log(ratio) - logVariance / 2.0D, StrictMath.sqrt(logVariance), 0.0D,
                terms.spreadIndexBps(), terms.feeBps(), stake);
    }

    /** A Savings Bond held to the end of its term: one certain outcome, and no costs. */
    public static Outcome bond(BondTerm term, TradingTerms terms, long stake) {
        long principal = Math.max(0L, stake);
        long back = ExchangeMath.add(principal, BondTerm.interest(principal, terms.bondRateBps(term)));
        int change = principal == 0L ? 0 : (int) ExchangeMath.mulDiv(back - principal, 10_000L, principal, false);
        return new Outcome(principal, back, back, back, change, change, change, term.days(), change, change, change);
    }

    /**
     * The mean and the deviation of the logarithm of one company's price ratio over a week, with
     * each day cut off at the daily limit.
     */
    static double[] week(StockDefinition definition, ExchangeSettings settings, long stepMillis) {
        long step = Math.max(1L, stepMillis);
        double steps = (double) PriceModel.WEEK_MILLIS / step;
        double sigma = PriceModel.volatilityPerStep(definition.volatility(), step);
        double keep = (1.0D - settings.reversion()) * (1.0D - settings.reversion());
        double variance = keep >= 1.0D ? sigma * sigma * steps
                : sigma * sigma * (1.0D - StrictMath.pow(keep, steps)) / (1.0D - keep);
        double dayMean = StrictMath.log1p(definition.drift() / 100.0D) / DAYS;
        double dayDeviation = StrictMath.sqrt(variance / DAYS);
        double limit = Math.min(0.99D, settings.dayLimitBps(definition.type()) / BPS);
        if (!(dayDeviation > 0.0D) || !(limit > 0.0D)) return new double[] {dayMean * DAYS, 0.0D};
        double low = StrictMath.log1p(-limit);
        double high = StrictMath.log1p(limit);
        double alpha = (low - dayMean) / dayDeviation;
        double beta = (high - dayMean) / dayDeviation;
        double below = cumulative(alpha);
        double above = 1.0D - cumulative(beta);
        double inside = 1.0D - below - above;
        double densityLow = density(alpha);
        double densityHigh = density(beta);
        double mean = low * below + high * above + dayMean * inside - dayDeviation * (densityHigh - densityLow);
        double square = low * low * below + high * high * above + dayMean * dayMean * inside
                + 2.0D * dayMean * dayDeviation * (densityLow - densityHigh)
                + dayDeviation * dayDeviation * (inside + alpha * densityLow - beta * densityHigh);
        return new double[] {mean * DAYS, StrictMath.sqrt(Math.max(0.0D, (square - mean * mean) * DAYS))};
    }

    private static Outcome outcome(double logMean, double logDeviation, double dividend, int spreadBps, int feeBps,
            long stake) {
        long in = Math.max(0L, stake);
        double half = spreadBps / (2.0D * BPS);
        double fee = Math.min(feeBps, 5_000) / BPS;
        // Bought at the ask with the fee on top, sold at the bid with the fee off: what is left of a price ratio.
        double kept = (1.0D - half) / (1.0D + half) * (1.0D - fee) / (1.0D + fee);
        double[] gross = new double[3];
        long[] back = new long[3];
        int[] change = new int[3];
        for (int index = 0; index < 3; index++) {
            gross[index] = StrictMath.exp(logMean + (index - 1) * Z * logDeviation);
            double net = gross[index] * kept + dividend / (1.0D + half) / (1.0D + fee);
            back[index] = (long) Math.max(0.0D, Math.min(0x1.0p62, StrictMath.floor(in * net)));
            change[index] = (int) Math.round((net - 1.0D) * BPS);
        }
        return new Outcome(in, back[0], back[1], back[2], change[0], change[1], change[2], DAYS,
                (int) Math.round((gross[0] - 1.0D) * BPS), (int) Math.round((gross[1] - 1.0D) * BPS),
                (int) Math.round((gross[2] - 1.0D) * BPS));
    }

    /** The standard normal density. */
    private static double density(double x) {
        return StrictMath.exp(-0.5D * x * x) / StrictMath.sqrt(2.0D * StrictMath.PI);
    }

    /**
     * The standard normal distribution function, to about seven decimal places (Abramowitz and
     * Stegun 7.1.26), from {@link StrictMath} only, so it gives the same number on every runtime.
     */
    static double cumulative(double x) {
        double t = 1.0D / (1.0D + 0.3275911D * Math.abs(x) / StrictMath.sqrt(2.0D));
        double poly = t * (0.254829592D + t * (-0.284496736D + t * (1.421413741D + t * (-1.453152027D + t * 1.061405429D))));
        double erf = 1.0D - poly * StrictMath.exp(-x * x / 2.0D);
        return x >= 0.0D ? 0.5D * (1.0D + erf) : 0.5D * (1.0D - erf);
    }
}
