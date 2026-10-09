package za.co.neroland.neroeconomy.guard;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * An exact fraction of credits, never negative. The arbitrage guard prices goods per unit through
 * recipes that turn one thing into four or nine into one; floating point would turn "exactly as
 * much" into "a little less" or "a little more", and the guard's answer hangs on that comparison.
 */
public record Ratio(BigInteger numerator, BigInteger denominator) implements Comparable<Ratio> {
    public static final Ratio ZERO = new Ratio(BigInteger.ZERO, BigInteger.ONE);

    public Ratio {
        if (numerator == null || denominator == null || numerator.signum() < 0 || denominator.signum() <= 0)
            throw new IllegalArgumentException("ratio");
        BigInteger common = numerator.gcd(denominator);
        if (common.signum() > 0 && !common.equals(BigInteger.ONE)) {
            numerator = numerator.divide(common);
            denominator = denominator.divide(common);
        }
    }

    public static Ratio of(long whole) { return new Ratio(BigInteger.valueOf(whole), BigInteger.ONE); }

    public Ratio plus(Ratio other) {
        return new Ratio(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Ratio times(long factor) { return new Ratio(numerator.multiply(BigInteger.valueOf(factor)), denominator); }

    /** @param divisor at least 1 */
    public Ratio dividedBy(long divisor) { return new Ratio(numerator, denominator.multiply(BigInteger.valueOf(divisor))); }

    @Override public int compareTo(Ratio other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    public boolean lessThan(Ratio other) { return compareTo(other) < 0; }

    /** The value to two decimal places, rounded half up: for reports only, never for a decision. */
    public String text() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), 2, RoundingMode.HALF_UP).toPlainString();
    }
}
