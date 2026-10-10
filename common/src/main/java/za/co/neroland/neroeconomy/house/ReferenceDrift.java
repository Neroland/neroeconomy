package za.co.neroland.neroeconomy.house;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * How a commodity's reference price drifts toward what players pay each other (E3). Pure and exact.
 *
 * <p>Each simulation step the counted volume of player-to-player fills since the last step — after
 * the per-account caps, without House fills, self-matches or trades below the minimum value —
 * pulls the reference toward its volume-weighted price by {@code V / (V + L)} of the gap, where
 * {@code V} is the counted value and {@code L} the commodity's liquidity: a full day of House trade
 * at the data reference. The move is then clamped to a fraction of the data reference per step and
 * the result to a band around it. With no counted trade the reference stays where it is.
 *
 * <p>Wash trading between a few accounts (N1): the counted trade of a step is kept per counterparty
 * pair, and a reference moves only when at least {@code minPairs} distinct pairs traded; no pair
 * counts for more than the pair ranked {@code minPairs} from the top ({@link #counted}). Within a
 * day the reference also stays within a reach of where the day began ({@link Bounds#today}).
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

        /**
         * These bounds narrowed to the day: at most {@code dayBps} of the data reference either side
         * of {@code dayOpenMillis} (itself clamped into the band). 0 keeps the reference where the day began.
         */
        public Bounds today(long dayOpenMillis, int dayBps) {
            long open = clamp(dayOpenMillis);
            long reach = Math.multiplyExact(dataMillis, Math.max(0, dayBps)) / BPS;
            long low = Math.max(minMillis, open - Math.min(open, reach));
            long high = Math.min(maxMillis, open > Long.MAX_VALUE - reach ? Long.MAX_VALUE : open + reach);
            return new Bounds(dataMillis, low, high, stepMillis);
        }
    }

    /**
     * A key for the pair of accounts on a fill, the same whichever of them sold. A one-way 64-bit
     * mix of both ids: it tells pairs apart within a step and names nobody.
     */
    public static long pairKey(UUID first, UUID second) {
        long a = mix(first);
        long b = mix(second);
        return mix(Math.min(a, b) * 0x9E3779B97F4A7C15L + Math.max(a, b));
    }

    private static long mix(UUID id) {
        return mix(id.getMostSignificantBits() * 0xC2B2AE3D27D4EB4FL ^ id.getLeastSignificantBits());
    }

    private static long mix(long value) {
        long h = value ^ (value >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    /**
     * What a step's trade counts for, from {@code [units, credits]} per counterparty pair: nothing
     * when fewer than {@code minPairs} pairs traded; otherwise every pair clipped to the credits of
     * the pair ranked {@code minPairs} from the top (its units in proportion), summed.
     *
     * @return {@code [units, credits]}
     */
    public static long[] counted(Collection<long[]> pairs, int minPairs) {
        int needed = Math.max(1, minPairs);
        List<long[]> rows = new ArrayList<>();
        if (pairs != null) for (long[] pair : pairs) if (pair != null && pair[0] > 0L && pair[1] > 0L) rows.add(pair);
        if (rows.size() < needed) return new long[] {0L, 0L};
        rows.sort((left, right) -> Long.compare(right[1], left[1]));
        long cap = rows.get(needed - 1)[1];
        long units = 0L;
        long value = 0L;
        for (long[] pair : rows) {
            long credits = Math.min(pair[1], cap);
            long count = credits == pair[1] ? pair[0] : Math.max(1L, BigInteger.valueOf(pair[0])
                    .multiply(BigInteger.valueOf(credits)).divide(BigInteger.valueOf(pair[1])).longValueExact());
            units = saturated(units, count);
            value = saturated(value, credits);
        }
        return new long[] {units, value};
    }

    private static long saturated(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
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
