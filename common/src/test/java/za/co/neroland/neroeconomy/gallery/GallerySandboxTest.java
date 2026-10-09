package za.co.neroland.neroeconomy.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

/** The sandbox on its own: seeding, recording, clearing and saving, without the game. */
class GallerySandboxTest {
    @Test void seedingLaysOutTheSampleRowsOnceAndRebuildsReplaceThem() {
        GallerySandbox sandbox = new GallerySandbox();
        assertTrue(sandbox.isEmpty());
        assertFalse(sandbox.built());
        sandbox.seed(5_000L);
        sandbox.seed(6_000L);
        assertTrue(sandbox.built());
        assertEquals(6_000L, sandbox.builtAt());
        assertEquals(2, sandbox.rows(true, "").size());
        assertEquals(2, sandbox.rows(false, "").size());
        assertEquals(List.of("minecraft:diamond"), sandbox.rows(true, "DIAM").stream()
                .map(GallerySandbox.Row::itemId).toList());
        assertTrue(sandbox.rows(false, "diamond").isEmpty());
        // Ids are fixed per row and never collide.
        assertEquals(GallerySandbox.rowId(0), sandbox.rows(true, "").get(0).id());
        assertEquals(4, sandbox.rows(true, "").stream().map(GallerySandbox.Row::id).distinct().count()
                + sandbox.rows(false, "").stream().map(GallerySandbox.Row::id).distinct().count());
    }

    @Test void clearHandsBackExactlyWhatWasRecordedAndOnlyOnce() {
        GallerySandbox sandbox = new GallerySandbox();
        sandbox.seed(1L);
        assertTrue(sandbox.recordBlock(10L, "minecraft:smooth_quartz"));
        assertTrue(sandbox.recordBlock(11L, "neroeconomy:bank_terminal"));
        // Placing again at the same spot keeps the last block placed there.
        assertTrue(sandbox.recordBlock(10L, "minecraft:polished_deepslate"));
        assertFalse(sandbox.recordBlock(12L, ""));
        UUID stand = new UUID(1L, 2L);
        assertTrue(sandbox.recordLabel(stand, GallerySandbox.ROLE_TITLE));
        assertFalse(sandbox.recordLabel(null, GallerySandbox.ROLE_TITLE));

        GallerySandbox.Cleared cleared = sandbox.clear();
        assertEquals(4, cleared.rows());
        assertEquals(Map.of(10L, "minecraft:polished_deepslate", 11L, "neroeconomy:bank_terminal"), cleared.blocks());
        assertEquals(List.of(new GallerySandbox.Label(stand, GallerySandbox.ROLE_TITLE)), cleared.labels());
        assertTrue(sandbox.isEmpty());
        assertEquals(0L, sandbox.builtAt());
        GallerySandbox.Cleared again = sandbox.clear();
        assertEquals(0, again.rows());
        assertTrue(again.blocks().isEmpty());
        assertTrue(again.labels().isEmpty());
    }

    @Test void theRecordsAreBounded() {
        GallerySandbox sandbox = new GallerySandbox();
        for (int index = 0; index < GallerySandbox.MAX_BLOCKS; index++)
            assertTrue(sandbox.recordBlock(index, "minecraft:stone"));
        assertFalse(sandbox.recordBlock(-1L, "minecraft:stone"));
        // A spot already recorded can still be updated.
        assertTrue(sandbox.recordBlock(0L, "minecraft:dirt"));
        for (int index = 0; index < GallerySandbox.MAX_LABELS; index++)
            assertTrue(sandbox.recordLabel(new UUID(0L, index), GallerySandbox.ROLE_TITLE));
        assertFalse(sandbox.recordLabel(new UUID(1L, 1L), GallerySandbox.ROLE_TITLE));
    }

    @Test void itSurvivesASaveAndAnOldOrDamagedFileLoadsSafely() {
        GallerySandbox sandbox = new GallerySandbox();
        sandbox.seed(42L);
        sandbox.recordBlock(-123_456_789L, "minecraft:chest");
        sandbox.recordLabel(new UUID(3L, 4L), GallerySandbox.ROLE_TICKER + 2);
        JsonElement saved = GallerySandbox.CODEC.encodeStart(JsonOps.INSTANCE, sandbox).getOrThrow();
        GallerySandbox read = GallerySandbox.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertTrue(read.built());
        assertEquals(42L, read.builtAt());
        assertEquals(sandbox.rows(true, ""), read.rows(true, ""));
        assertEquals(sandbox.rows(false, ""), read.rows(false, ""));
        assertEquals(sandbox.blocks(), read.blocks());
        assertEquals(sandbox.labels(), read.labels());
        // Nothing about a player is saved: rows, block ids, entity ids of stands and roles only.
        assertFalse(saved.toString().contains("player"));

        GallerySandbox blank = GallerySandbox.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertTrue(blank.isEmpty());

        JsonObject damaged = saved.getAsJsonObject().deepCopy();
        damaged.getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("id", "not-a-uuid");
        damaged.getAsJsonArray("labels").get(0).getAsJsonObject().addProperty("entity", "nope");
        GallerySandbox repaired = GallerySandbox.CODEC.parse(JsonOps.INSTANCE, damaged).getOrThrow();
        assertEquals(3, repaired.rows(true, "").size() + repaired.rows(false, "").size());
        assertTrue(repaired.labels().isEmpty());
    }

    @Test void tickerRolesNameTheirTicker() {
        assertEquals(2, GallerySandbox.tickerIndex(GallerySandbox.ROLE_TICKER + 2));
        assertEquals(-1, GallerySandbox.tickerIndex(GallerySandbox.ROLE_LOOP));
        assertEquals(-1, GallerySandbox.tickerIndex(GallerySandbox.ROLE_TICKER + "x"));
        assertEquals(-1, GallerySandbox.tickerIndex(null));
    }
}
