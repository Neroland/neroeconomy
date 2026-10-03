package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EconomyAlertsTest {
    @Test void channelRecoversAfterTheWindowEvenUnderContinuousActivity() {
        String key = UUID.randomUUID() + "|shop_sold_out";
        long start = 1_000_000L;
        assertTrue(EconomyAlerts.allow(key, start));
        // A suppressed attempt must not move the window forward, or the channel would starve.
        assertFalse(EconomyAlerts.allow(key, start + 10_000L));
        assertFalse(EconomyAlerts.allow(key, start + 20_000L));
        assertFalse(EconomyAlerts.allow(key, start + 29_999L));
        assertTrue(EconomyAlerts.allow(key, start + 30_000L));
        assertFalse(EconomyAlerts.allow(key, start + 31_000L));
    }

    @Test void kindsAndPlayersAreLimitedIndependently() {
        UUID player = UUID.randomUUID();
        long now = 5_000_000L;
        assertTrue(EconomyAlerts.allow(player + "|outbid", now));
        assertTrue(EconomyAlerts.allow(player + "|auction_won", now));
        assertTrue(EconomyAlerts.allow(UUID.randomUUID() + "|outbid", now));
        assertFalse(EconomyAlerts.allow(player + "|outbid", now + 1L));
    }

    @Test void alertIdIsStablePerKindAndCarriesNoTimestamp() {
        assertEquals("neroeconomy:outbid", EconomyAlerts.alertId("outbid"));
        // The same kind always maps to the same id, so Core replaces the previous alert of that kind.
        assertEquals(EconomyAlerts.alertId("shop_sold_out"), EconomyAlerts.alertId("shop_sold_out"));
        assertFalse(EconomyAlerts.alertId("outbid").equals(EconomyAlerts.alertId("auction_won")));
    }

    @Test void forgetDropsOnlyThatPlayersEntriesAndToleratesUnknownPlayers() {
        UUID erased = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        long now = 9_000_000L;
        assertTrue(EconomyAlerts.allow(erased + "|outbid", now));
        assertTrue(EconomyAlerts.allow(erased + "|auction_won", now));
        assertTrue(EconomyAlerts.allow(other + "|outbid", now));
        EconomyAlerts.forget(erased);
        assertFalse(EconomyAlerts.tracked(erased, "outbid"));
        assertFalse(EconomyAlerts.tracked(erased, "auction_won"));
        assertTrue(EconomyAlerts.tracked(other, "outbid"));
        // Nothing stored for these: both must be harmless no-ops.
        EconomyAlerts.forget(UUID.randomUUID());
        EconomyAlerts.forget(null);
        assertTrue(EconomyAlerts.tracked(other, "outbid"));
    }

    @Test void alertTextIsAClosedCatalogueWithNothingThatCouldNameAPlayerOrAnAmount() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (EconomyAlerts.Kind kind : EconomyAlerts.Kind.values()) {
            String text = kind.text();
            // Letters, spaces and sentence punctuation only: no digit (an amount, a UUID, a position),
            // no placeholder that a formatter could fill, and short enough to be one sentence.
            assertTrue(text.matches("[A-Za-z ,.'-]{1,80}"), kind.name());
            assertTrue(text.endsWith("."), kind.name());
            assertTrue(kind.id().matches("[a-z_]{1,32}"), kind.name());
            assertTrue(ids.add(kind.id()), kind.name());
        }
        // The ids that existing servers already hold alerts under must not move.
        assertEquals("contract_fulfilled", EconomyAlerts.Kind.CONTRACT_FULFILLED.id());
        assertEquals("outbid", EconomyAlerts.Kind.OUTBID.id());
        assertEquals("auction_won", EconomyAlerts.Kind.AUCTION_WON.id());
        assertEquals("shop_sold_out", EconomyAlerts.Kind.SHOP_SOLD_OUT.id());
    }

    @Test void clearDropsEveryEntry() {
        UUID player = UUID.randomUUID();
        assertTrue(EconomyAlerts.allow(player + "|outbid", 12_000_000L));
        EconomyAlerts.clear();
        assertFalse(EconomyAlerts.tracked(player, "outbid"));
        // With the limiter empty the very next alert goes out again.
        assertTrue(EconomyAlerts.allow(player + "|outbid", 12_000_001L));
    }
}
