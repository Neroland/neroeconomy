package za.co.neroland.neroeconomy.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.direct.TradePresence.Party;
import za.co.neroland.neroeconomy.direct.TradePresence.Verdict;
import za.co.neroland.neroeconomy.direct.TradeSession.CancelReason;

/** Who may trade with whom: in range in the same level, or each at a terminal; alive and not spectating. */
class TradePresenceTest {
    private static final Party HERE = new Party(true, true, false, false);
    private static final Party AT_TERMINAL = new Party(true, true, false, true);
    private static final int RANGE = 16;

    @Test void twoPlayersWithinRangeInTheSameLevelMayTrade() {
        assertEquals(Verdict.OK, TradePresence.check(HERE, HERE, true, 0.0D, RANGE));
        assertEquals(Verdict.OK, TradePresence.check(HERE, HERE, true, 256.0D, RANGE));
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, HERE, true, 256.01D, RANGE));
        // The same coordinates in another dimension are not near.
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, HERE, false, 0.0D, RANGE));
        // The range is the configured one, whatever it is.
        assertEquals(Verdict.OK, TradePresence.check(HERE, HERE, true, 4_096.0D, 64));
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, HERE, true, 4.01D, 2));
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, HERE, true, 1.0D, -3));
        // A distance that is not a number is never within range.
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, HERE, true, Double.NaN, RANGE));
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, HERE, true, -1.0D, RANGE));
        assertTrue(Verdict.OK.ok());
        assertFalse(Verdict.TOO_FAR.ok());
    }

    @Test void twoPlayersEachAtATerminalMayTradeFromAnywhere() {
        assertEquals(Verdict.OK, TradePresence.check(AT_TERMINAL, AT_TERMINAL, false, 1.0e12D, RANGE));
        assertEquals(Verdict.OK, TradePresence.check(AT_TERMINAL, AT_TERMINAL, true, Double.NaN, RANGE));
        // One terminal is not enough.
        assertEquals(Verdict.TOO_FAR, TradePresence.check(AT_TERMINAL, HERE, false, 1.0e12D, RANGE));
        assertEquals(Verdict.TOO_FAR, TradePresence.check(HERE, AT_TERMINAL, true, 1_000.0D, RANGE));
        // But one terminal does no harm to two players who are near each other anyway.
        assertEquals(Verdict.OK, TradePresence.check(AT_TERMINAL, HERE, true, 9.0D, RANGE));
    }

    @Test void theDeadTheSpectatingAndTheAbsentNeverTrade() {
        Party offline = new Party(false, true, false, true);
        Party dead = new Party(true, false, false, true);
        Party spectator = new Party(true, true, true, true);
        for (boolean firstSide : new boolean[] {true, false}) {
            assertEquals(Verdict.OFFLINE, check(offline, firstSide));
            assertEquals(Verdict.DEAD, check(dead, firstSide));
            assertEquals(Verdict.SPECTATING, check(spectator, firstSide));
        }
        assertEquals(Verdict.OFFLINE, TradePresence.check(null, AT_TERMINAL, true, 0.0D, RANGE));
        assertEquals(Verdict.OFFLINE, TradePresence.check(AT_TERMINAL, null, true, 0.0D, RANGE));
        // Being gone is reported before anything else about a player who is gone.
        assertEquals(Verdict.OFFLINE, TradePresence.check(new Party(false, false, true, false), HERE, false, 9e9D, RANGE));
    }

    private static Verdict check(Party odd, boolean firstSide) {
        return firstSide ? TradePresence.check(odd, AT_TERMINAL, true, 0.0D, RANGE)
                : TradePresence.check(AT_TERMINAL, odd, true, 0.0D, RANGE);
    }

    @Test void everyVerdictButOkEndsAnOpenTradeWithItsOwnReason() {
        assertNull(Verdict.OK.cancelReason());
        assertEquals(CancelReason.DISCONNECTED, Verdict.OFFLINE.cancelReason());
        assertEquals(CancelReason.DIED, Verdict.DEAD.cancelReason());
        assertEquals(CancelReason.NOT_ALLOWED, Verdict.SPECTATING.cancelReason());
        assertEquals(CancelReason.OUT_OF_RANGE, Verdict.TOO_FAR.cancelReason());
    }
}
