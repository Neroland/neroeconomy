package za.co.neroland.neroeconomy.link;

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
}
