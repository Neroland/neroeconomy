package za.co.neroland.neroeconomy.house;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;

/**
 * The arithmetic of a Merchant House's quotes. Pure and exact: every price is whole credits, every
 * reference is in milli-credits, every pressure in millionths.
 *
 * <p><b>Spread.</b> As in the commodity data and the arbitrage guard, a House sells a commodity's
 * spread <em>above</em> its reference and buys the spread <em>below</em> it, rounded away from the
 * reference. The ask is always strictly above the reference and the bid strictly below it, so a
 * House's ask is always above its own bid. For a whole-credit reference the prices are exactly
 * {@link CommodityDefinition#askAt} and {@link CommodityDefinition#bidAt}.
 *
 * <p><b>Pressure.</b> Buying from a House raises its ask, and selling to it lowers its bid, each by
 * {@code elasticity × units / daily cap}; both decay by the commodity's {@code recovery} each
 * simulation step. Pressure only ever moves the ask up and the bid down, so the ask stays above the bid.
 */
public final class HousePricing {
    /** Milli-credits per credit: the unit a reference price is kept in. */
    public static final long MILLI = 1_000L;
    /** Millionths: the unit pressure is kept in. 1,000,000 is 100 %. */
    public static final long PPM = 1_000_000L;
    /** The most pressure a side can carry: its price at five times, or its bid at nothing. */
    public static final long MAX_PRESSURE = 4_000_000L;
    private static final long BPS = CommodityDefinition.BPS;

    private HousePricing() { }

    /** What a House sells one unit for at reference {@code refMillis}, before pressure. Above the reference. */
    public static long ask(long refMillis, int spreadBps) {
        long reference = Math.max(1L, refMillis);
        long ask = Math.ceilDiv(Math.multiplyExact(reference, BPS + spreadBps), BPS * MILLI);
        return Math.max(ask, Math.floorDiv(reference, MILLI) + 1L);
    }

    /** What a House pays for one unit at reference {@code refMillis}, before pressure. Below the reference; 0 is nothing. */
    public static long bid(long refMillis, int spreadBps) {
        if (refMillis < 1L) return 0L;
        long bid = Math.floorDiv(Math.multiplyExact(refMillis, BPS - spreadBps), BPS * MILLI);
        return Math.max(0L, Math.min(bid, Math.ceilDiv(refMillis, MILLI) - 1L));
    }

    /** The ask with the buying pressure on it: never below {@code ask}. */
    public static long askWithPressure(long ask, long pressure) {
        long applied = Math.max(0L, Math.min(MAX_PRESSURE, pressure));
        return Math.ceilDiv(Math.multiplyExact(ask, PPM + applied), PPM);
    }

    /** The bid with the selling pressure on it: never above {@code bid}, never below 0. */
    public static long bidWithPressure(long bid, long pressure) {
        long applied = Math.max(0L, Math.min(PPM, pressure));
        return Math.floorDiv(Math.multiplyExact(Math.max(0L, bid), PPM - applied), PPM);
    }

    /** The pressure {@code units} add to a side whose daily cap is {@code dailyCap}: {@code elasticity × units / cap}. */
    public static long pressureFor(double elasticity, long units, long dailyCap) {
        if (units <= 0L || dailyCap <= 0L || !(elasticity > 0.0D)) return 0L;
        double value = Math.ceil(elasticity * PPM * units / dailyCap);
        return value >= MAX_PRESSURE ? MAX_PRESSURE : (long) value;
    }

    /** Pressure plus more, at most {@link #MAX_PRESSURE}. */
    public static long add(long pressure, long more) {
        long sum = Math.max(0L, pressure) + Math.max(0L, more);
        return sum < 0L || sum > MAX_PRESSURE ? MAX_PRESSURE : sum;
    }

    /** One step of recovery: {@code recovery} of the pressure is gone. Reaches 0 in finite steps. */
    public static long decay(long pressure, double recovery) {
        if (pressure <= 0L) return 0L;
        double gone = Math.ceil(pressure * Math.max(0.0D, Math.min(1.0D, recovery)));
        return Math.max(0L, pressure - Math.max(1L, (long) gone));
    }
}
