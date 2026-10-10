package za.co.neroland.neroeconomy.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntentRulesTest {
    @Test void aPresentBlockInTheSameLevelAndInReachKeepsTheMenuOpen() {
        assertTrue(IntentRules.anchored(true, true, 0.0D));
        assertTrue(IntentRules.anchored(true, true, IntentRules.MAX_DISTANCE_SQR));
    }

    @Test void theSameCoordinatesInAnotherLevelDoNotCount() {
        // A player who changed dimension can stand at the very same coordinates as the terminal.
        assertFalse(IntentRules.anchored(true, false, 0.0D));
    }

    @Test void aRemovedBlockOrOneOutOfReachClosesTheMenu() {
        assertFalse(IntentRules.anchored(false, true, 0.0D));
        assertFalse(IntentRules.anchored(true, true, IntentRules.MAX_DISTANCE_SQR + 0.01D));
        assertFalse(IntentRules.anchored(true, true, Double.NaN));
        assertFalse(IntentRules.anchored(true, true, -1.0D));
    }

    @Test void aLivingNonSpectatorWithTheMenuOpenMayAct() {
        assertTrue(IntentRules.mayAct(true, false, true, true));
    }

    @Test void aPayloadIsAddressedOnlyToTheOpenMenuOfItsKindByItsCurrentId() {
        assertTrue(IntentRules.addressed(true, 7, 7));
        // A stale id (the menu was closed and another opened), one from the future, and a wrapped id.
        assertFalse(IntentRules.addressed(true, 7, 6));
        assertFalse(IntentRules.addressed(true, 7, 8));
        assertFalse(IntentRules.addressed(true, 7, Integer.MIN_VALUE));
        assertFalse(IntentRules.addressed(true, 0, -1));
        // The right id on a menu of another kind (the player's own inventory is menu 0).
        assertFalse(IntentRules.addressed(false, 7, 7));
        assertFalse(IntentRules.addressed(false, 0, 0));
    }

    @Test void aBurstOfPacketsInOneTickIsHandledAtMostTheBudgetsBurstTimes() {
        for (RateLimiter.Budget budget : RateLimiter.Budget.values()) {
            java.util.UUID player = java.util.UUID.randomUUID();
            int allowed = 0;
            for (int packet = 0; packet < 50 + budget.burst() * 4; packet++)
                if (IntentGuard.withinBudget(player, budget)) allowed++;
            IntentGuard.forget(player);
            // A token may refill while the loop runs; never more than that.
            assertTrue(allowed >= budget.burst() && allowed <= budget.burst() + 1, budget + " allowed " + allowed);
        }
    }

    @Test void theDeadSpectatorsAndStaleMenusMayNot() {
        assertFalse(IntentRules.mayAct(false, false, true, true));
        assertFalse(IntentRules.mayAct(true, true, true, true));
        assertFalse(IntentRules.mayAct(true, false, false, true));
        assertFalse(IntentRules.mayAct(true, false, true, false));
    }
}
