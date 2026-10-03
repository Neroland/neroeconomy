package za.co.neroland.neroeconomy.obligation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobTargetLabelsTest {
    @Test void theThreePlayerTokensMapToTheirPhrases() {
        assertEquals("gui.neroeconomy.target.player", JobTargets.playerLabelKey(JobTargets.PLAYER_HIDDEN));
        assertEquals("gui.neroeconomy.target.player_you", JobTargets.playerLabelKey(JobTargets.PLAYER_YOU));
        assertEquals("gui.neroeconomy.target.player_yours", JobTargets.playerLabelKey(JobTargets.PLAYER_YOURS));
    }

    @Test void ordinaryIdsAreShownAsTheyStand() {
        assertNull(JobTargets.playerLabelKey("minecraft:diamond"));
        assertNull(JobTargets.playerLabelKey("#minecraft:logs"));
        assertNull(JobTargets.playerLabelKey("minecraft:zombie"));
        assertNull(JobTargets.playerLabelKey(null));
    }

    @Test void aRawPlayerTargetStillGetsAPhraseAndNeverItsId() {
        // Not something a caller should pass, but if one ever does the id must not be displayed.
        assertEquals("gui.neroeconomy.target.player", JobTargets.playerLabelKey(JobTargets.player(UUID.randomUUID())));
        assertEquals("gui.neroeconomy.target.player", JobTargets.playerLabelKey("player:erased"));
    }

    @Test void whateverAViewerIsSentHasAPhrase() {
        UUID issuer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String stored = JobTargets.player(target);
        for (UUID viewer : new UUID[] { issuer, target, UUID.randomUUID(), null })
            assertNotNull(JobTargets.playerLabelKey(JobTargets.visibleTo(stored, issuer, viewer)));
    }

    @Test void statesAreShownThroughLangKeysNotEnumNames() {
        assertEquals("command.neroeconomy.job_state.open", JobState.OPEN.translationKey());
        assertEquals("command.neroeconomy.job_state.fulfilled", JobState.FULFILLED.translationKey());
    }
}
