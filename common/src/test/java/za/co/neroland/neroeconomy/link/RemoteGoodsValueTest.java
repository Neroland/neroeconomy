package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.goods.Lot;

/** R1: what goods leaving an account through the app count as; none is free, none can be made cheap. */
class RemoteGoodsValueTest {
    private static final String DIAMOND = "minecraft:diamond";
    private static final String DIRT = "minecraft:dirt";
    private static final String SHULKER = "minecraft:shulker_box";
    private static final CommodityRegistry COMMODITIES = CommodityRegistry.of(
            List.of(CommodityDefinition.of(DIAMOND, 120L, 100L, 100L, 1000, CommodityClass.STAPLE)),
            CommodityCatalog.of(Set.of(), Set.of(DIAMOND, DIRT, SHULKER), Map.of(), Set.of()));

    private static RemoteGoodsValue.Pricing pricing(long liveMillis) {
        return new RemoteGoodsValue.Pricing(COMMODITIES, key -> liveMillis, 10L, 500L);
    }

    @Test void aCommodityCountsAtTheHigherOfItsDataAndLiveReferenceRoundedUp() {
        assertEquals(120L, RemoteGoodsValue.unitValue(pricing(-1L), DIAMOND, Lot.PRISTINE));
        assertEquals(151L, RemoteGoodsValue.unitValue(pricing(150_001L), DIAMOND, Lot.PRISTINE), "rounded up");
        assertEquals(120L, RemoteGoodsValue.unitValue(pricing(1_000L), DIAMOND, Lot.PRISTINE),
                "a live price pushed down does not make goods cheaper to send away");
        assertEquals(64L * 120L, RemoteGoodsValue.of(pricing(-1L), DIAMOND, Lot.PRISTINE, 64L));
    }

    @Test void goodsThatAreNoCommodityAndModifiedGoodsAreNeverFree() {
        assertEquals(10L, RemoteGoodsValue.unitValue(pricing(-1L), DIRT, Lot.PRISTINE));
        assertEquals(500L, RemoteGoodsValue.unitValue(pricing(-1L), SHULKER, 7), "a filled shulker box");
        assertEquals(500L, RemoteGoodsValue.unitValue(pricing(-1L), DIAMOND, 3), "modified, cheaper commodity");
        assertEquals(900L, RemoteGoodsValue.unitValue(pricing(900_000L), DIAMOND, 3), "modified, dearer commodity");
        RemoteGoodsValue.Pricing unknown = new RemoteGoodsValue.Pricing(null, null, 0L, -5L);
        assertEquals(1L, RemoteGoodsValue.unitValue(unknown, DIAMOND, Lot.PRISTINE), "never below 1");
        assertEquals(1L, RemoteGoodsValue.unitValue(unknown, DIAMOND, 2));
        assertEquals(0L, RemoteGoodsValue.of(unknown, DIAMOND, Lot.PRISTINE, 0L));
    }

    @Test void sumsAndProductsSaturateInsteadOfWrapping() {
        RemoteGoodsValue.Pricing dear = new RemoteGoodsValue.Pricing(null, null, Long.MAX_VALUE / 2L, 1L);
        assertEquals(Long.MAX_VALUE, RemoteGoodsValue.of(dear, DIRT, Lot.PRISTINE, 3L));
        assertEquals(Long.MAX_VALUE, RemoteGoodsValue.of(dear, List.of(Lot.of(DIRT, 1), Lot.of(DIRT, 1), Lot.of(DIRT, 1))));
        assertEquals(64L * 120L + 10L * 10L, RemoteGoodsValue.of(pricing(-1L),
                List.of(Lot.of(DIAMOND, 64), Lot.of(DIRT, 10))));
    }
}
