package za.co.neroland.neroeconomy.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class NeroEconomyTelemetryTest {
    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test void scrubRemovesDashedAndBareUuids() {
        assertEquals("export for <uuid> failed", NeroEconomyTelemetry.scrub("export for " + PLAYER + " failed"));
        assertEquals("id=<uuid>;", NeroEconomyTelemetry.scrub("id=" + PLAYER.toString().replace("-", "") + ";"));
        assertEquals("<uuid> and <uuid>", NeroEconomyTelemetry.scrub(
                PLAYER.toString().toUpperCase(java.util.Locale.ROOT) + " and " + PLAYER));
    }

    @Test void scrubRemovesTheUuidFileNameAndTheHomePathTogether() {
        String scrubbed = NeroEconomyTelemetry.scrub(
                "/home/someone/server/world/neroeconomy-exports/" + PLAYER + ".json (Permission denied)");
        assertFalse(scrubbed.contains(PLAYER.toString()));
        assertFalse(scrubbed.contains("someone"));
        assertEquals("/~/server/world/neroeconomy-exports/<uuid>.json (Permission denied)", scrubbed);
        String windows = NeroEconomyTelemetry.scrub("C:\\Users\\someone\\world\\" + PLAYER + ".json");
        assertFalse(windows.contains("someone"));
        assertFalse(windows.contains(PLAYER.toString()));
    }

    @Test void scrubLeavesOrdinaryTextAndShortHexAlone() {
        assertEquals("", NeroEconomyTelemetry.scrub(null));
        assertEquals("Currency call without a bound server", NeroEconomyTelemetry.scrub("Currency call without a bound server"));
        assertEquals("hash deadbeef and 1234-5678", NeroEconomyTelemetry.scrub("hash deadbeef and 1234-5678"));
    }
}
