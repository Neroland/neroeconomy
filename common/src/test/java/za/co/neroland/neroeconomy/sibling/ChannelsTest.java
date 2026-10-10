package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

/** Milestones, threshold watches and shock requests: the class-free channels' pure rules. */
class ChannelsTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Set<String> SOURCES = Set.of("neroevents");

    @Test void aMilestoneIsEarnedOnceAndWaitsForItsGate() {
        MilestoneSection section = new MilestoneSection();
        assertTrue(section.earn(P, MilestoneSection.Milestone.FIRST_BOND));
        assertFalse(section.earn(P, MilestoneSection.Milestone.FIRST_BOND), "once only");
        assertEquals(List.of(MilestoneSection.Milestone.FIRST_BOND), section.pending(P));
        section.opened(P, MilestoneSection.Milestone.FIRST_BOND);
        assertTrue(section.pending(P).isEmpty());
        assertTrue(section.earned(P, MilestoneSection.Milestone.FIRST_BOND));
        assertFalse(section.hasPending());
    }

    @Test void theHundredthSaleReachesTrader100() {
        MilestoneSection section = new MilestoneSection();
        for (int sale = 1; sale < MilestoneSection.TRADER_SALES; sale++) section.countSale(P);
        assertTrue(section.earned(P, MilestoneSection.Milestone.FIRST_SALE));
        assertFalse(section.earned(P, MilestoneSection.Milestone.TRADER_100));
        assertTrue(section.countSale(P));
        assertTrue(section.earned(P, MilestoneSection.Milestone.TRADER_100));
        section.countSale(P);
        assertEquals(MilestoneSection.TRADER_SALES, section.sales(P), "it stops counting");
    }

    @Test void milestonesAreErasedExportedAndSaved() {
        MilestoneSection section = new MilestoneSection();
        section.countSale(P);
        JsonObject export = section.exportFor(P);
        assertEquals("first_sale", export.getAsJsonArray("reached").get(0).getAsString());
        assertEquals(1L, export.get("settled_sales_counted").getAsLong());
        MilestoneSection restored = MilestoneSection.CODEC.parse(JsonOps.INSTANCE,
                MilestoneSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow()).getOrThrow();
        assertTrue(restored.earned(P, MilestoneSection.Milestone.FIRST_SALE));
        assertEquals(List.of(MilestoneSection.Milestone.FIRST_SALE), restored.pending(P));
        assertTrue(MilestoneSection.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().isEmpty());
        section.forgetPlayer(P);
        assertEquals(0, section.exportFor(P).size());
        assertTrue(section.isEmpty());
    }

    @Test void aWatchReportsOnlyAChangeAndNeverTheFirstLook() {
        ThresholdWatch watch = new ThresholdWatch();
        assertNull(watch.observe("world", true));
        assertNull(watch.observe("world", true));
        assertEquals(Boolean.FALSE, watch.observe("world", false));
        assertNull(watch.observe("world", false));
        assertEquals(Boolean.TRUE, watch.observe("world", true));
    }

    @Test void aShockRequestIsDecodedClampedAndCapped() {
        ShockRequests.Decision apply = ShockRequests.decode("neroevents", ShockRequests.PATH, "#c:ores", 1_500L, 90L,
                true, SOURCES, id -> true, 1_440L, 0, 64, false);
        assertEquals(ShockRequests.Kind.APPLY, apply.kind());
        assertEquals(1.5D, apply.multiplier());
        assertEquals(90L, apply.minutes());
        assertEquals(1_440L, ShockRequests.decode("neroevents", ShockRequests.PATH, "minecraft:wheat", 500L, 99_999L,
                true, SOURCES, id -> true, 1_440L, 0, 64, false).minutes(), "duration clamped");
        assertEquals(ShockRequests.Kind.CLEAR, ShockRequests.decode("neroevents", ShockRequests.PATH, "minecraft:wheat",
                0L, 0L, false, SOURCES, id -> true, 1_440L, 0, 64, false).kind());
        assertEquals(ShockRequests.Kind.IGNORE, ShockRequests.decode("othermod", ShockRequests.PATH, "minecraft:wheat",
                1_500L, 10L, true, SOURCES, id -> true, 1_440L, 0, 64, false).kind(), "unknown source");
        assertEquals(ShockRequests.Kind.IGNORE, ShockRequests.decode("neroevents", ShockRequests.PATH, "minecraft:wheat",
                1_500L, 10L, true, SOURCES, id -> false, 1_440L, 0, 64, false).kind(), "not loaded");
        assertEquals(ShockRequests.Kind.IGNORE, ShockRequests.decode("neroevents", ShockRequests.PATH, "Steve",
                1_500L, 10L, true, SOURCES, id -> true, 1_440L, 0, 64, false).kind(), "a scope that is no item or tag");
        assertEquals(ShockRequests.Kind.IGNORE, ShockRequests.decode("neroevents", ShockRequests.PATH, "minecraft:wheat",
                1_500L, 10L, true, SOURCES, id -> true, 1_440L, 64, 64, false).kind(), "at the cap");
        assertEquals(ShockRequests.Kind.APPLY, ShockRequests.decode("neroevents", ShockRequests.PATH, "minecraft:wheat",
                1_500L, 10L, true, SOURCES, id -> true, 1_440L, 64, 64, true).kind(), "replacing one is not a new one");
        assertEquals(ShockRequests.Kind.IGNORE, ShockRequests.decode("neroevents", "pollution", "minecraft:wheat",
                1_500L, 10L, true, SOURCES, id -> true, 1_440L, 0, 64, false).kind(), "another channel");
        assertEquals(Set.of("neroevents", "nerofactions"), ShockRequests.parseSources(" neroevents, nerofactions,Bad,neroeconomy"));
    }
}
