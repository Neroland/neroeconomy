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

    @Test void theDeadSpectatorsAndStaleMenusMayNot() {
        assertFalse(IntentRules.mayAct(false, false, true, true));
        assertFalse(IntentRules.mayAct(true, true, true, true));
        assertFalse(IntentRules.mayAct(true, false, false, true));
        assertFalse(IntentRules.mayAct(true, false, true, false));
    }
}
