package za.co.neroland.neroeconomy.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** D2 hardening: a vault slot whose count grows is asked the same question as a new stack. */
class VaultSlotRuleTest {
    @Test void goodsAlreadyInASlotMayStayInWhateverQuantityIsLeft() {
        assertEquals(10, VaultSlotRule.mayStay(10, 10, false));
        assertEquals(4, VaultSlotRule.mayStay(10, 4, false), "taking some out never needs permission");
    }

    @Test void aSlotThatGrowsKeepsTheAddedGoodsOnlyIfTheyMayEnterTheVault() {
        assertEquals(10, VaultSlotRule.mayStay(10, 64, false), "a creative top-up of an existing stack goes back");
        assertEquals(64, VaultSlotRule.mayStay(10, 64, true));
    }
}
