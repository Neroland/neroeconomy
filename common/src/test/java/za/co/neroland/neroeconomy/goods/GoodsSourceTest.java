package za.co.neroland.neroeconomy.goods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The one rule on who may be a source of goods: never creative mode, never a spectator. */
class GoodsSourceTest {
    @Test void aSurvivalPlayerMaySupplyGoods() {
        assertTrue(GoodsSource.allowed(false, false, false));
    }

    @Test void creativeModeSpectatorsAndEndlessItemsNeverMay() {
        assertFalse(GoodsSource.allowed(true, false, false));
        assertFalse(GoodsSource.allowed(false, true, false));
        // Endless items without the game mode, as another mod's ability can grant, count the same.
        assertFalse(GoodsSource.allowed(false, false, true));
        assertFalse(GoodsSource.allowed(true, true, true));
    }

    @Test void nobodyIsNotASource() {
        assertFalse(GoodsSource.allowed(null));
    }
}
