package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

/** A quest hook's listing-fee waiver: one at a time, spent once, erased, exported and saved. */
class PerkSectionTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void aWaiverIsHeldOnceAndSpentOnce() {
        PerkSection section = new PerkSection();
        assertFalse(section.hasFeeWaiver(P));
        assertTrue(section.grantFeeWaiver(P));
        assertFalse(section.grantFeeWaiver(P), "a second hook adds nothing: a flag, not a credit");
        assertTrue(section.hasFeeWaiver(P));
        assertTrue(section.useFeeWaiver(P));
        assertFalse(section.useFeeWaiver(P));
        assertFalse(section.hasFeeWaiver(P));
        assertTrue(section.isEmpty());
    }

    @Test void waiversAreErasedExportedAndSaved() {
        PerkSection section = new PerkSection();
        section.grantFeeWaiver(P);
        section.grantFeeWaiver(Q);
        assertTrue(section.exportFor(P).get("fee_waiver_pending").getAsBoolean());
        section.forgetPlayer(P);
        assertEquals(0, section.exportFor(P).size(), "nothing exported for a player without a row");
        assertEquals(1, section.size());
        PerkSection restored = PerkSection.CODEC.parse(JsonOps.INSTANCE,
                PerkSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow()).getOrThrow();
        assertTrue(restored.hasFeeWaiver(Q));
        assertFalse(restored.hasFeeWaiver(P));
        assertTrue(PerkSection.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().isEmpty());
    }

    @Test void theHookGatesAreThisModsRecordGates() {
        for (QuestHooks.Hook hook : QuestHooks.Hook.values()) {
            assertEquals("neroeconomy", hook.gate().getNamespace());
            assertTrue(hook.gate().getPath().startsWith("hook/"), hook.gate().toString());
            assertEquals(hook, QuestHooks.Hook.byGate(hook.gate()));
        }
    }
}
