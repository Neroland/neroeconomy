package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Ids as a player types them: a unique prefix of their own rows, or the whole id. */
class ShortIdsTest {
    private static final UUID FIRST = UUID.fromString("1234abcd-0000-4000-8000-000000000001");
    private static final UUID TWIN = UUID.fromString("1234abcd-0000-4000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("9999ffff-1111-4000-8000-000000000003");

    @Test void aShortIdIsEightCharactersUnlessThatIsNotEnoughToTellRowsApart() {
        assertEquals("9999ffff", ShortIds.of(OTHER, List.of(FIRST, TWIN, OTHER)));
        assertEquals("1234abcd", ShortIds.of(FIRST, List.of(FIRST, OTHER)));
        // Two ids that only differ in their last character are shown in full.
        assertEquals(FIRST.toString(), ShortIds.of(FIRST, List.of(FIRST, TWIN)));
        assertEquals(List.of(FIRST.toString(), TWIN.toString(), "9999ffff"), ShortIds.all(List.of(FIRST, TWIN, OTHER)));
        assertEquals("", ShortIds.of(null, List.of(FIRST)));
        assertEquals("1234abcd", ShortIds.of(FIRST, null));
    }

    @Test void aPrefixResolvesToTheOneRowItStarts() {
        List<UUID> own = List.of(FIRST, OTHER);
        assertEquals(FIRST, ShortIds.resolve("1234abcd", own).id());
        assertEquals(FIRST, ShortIds.resolve("1234", own).id());
        assertEquals(OTHER, ShortIds.resolve(" 9999FFFF ", own).id());
        // Every short id that is shown resolves back to its row.
        List<UUID> all = List.of(FIRST, TWIN, OTHER);
        for (UUID id : all) assertEquals(id, ShortIds.resolve(ShortIds.of(id, all), all).id());
    }

    @Test void anAmbiguousPrefixIsNotGuessedAt() {
        ShortIds.Match match = ShortIds.resolve("1234abcd", List.of(FIRST, TWIN, OTHER));
        assertNull(match.id());
        assertTrue(match.ambiguous());
        assertFalse(match.found());
    }

    @Test void nothingAndNonsenseMatchNothing() {
        List<UUID> own = List.of(FIRST, OTHER);
        assertFalse(ShortIds.resolve("", own).found());
        assertFalse(ShortIds.resolve(null, own).found());
        assertFalse(ShortIds.resolve("zzzz", own).found());
        assertFalse(ShortIds.resolve("zzzz", own).ambiguous());
        assertFalse(ShortIds.resolve("1234abcd", null).found());
        // Thirty-six characters that are not an id.
        assertFalse(ShortIds.resolve("not-an-id-not-an-id-not-an-id-not-an", own).found());
    }

    @Test void aWholeIdIsTakenAsItIsSoTheStoreDecidesWhoseItIs() {
        ShortIds.Match match = ShortIds.resolve(TWIN.toString(), List.of(FIRST, OTHER));
        assertEquals(TWIN, match.id());
        assertFalse(match.ambiguous());
    }
}
