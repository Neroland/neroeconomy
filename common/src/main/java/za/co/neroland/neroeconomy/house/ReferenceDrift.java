package za.co.neroland.neroeconomy.house;

import java.math.BigInteger;

/**
 * How a commodity's reference price drifts toward what players pay each other (E3). Pure and exact.
 *
 * <p>Each simulation step the counted volume of player-to-player fills since the last step — after
 * the per-account caps, without House fills, self-matches or trades below the minimum value —
 * pulls the reference toward its volume-weighted price by {@code V / (V + L)} of the gap, where
 * {@code V} is the counted value and {@code L} the commodity's liquidity: a full day of House trade
 * at the data reference. The move is then clamped to a fraction of the data reference per step and
 * the result to a band around it. With no counted trade the reference stays where it is.
 */
public final class ReferenceDrift {
    /** The least liquidity a commodity is given, in units at its data reference. */
    public static final long MIN_LIQUIDITY_UNITS = 64L;
    private static final long BPS = 10_000L;

    private ReferenceDrift() { }

    /**
     * Where a reference may be.
     *
     * @param dataMillis the data reference, in milli-credits
     * @param minMillis  the lowest the reference may drift to
     * @param maxMillis  the highest
     * @param stepMillis the most it may move in one step, at least 1
     */
    public record Bounds(long dataMillis, long minMillis, long maxMillis, long stepMillis) {
        public long clamp(long refMillis) { return Math.max(minMillis, Math.min(maxMillis, refMillis)); }
    }

    /**
     * The bounds for a data reference of {@code dataReference} whole credits.
     *
     * @param stepBps the per-step clamp, in hundredths of a percent of the data reference
     * @param minBps  the band's floor, in hundredths of a percent of the data reference
     * @param maxBps  the band's ceiling
     */
    public static Bounds bounds(long dataReference, int stepBps, int minBps, int maxBps) {
        long data = Math.multiplyExact(Math.max(1L, dataReference), HousePricing.MILLI);
        long low = Math.max(1L, Math.multiplyExact(data, Math.max(0, minBps)) / BPS);
        long high = Math.max(low, Math.multiplyExact(data, Math.max(0, maxBps)) / BPS);
        long step = Math.max(1L, Math.multiplyExact(data, Math.max(0, stepBps)) / BPS);
        return new Bounds(data, Math.min(low, data), Math.max(high, data), step);
    }

    /** The liquidity of a commodity: a day of House trade at its data reference, in credits. */
    public static long liquidity(long dataReference, long dailySupply, long dailyDemand) {
        long units = Math.max(MIN_LIQUIDITY_UNITS, Math.max(0L, dailySupply) + Math.max(0L, dailyDemand));
        return Math.multiplyExact(Math.max(1L, dataReference), units);
    }

    /**
     * The reference after one step.
     *
     * @param refMillis the reference now
     * @param quantity  counted units traded between players since the last step
     * @param value     counted credits they traded for
     * @param liquidity see {@link #liquidity}
     */
    public static long next(long refMillis, long quantity, long value, long liquidity, Bounds bounds) {
        long reference = bounds.clamp(refMillis);
        if (quantity <= 0L || value <= 0L) return reference;
        BigInteger vwap = BigInteger.valueOf(value).multiply(BigInteger.valueOf(HousePricing.MILLI))
                .divide(BigInteger.valueOf(quantity));
        BigInteger gap = vwap.subtract(BigInteger.valueOf(reference));
        BigInteger delta = gap.multiply(BigInteger.valueOf(value))
                .divide(BigInteger.valueOf(value).add(BigInteger.valueOf(Math.max(1L, liquidity))));
        BigInteger step = BigInteger.valueOf(bounds.stepMillis());
        if (delta.compareTo(step) > 0) delta = step;
        if (delta.compareTo(step.negate()) < 0) delta = step.negate();
        return bounds.clamp(reference + delta.longValueExact());
    }
}
