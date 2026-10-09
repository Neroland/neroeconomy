package za.co.neroland.neroeconomy.link;

import java.util.List;
import java.util.function.ToLongFunction;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.goods.Lot;

/**
 * What goods that leave an account through the companion app are worth, in whole credits, for
 * {@code remoteDailyGoodsLimit}. A stolen app token must not be able to move goods out of an
 * account without limit, so every unit is given a value, and none is free:
 *
 * <ul>
 * <li>An unmodified commodity counts at the higher of its data reference price and its Merchant
 *     House's live reference (milli-credits, rounded up), so pushing a live price down does not make
 *     goods cheaper to send away.</li>
 * <li>Goods that are no commodity count at {@code remoteGoodsFloorValue} per unit.</li>
 * <li>Modified goods (a custom name, enchantments, contents; any payload) can hold far more than
 *     their item suggests, such as a filled shulker box, so they count at
 *     {@code remoteModifiedGoodsValue} per unit, or more when their item is a dearer commodity.</li>
 * </ul>
 *
 * <p>Exact arithmetic: products and sums saturate at {@link Long#MAX_VALUE} rather than wrap, which a
 * limit then refuses. Pure: the commodity data and the live reference are handed in.
 */
public final class RemoteGoodsValue {
    private RemoteGoodsValue() { }

    /**
     * How a value is worked out.
     *
     * @param commodities   the world's commodities; {@link CommodityRegistry#UNAVAILABLE} when unknown
     * @param liveMillis    a commodity key's live House reference in milli-credits, or -1 when none
     * @param floor         credits per unit of goods that are no commodity, at least 1
     * @param modifiedFloor credits per unit of modified goods, at least 1
     */
    public record Pricing(CommodityRegistry commodities, ToLongFunction<String> liveMillis, long floor,
            long modifiedFloor) {
        public Pricing {
            if (commodities == null) commodities = CommodityRegistry.UNAVAILABLE;
            if (liveMillis == null) liveMillis = key -> -1L;
            floor = Math.max(1L, floor);
            modifiedFloor = Math.max(1L, modifiedFloor);
        }
    }

    /** Credits one unit of {@code item} in form {@code payload} counts as; always at least 1. */
    public static long unitValue(Pricing pricing, String item, int payload) {
        long value = payload == Lot.PRISTINE ? pricing.floor() : pricing.modifiedFloor();
        CommodityDefinition commodity = item == null ? null : pricing.commodities().forItem(item);
        if (commodity != null) {
            long reference = commodity.referencePrice();
            long live = pricing.liveMillis().applyAsLong(commodity.key());
            if (live > 0L) reference = Math.max(reference, Math.ceilDiv(live, 1000L));
            // A commodity's own price replaces the floor for unmodified goods; modified goods keep the higher.
            value = payload == Lot.PRISTINE ? Math.max(1L, reference) : Math.max(value, reference);
        }
        return value;
    }

    /** What {@code count} units of {@code item} in form {@code payload} count as. */
    public static long of(Pricing pricing, String item, int payload, long count) {
        return count <= 0L ? 0L : times(unitValue(pricing, item, payload), count);
    }

    /** What every lot of {@code lots} counts as, together. */
    public static long of(Pricing pricing, List<Lot> lots) {
        long total = 0L;
        if (lots != null) for (Lot lot : lots) if (lot != null) total = plus(total, of(pricing, lot.item(), lot.payload(),
                lot.count()));
        return total;
    }

    private static long times(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long plus(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
}
