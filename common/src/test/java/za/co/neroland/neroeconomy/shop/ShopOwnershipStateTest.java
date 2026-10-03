package za.co.neroland.neroeconomy.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

class ShopOwnershipStateTest {
    @Test void exportRowSplitsTheStoredKeyIntoDimensionAndCoordinates() {
        JsonObject row = ShopOwnershipState.describe("minecraft:the_nether|" + BlockPos.asLong(10, 64, -20));
        assertEquals("minecraft:the_nether", row.get("dimension").getAsString());
        assertEquals(10, row.get("x").getAsInt());
        assertEquals(64, row.get("y").getAsInt());
        assertEquals(-20, row.get("z").getAsInt());
    }

    @Test void aKeyThisBuildDidNotWriteStillExportsWhatItCan() {
        JsonObject malformed = ShopOwnershipState.describe("minecraft:overworld|not-a-number");
        assertEquals("minecraft:overworld", malformed.get("dimension").getAsString());
        assertFalse(malformed.has("x"));
        JsonObject bare = ShopOwnershipState.describe("no-separator");
        assertEquals("no-separator", bare.get("dimension").getAsString());
        assertFalse(bare.has("x"));
    }
}
