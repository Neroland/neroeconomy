package za.co.neroland.neroeconomy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class PricingEngineTest {
    private record Listing(UUID listingId, Identifier itemId, Set<Identifier> tags, long baseUnitPrice,
            int availableQuantity) implements MarketListingView { }

    @AfterEach void clear() { PriceModifierRegistry.clearForTest(); MarketShocks.clear(); }

    @Test void modifiersAreAppliedInModIdOrder() {
        // Multiplication commutes, so the ordering guarantee is only observable in the call trace.
        List<String> trace = new ArrayList<>();
        PriceModifierRegistry.register("z_mod", context -> { trace.add("z_mod"); return 2D; });
        PriceModifierRegistry.register("a_mod", context -> { trace.add("a_mod"); return 0.5D; });
        PriceModifierRegistry.register("m_mod", context -> { trace.add("m_mod"); return 1.5D; });
        assertEquals(150L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
        assertEquals(List.of("a_mod", "m_mod", "z_mod"), trace);
    }

    @Test void failingModifiersAreSkippedWithoutBreakingTheOrder() {
        List<String> trace = new ArrayList<>();
        PriceModifierRegistry.register("b_mod", context -> { trace.add("b_mod"); throw new IllegalStateException(); });
        PriceModifierRegistry.register("a_mod", context -> { trace.add("a_mod"); return 2D; });
        PriceModifierRegistry.register("c_mod", context -> { trace.add("c_mod"); return 1.5D; });
        assertEquals(300L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
        assertEquals(List.of("a_mod", "b_mod", "c_mod"), trace);
    }

    @Test void finalMultiplierIsClampedAboveAndBelowAfterModifiers() {
        PriceModifierRegistry.register("a_mod", context -> 10D);
        assertEquals(400L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
        PriceModifierRegistry.clearForTest();
        PriceModifierRegistry.register("a_mod", context -> 0.01D);
        assertEquals(25L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
    }

    @Test void modifiersCannotEscapeTheBandByStackingWithAShock() {
        MarketShocks.applyShock("minecraft:diamond", 4D, Duration.ofMinutes(1));
        PriceModifierRegistry.register("a_mod", context -> 4D);
        assertEquals(400L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
    }

    @Test void shockIsCappedAndExecutionToleranceIsEnforced() {
        MarketShocks.applyShock("minecraft:diamond", 1000D, Duration.ofMinutes(1));
        assertEquals(400L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
        assertTrue(PricingEngine.executionPriceAccepted(100L, 105L));
        assertFalse(PricingEngine.executionPriceAccepted(100L, 106L));
    }

    @Test void tagShocksMatchTheListingTags() {
        MarketShocks.applyShock("#c:gems", 2D, Duration.ofMinutes(1));
        assertEquals(200L, PricingEngine.quote(tagged("c:gems"), UUID.randomUUID()).unitPrice());
        assertEquals(100L, PricingEngine.quote(tagged("c:ores"), UUID.randomUUID()).unitPrice());
    }

    @Test void expiredShocksStopApplying() {
        MarketShocks.applyShock("minecraft:diamond", 2D, Duration.ofMillis(1));
        try { Thread.sleep(5L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        assertEquals(100L, PricingEngine.quote(listing(), UUID.randomUUID()).unitPrice());
    }

    private static Listing listing() {
        return new Listing(UUID.randomUUID(), Identifier.parse("minecraft:diamond"), Set.of(), 100L, 5);
    }

    private static Listing tagged(String tag) {
        return new Listing(UUID.randomUUID(), Identifier.parse("minecraft:diamond"),
                Set.of(Identifier.parse(tag)), 100L, 5);
    }
}
