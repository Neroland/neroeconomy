package za.co.neroland.neroeconomy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class PricingEngineTest {
    private record Listing(UUID listingId, Identifier itemId, Set<Identifier> tags, long baseUnitPrice,
            int availableQuantity) implements MarketListingView { }

    @AfterEach void clear() { PriceModifierRegistry.clearForTest(); MarketShocks.clearForTest(); }

    @Test void modifiersAreDeterministicByModId() {
        PriceModifierRegistry.register("z_mod", context -> 2D);
        PriceModifierRegistry.register("a_mod", context -> 0.5D);
        assertEquals(100L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
    }

    @Test void shockIsCappedAndExecutionToleranceIsEnforced() {
        MarketShocks.applyShock("minecraft:diamond", 1000D, Duration.ofMinutes(1));
        assertEquals(400L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
        assertTrue(PricingEngine.executionPriceAccepted(100L, 105L));
        assertFalse(PricingEngine.executionPriceAccepted(100L, 106L));
    }

    private static Listing listing() {
        return new Listing(UUID.randomUUID(), Identifier.parse("minecraft:diamond"), Set.of(), 100L, 5);
    }
}
