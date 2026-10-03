package za.co.neroland.neroeconomy.economy;

/**
 * The one fee rule: {@code ceil(total × percent / 100)}, at least one credit whenever a fee applies
 * at all, and never more than the total. It is always computed on the order total, so splitting an
 * order into smaller ones can never pay less. Exact integer arithmetic in basis points; pure Java,
 * so it is unit tested directly.
 */
public final class Fees {
    /** One percent is one hundred basis points. */
    public static final int BASIS_POINTS_PER_PERCENT = 100;
    private static final long BASIS_POINT_SCALE = 10_000L;
    /** No fee is ever configured above half of the total. */
    public static final int MAX_BASIS_POINTS = 5_000;

    private Fees() { }

    /**
     * A configured percentage as whole basis points, clamped to 0–50 %. Anything that is not a
     * positive number is no fee; a positive percentage too small to be one basis point is one.
     */
    public static int basisPoints(double percent) {
        if (!(percent > 0.0D)) return 0;
        long points = Math.round(Math.min(percent, 50.0D) * BASIS_POINTS_PER_PERCENT);
        return (int) Math.max(1L, Math.min(MAX_BASIS_POINTS, points));
    }

    /** The fee on {@code total} at {@code percent}. */
    public static long fee(long total, double percent) {
        return feeForBasisPoints(total, basisPoints(percent));
    }

    /** The fee on {@code total} at {@code basisPoints} hundredths of a percent. */
    public static long feeForBasisPoints(long total, int basisPoints) {
        if (total <= 0L || basisPoints <= 0) return 0L;
        int points = Math.min(MAX_BASIS_POINTS, basisPoints);
        // total × points / 10000, rounded up, without the product ever leaving 64 bits.
        long whole = total / BASIS_POINT_SCALE * points;
        long partial = (total % BASIS_POINT_SCALE * points + BASIS_POINT_SCALE - 1L) / BASIS_POINT_SCALE;
        return Math.min(total, Math.max(1L, whole + partial));
    }

    /** What is left of {@code total} once the fee at {@code percent} has been taken. */
    public static long afterFee(long total, double percent) {
        return total <= 0L ? 0L : total - fee(total, percent);
    }

    /** A flat fee as configured: never negative. */
    public static long flat(long configured) { return Math.max(0L, configured); }

    /**
     * A configured percentage as the number a player is shown: the rate actually charged, in whole
     * basis points, without a trailing {@code .0} — {@code 2}, {@code 2.5}, {@code 0.01}.
     */
    public static String percentText(double percent) {
        int points = basisPoints(percent);
        int whole = points / BASIS_POINTS_PER_PERCENT;
        int fraction = points % BASIS_POINTS_PER_PERCENT;
        if (fraction == 0) return Integer.toString(whole);
        String digits = fraction < 10 ? "0" + fraction : Integer.toString(fraction);
        return whole + "." + (digits.endsWith("0") ? digits.substring(0, 1) : digits);
    }
}
