package za.co.neroland.neroeconomy.obligation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobTargetsTest {
    private static final UUID ISSUER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BYSTANDER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test void realIdsAndTagsAreWellFormed() {
        assertTrue(JobTargets.wellFormed("minecraft:diamond"));
        assertTrue(JobTargets.wellFormed("#c:gems"));
        assertTrue(JobTargets.wellFormed(JobTargets.player(TARGET)));
    }

    @Test void blankAndOverLongTargetsAreRefused() {
        assertFalse(JobTargets.wellFormed(null));
        assertFalse(JobTargets.wellFormed(""));
        assertFalse(JobTargets.wellFormed("   "));
        assertTrue(JobTargets.wellFormed("a".repeat(JobTargets.MAX_LENGTH)));
        assertFalse(JobTargets.wellFormed("a".repeat(JobTargets.MAX_LENGTH + 1)));
    }

    @Test void aPlayerTargetFitsTheLengthCap() {
        assertTrue(JobTargets.player(TARGET).length() <= JobTargets.MAX_LENGTH);
    }

    @Test void targetKindsAreToldApart() {
        assertTrue(JobTargets.isTag("#c:gems"));
        assertFalse(JobTargets.isTag("minecraft:diamond"));
        assertTrue(JobTargets.isPlayer(JobTargets.player(TARGET)));
        assertTrue(JobTargets.isPlayer("player:erased"));
        assertFalse(JobTargets.isPlayer("minecraft:player"));
        assertFalse(JobTargets.isPlayer(null));
    }

    @Test void publicTargetsAreShownUnchangedToEveryone() {
        assertEquals("minecraft:diamond", JobTargets.visibleTo("minecraft:diamond", ISSUER, BYSTANDER));
        assertEquals("#c:gems", JobTargets.visibleTo("#c:gems", ISSUER, null));
        assertEquals("minecraft:zombie", JobTargets.visibleTo("minecraft:zombie", ISSUER, ISSUER));
    }

    @Test void aPlayerTargetNeverReachesAnyViewerAsStored() {
        String stored = JobTargets.player(TARGET);
        for (UUID viewer : new UUID[] {ISSUER, TARGET, BYSTANDER, null}) {
            String shown = JobTargets.visibleTo(stored, ISSUER, viewer);
            assertFalse(shown.contains(TARGET.toString()), "the target's id leaked to " + viewer);
        }
    }

    @Test void eachViewerOfAPlayerBountyGetsTheirOwnToken() {
        String stored = JobTargets.player(TARGET);
        assertEquals(JobTargets.PLAYER_YOURS, JobTargets.visibleTo(stored, ISSUER, ISSUER));
        assertEquals(JobTargets.PLAYER_YOU, JobTargets.visibleTo(stored, ISSUER, TARGET));
        assertEquals(JobTargets.PLAYER_HIDDEN, JobTargets.visibleTo(stored, ISSUER, BYSTANDER));
        // The console, and any other viewer with no identity, is a third party.
        assertEquals(JobTargets.PLAYER_HIDDEN, JobTargets.visibleTo(stored, ISSUER, null));
    }

    @Test void anErasedTargetNamesNobodyAndMatchesNobody() {
        assertEquals(JobTargets.PLAYER_HIDDEN, JobTargets.visibleTo("player:erased", ISSUER, TARGET));
        assertEquals(JobTargets.PLAYER_YOURS, JobTargets.visibleTo("player:erased", ISSUER, ISSUER));
    }
}
