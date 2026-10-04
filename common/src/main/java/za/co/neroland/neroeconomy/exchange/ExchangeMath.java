package za.co.neroland.neroeconomy.exchange;

import java.math.BigInteger;

import za.co.neroland.neroeconomy.economy.Fees;

/**
 * The arithmetic of a trade on the exchange, and nothing else: no store, no ledger, no clock. Both
 * the real desk and the practice book price a trade with these very functions, which is the only
 * thing the two share besides the lot arithmetic of {@link Holding}.
 *
 * <p>Prices are whole hundredths of a credit; credits and units are whole. Every rounding goes
 * against the trader:
 *
 * <pre>
 *   ask   = ceil (price × (1 + spread / 2))        what a buyer pays per unit, in hundredths
 *   bid   = floor(price × (1 − spread / 2))        what a seller receives per unit, in hundredths
 *   cost     of n units = ceil (n × ask / 100)     whole credits
 *   proceeds of n units = floor(n × bid / 100)     whole credits
 *   fee      on a total = ceil (total × fee %)     at least 1 credit when a fee applies ({@link Fees})
 * </pre>
 *
 * <p>Two things follow, and are tested for every price. The ask is always above the bid whenever
 * the spread is above zero, so <b>buying and selling at once always loses</b>, with or without a
 * fee. And because {@code ceil(a) + ceil(b) ≥ ceil(a + b)} and {@code floor(a) + floor(b) ≤
 * floor(a + b)}, <b>an order split into smaller ones never does better than the whole order</b>.
 */
public final class ExchangeMath {
    /** Hundredths in a credit. */
    public static final long CENTS = PriceModel.CENTS_PER_CREDIT;
    /** A spread is given in hundredths of a percent; half of it goes on each side of the price. */
    private static final long HALF_SPREAD_SCALE = 20_000L;
    private static final long BPS = 10_000L;

    private ExchangeMath() { }

    /** A percentage as hundredths of a percent, at least 0. */
    public static int bps(double percent) {
        if (!(percent > 0.0D)) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(percent * 100.0D));
    }

    /** What a buyer pays per unit, in hundredths of a credit: the price plus half the spread, rounded up. */
    public static long ask(long priceCents, int spreadBps) {
        long price = Math.max(PriceModel.MIN_PRICE_CENTS, priceCents);
        return mulDiv(price, HALF_SPREAD_SCALE + Math.max(0, spreadBps), HALF_SPREAD_SCALE, true);
    }

    /** What a seller receives per unit, in hundredths of a credit: the price less half the spread, rounded down. */
    public static long bid(long priceCents, int spreadBps) {
        long price = Math.max(PriceModel.MIN_PRICE_CENTS, priceCents);
        return mulDiv(price, Math.max(0L, HALF_SPREAD_SCALE - Math.max(0, spreadBps)), HALF_SPREAD_SCALE, false);
    }

    /** Whole credits {@code units} cost at {@code askCents}, rounded up; {@link Long#MAX_VALUE} when it does not fit. */
    public static long cost(long units, long askCents) {
        if (units <= 0L || askCents <= 0L) return 0L;
        return mulDiv(units, askCents, CENTS, true);
    }

    /** Whole credits {@code units} fetch at {@code bidCents}, rounded down. */
    public static long proceeds(long units, long bidCents) {
        if (units <= 0L || bidCents <= 0L) return 0L;
        return mulDiv(units, bidCents, CENTS, false);
    }

    /** The exchange fee on {@code total} credits: rounded up, at least 1 when a fee applies, never more than the total. */
    public static long fee(long total, int feeBps) { return Fees.feeForBasisPoints(total, feeBps); }

    /** What a purchase takes from the balance: the cost and the fee on it. */
    public static long spend(long units, long askCents, int feeBps) {
        long cost = cost(units, askCents);
        long fee = fee(cost, feeBps);
        return Long.MAX_VALUE - cost < fee ? Long.MAX_VALUE : cost + fee;
    }

    /**
     * The most whole units {@code credits} buy at {@code askCents}, fee included: the largest
     * {@code n} with {@code cost(n) + fee(cost(n)) ≤ credits}. 0 when they do not cover one unit.
     */
    public static long unitsFor(long credits, long askCents, int feeBps) {
        if (credits <= 0L || askCents <= 0L) return 0L;
        // A first guess from the exact ratio, then corrected for the two roundings.
        long guess = mulDiv(credits, CENTS * BPS, BigInteger.valueOf(askCents)
                .multiply(BigInteger.valueOf(BPS + Math.max(0, Math.min(Fees.MAX_BASIS_POINTS, feeBps)))));
        long units = Math.min(Holding.MAX_UNITS, guess);
        while (units > 0L && spend(units, askCents, feeBps) > credits) units--;
        while (units < Holding.MAX_UNITS && spend(units + 1L, askCents, feeBps) <= credits) units++;
        return units;
    }

    /**
     * {@code a × b / divisor}, exact, rounded down or up; {@link Long#MAX_VALUE} when the result
     * does not fit. The operands are never negative here.
     */
    public static long mulDiv(long a, long b, long divisor, boolean up) {
        if (a <= 0L || b <= 0L || divisor <= 0L) return 0L;
        if (Math.multiplyHigh(a, b) == 0L && a * b >= 0L) {
            long product = a * b;
            long quotient = product / divisor;
            return up && product % divisor != 0L ? quotient + 1L : quotient;
        }
        BigInteger[] parts = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
                .divideAndRemainder(BigInteger.valueOf(divisor));
        BigInteger quotient = up && parts[1].signum() != 0 ? parts[0].add(BigInteger.ONE) : parts[0];
        return quotient.bitLength() > 62 ? Long.MAX_VALUE : quotient.longValue();
    }

    /** {@code a × b / divisor} rounded down, for a divisor too large for a {@code long}. */
    private static long mulDiv(long a, long b, BigInteger divisor) {
        BigInteger quotient = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(divisor);
        return quotient.bitLength() > 62 ? Long.MAX_VALUE : quotient.longValue();
    }

    /**
     * {@code a × b × c / divisor}, exact, rounded down: what a dividend is worked out with. Never
     * more than {@link Long#MAX_VALUE}.
     */
    public static long mulMulDiv(long a, long b, long c, long divisor) {
        if (a <= 0L || b <= 0L || c <= 0L || divisor <= 0L) return 0L;
        BigInteger quotient = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).multiply(BigInteger.valueOf(c))
                .divide(BigInteger.valueOf(divisor));
        return quotient.bitLength() > 62 ? Long.MAX_VALUE : quotient.longValue();
    }

    /** {@code left + right}, stopping at {@link Long#MAX_VALUE}; both are never negative here. */
    public static long add(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
