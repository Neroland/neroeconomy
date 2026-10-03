package za.co.neroland.neroeconomy.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;

/** Where the simulation has got to, and the world's secret: what is saved, and what never leaves. */
class SimulationSectionTest {
    private static SimulationSection roundTrip(SimulationSection section) {
        return SimulationSection.CODEC.parse(JsonOps.INSTANCE,
                SimulationSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow()).getOrThrow();
    }

    @Test void aNewSectionHasNeverRunAndHasNoSecretYet() {
        SimulationSection section = new SimulationSection();
        assertTrue(section.isEmpty());
        assertEquals(SimClock.NEVER, section.lastStep());
        assertEquals(0L, section.stepMillis());
        // Nothing to save, so an older store and a new world look the same on disk.
        assertEquals(new JsonObject(), SimulationSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow());
        assertTrue(SimulationSection.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().isEmpty());
    }

    @Test void theSecretIsMadeOnceIsThirtyTwoBytesAndSurvivesASave() {
        SimulationSection section = new SimulationSection();
        byte[] secret = SimTesting.secretOf(section);
        assertEquals(SimulationSection.SECRET_BYTES, secret.length);
        assertFalse(section.isEmpty());
        // The same secret every time it is asked for; changing the copy handed out changes nothing.
        secret[0] ^= 0x55;
        assertNotEquals(secret[0], SimTesting.secretOf(section)[0]);
        byte[] again = SimTesting.secretOf(section);
        assertArrayEquals(again, SimTesting.secretOf(roundTrip(section)));
        // Two worlds do not share one.
        assertFalse(HexFormat.of().formatHex(again).equals(
                HexFormat.of().formatHex(SimTesting.secretOf(new SimulationSection()))));
        // Not all zeros, which is what a forgotten initialisation would look like.
        boolean nonZero = false;
        for (byte value : again) nonZero |= value != 0;
        assertTrue(nonZero);
    }

    @Test void aDamagedSecretIsReplacedRatherThanUsed() {
        JsonObject saved = new JsonObject();
        saved.addProperty("last_step", 12L);
        saved.addProperty("secret", "not hex at all");
        SimulationSection section = SimulationSection.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(12L, section.lastStep());
        assertEquals(SimulationSection.SECRET_BYTES, SimTesting.secretOf(section).length);
        // Too short is not a secret either.
        saved.addProperty("secret", "00ff");
        section = SimulationSection.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        byte[] made = SimTesting.secretOf(section);
        assertEquals(SimulationSection.SECRET_BYTES, made.length);
        assertArrayEquals(made, SimTesting.secretOf(section));
    }

    @Test void theLastStepItsLengthAndHandlerStateSurviveASave() {
        SimulationSection section = new SimulationSection();
        section.useStepLength(600_000L);
        section.setLastStep(2_900_000L);
        assertTrue(section.setState("house.restock", 41L));
        assertTrue(section.setState("exchange.halted_until", -7L));
        SimulationSection restored = roundTrip(section);
        assertEquals(2_900_000L, restored.lastStep());
        assertEquals(600_000L, restored.stepMillis());
        assertEquals(41L, restored.state("house.restock", 0L));
        assertEquals(-7L, restored.state("exchange.halted_until", 0L));
        assertEquals(99L, restored.state("never.set", 99L));
        assertTrue(restored.clearState("house.restock"));
        assertFalse(restored.clearState("house.restock"));
        assertEquals(5L, restored.state("house.restock", 5L));
        // A stored index that cannot be a step is read as "never ran".
        JsonObject broken = new JsonObject();
        broken.addProperty("last_step", -40L);
        assertEquals(SimClock.NEVER, SimulationSection.CODEC.parse(JsonOps.INSTANCE, broken).getOrThrow().lastStep());
    }

    @Test void handlerStateIsBoundedAndRefusesUnusableKeys() {
        SimulationSection section = new SimulationSection();
        assertFalse(section.setState(null, 1L));
        assertFalse(section.setState(" ", 1L));
        assertFalse(section.setState("k".repeat(SimulationSection.MAX_STATE_KEY_LENGTH + 1), 1L));
        for (int index = 0; index < SimulationSection.MAX_STATE_ENTRIES; index++)
            assertTrue(section.setState("key." + index, index));
        assertFalse(section.setState("one.too.many", 1L));
        // A key that is already there can still be updated.
        assertTrue(section.setState("key.0", 77L));
        assertEquals(77L, section.state("key.0", 0L));
    }

    @Test void changingTheStepLengthRebasesTheStoredIndex() {
        SimulationSection section = new SimulationSection();
        section.useStepLength(600_000L);
        section.setLastStep(100L);
        section.useStepLength(60_000L);
        assertEquals(1_000L, section.lastStep());
        assertEquals(60_000L, section.stepMillis());
        // The same length again, or one that makes no sense, changes nothing.
        section.useStepLength(60_000L);
        section.useStepLength(0L);
        assertEquals(1_000L, section.lastStep());
        assertEquals(60_000L, section.stepMillis());
    }

    @Test void theSectionIsPartOfTheStoreAndMarksItDirty() {
        EconomyStore store = new EconomyStore();
        assertFalse(store.isDirty());
        store.simulation().setLastStep(5L);
        assertTrue(store.isDirty());
        assertFalse(store.simulation().isDirty());
        // Setting the same step again is not a change.
        store.setDirty(false);
        store.simulation().setLastStep(5L);
        assertFalse(store.isDirty());
        // The secret is created inside the store and is saved with it.
        byte[] secret = SimTesting.secretOf(store.simulation());
        assertTrue(store.isDirty());
        EconomyStore restored = StoreTesting.reload(store);
        assertEquals(5L, restored.simulation().lastStep());
        assertArrayEquals(secret, SimTesting.secretOf(restored.simulation()));
        assertTrue(restored.sectionsPresent().contains("simulation"));
    }

    @Test void theSecretIsOnlyInTheSimulationSectionOfTheSavedStore() {
        EconomyStore store = new EconomyStore();
        String secret = HexFormat.of().formatHex(SimTesting.secretOf(store.simulation()));
        JsonObject saved = StoreTesting.encode(store).getAsJsonObject();
        assertEquals(secret, saved.getAsJsonObject("simulation").get("secret").getAsString());
        saved.remove("simulation");
        assertFalse(saved.toString().contains(secret));
    }
}
