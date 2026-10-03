package za.co.neroland.neroeconomy.trader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/** The Auto-Trader owner index: one row per block, bounded, saved with the store, erasable and exportable. */
class AutoTraderSectionTest {
    private static final UUID ALICE = new UUID(7L, 1L);
    private static final UUID BOB = new UUID(7L, 2L);
    /** A location as the game writes it: x 10, y 64, z -3 in the overworld. */
    private static final String HERE = "minecraft:overworld|" + pack(10, 64, -3);
    private static final String THERE = "minecraft:the_nether|" + pack(-200, 5, 7);
    private final TradeWorld world = new TradeWorld();

    @AfterEach void done() { world.close(); }

    /** The game's own packing of a block position: x in the top 26 bits, z in the next 26, y in the low 12. */
    private static long pack(int x, int y, int z) {
        return (x & 0x3FFFFFFL) << 38 | (z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    private AutoTraderSection section() { return world.store.autoTraders(); }

    @Test void thePlacerOwnsTheBlockUntilItIsRemoved() {
        assertTrue(section().isEmpty());
        assertNull(section().owner(HERE));
        assertTrue(section().claim(HERE, ALICE));
        assertEquals(ALICE, section().owner(HERE));
        assertEquals(1, section().count(ALICE));
        assertEquals(List.of(HERE), section().locationsOf(ALICE));
        // Claiming it again changes nothing; a new block at the same position is its new placer's.
        assertTrue(section().claim(HERE, ALICE));
        assertTrue(section().claim(HERE, BOB));
        assertEquals(BOB, section().owner(HERE));
        assertEquals(0, section().count(ALICE));
        assertTrue(section().remove(HERE));
        assertFalse(section().remove(HERE));
        assertFalse(section().remove(null));
        assertNull(section().owner(HERE));
        assertNull(section().owner(null));
        assertTrue(section().isEmpty());
        assertFalse(section().claim(null, ALICE));
        assertFalse(section().claim(" ", ALICE));
        assertFalse(section().claim(HERE, null));
    }

    @Test void onePlayerOwnsAtMostSixtyFour() {
        for (int index = 0; index < AutoTraderSection.MAX_PER_OWNER; index++)
            assertTrue(section().claim("minecraft:overworld|" + index, ALICE));
        assertFalse(section().claim("minecraft:overworld|999", ALICE));
        assertNull(section().owner("minecraft:overworld|999"));
        // Somebody else is not held back by it, and neither is a block she already owns.
        assertTrue(section().claim("minecraft:overworld|999", BOB));
        assertTrue(section().claim("minecraft:overworld|3", ALICE));
        assertTrue(section().remove("minecraft:overworld|3"));
        assertTrue(section().claim("minecraft:overworld|1000", ALICE));
        assertEquals(AutoTraderSection.MAX_PER_OWNER + 1, section().size());
    }

    @Test void theIndexIsSavedWithTheStoreAndAnOlderStoreLoadsWithNone() {
        assertFalse(StoreTesting.encode(world.store).getAsJsonObject().has("auto_traders"));
        world.reload();
        assertTrue(section().isEmpty());
        assertTrue(section().claim(HERE, ALICE));
        assertTrue(section().claim(THERE, BOB));
        assertTrue(world.store.sectionsPresent().contains("auto_traders"));

        world.reload();

        assertEquals(ALICE, section().owner(HERE));
        assertEquals(BOB, section().owner(THERE));
        assertEquals(List.of(HERE, THERE), section().locations());
    }

    @Test void erasureLeavesTheBlocksOwnerlessAndExportListsWhereTheyStand() {
        assertTrue(section().claim(HERE, ALICE));
        assertTrue(section().claim(THERE, ALICE));
        assertTrue(section().claim("minecraft:the_end|" + pack(1, 2, 3), BOB));

        JsonArray exported = section().exportFor(ALICE);
        assertEquals(2, exported.size());
        JsonObject first = exported.get(0).getAsJsonObject();
        assertEquals("minecraft:overworld", first.get("dimension").getAsString());
        assertEquals(10, first.get("x").getAsInt());
        assertEquals(64, first.get("y").getAsInt());
        assertEquals(-3, first.get("z").getAsInt());
        JsonObject second = exported.get(1).getAsJsonObject();
        assertEquals("minecraft:the_nether", second.get("dimension").getAsString());
        assertEquals(-200, second.get("x").getAsInt());
        assertEquals(5, second.get("y").getAsInt());
        assertEquals(7, second.get("z").getAsInt());
        // An export names nobody: it is the subject's own positions.
        assertFalse(exported.toString().contains(ALICE.toString()));

        assertEquals(List.of(HERE, THERE), section().forgetPlayer(ALICE));
        assertNull(section().owner(HERE));
        assertNull(section().owner(THERE));
        assertEquals(0, section().exportFor(ALICE).size());
        assertEquals(1, section().size());
        assertTrue(section().forgetPlayer(ALICE).isEmpty());
        assertTrue(section().forgetPlayer(null).isEmpty());
        // Nothing of the erased player is left in the saved store.
        assertFalse(StoreTesting.encode(world.store).toString().contains(ALICE.toString()));
    }

    @Test void aKeyThisBuildDidNotWriteStillExportsItsDimension() {
        assertEquals("somewhere", AutoTraderSection.describe("somewhere").get("dimension").getAsString());
        JsonObject odd = AutoTraderSection.describe("minecraft:overworld|not-a-number");
        assertEquals("minecraft:overworld", odd.get("dimension").getAsString());
        assertFalse(odd.has("x"));
        // Negative heights come back as they went in.
        assertEquals(-64, AutoTraderSection.describe("d|" + pack(0, -64, 0)).get("y").getAsInt());
    }
}
