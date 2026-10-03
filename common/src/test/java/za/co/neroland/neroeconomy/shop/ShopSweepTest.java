package za.co.neroland.neroeconomy.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

class ShopSweepTest {
    @Test void aStoredKeySplitsIntoDimensionAndPackedPosition() {
        long packed = BlockPos.asLong(10, 64, -20);
        ShopSweep.Location location = ShopSweep.parse("minecraft:the_nether|" + packed);
        assertEquals("minecraft:the_nether", location.dimension());
        assertEquals(packed, location.packedPosition());
        assertEquals(new BlockPos(10, 64, -20), BlockPos.of(location.packedPosition()));
    }

    @Test void keysThisBuildDidNotWriteAreSkippedNotGuessed() {
        // "link" is the location of a listing posted through the companion app: not a shop position.
        assertNull(ShopSweep.parse("link"));
        assertNull(ShopSweep.parse(""));
        assertNull(ShopSweep.parse(null));
        assertNull(ShopSweep.parse("minecraft:overworld|"));
        assertNull(ShopSweep.parse("|12"));
        assertNull(ShopSweep.parse("minecraft:overworld|not-a-number"));
    }
}
