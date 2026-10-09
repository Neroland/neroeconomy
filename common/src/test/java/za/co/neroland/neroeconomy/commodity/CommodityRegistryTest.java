package za.co.neroland.neroeconomy.commodity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.goods.Lot;

/** Looking commodities up: by item, by lot, by key, and with the guard's verdict applied. */
class CommodityRegistryTest {
    private static final CommodityDefinition DIAMOND =
            CommodityDefinition.of("minecraft:diamond", 400L, 0L, 8L, 1000, CommodityClass.RARE);
    private static final CommodityDefinition LOGS =
            CommodityDefinition.of("#minecraft:logs", 4L, 2048L, 512L, 1000, CommodityClass.FARMABLE);
    private static final CommodityDefinition DIRT =
            CommodityDefinition.of("minecraft:dirt", 1L, 64L, 64L, 1000, CommodityClass.STAPLE);

    private static CommodityRegistry registry() {
        return CommodityRegistry.of(List.of(LOGS, DIAMOND, DIRT), CommodityCatalog.of(Set.of(),
                Set.of("minecraft:diamond", "minecraft:dirt"),
                Map.of("minecraft:logs", List.of("minecraft:oak_log", "minecraft:birch_log")), Set.of()));
    }

    @Test void everyItemOfATagIsFoundByItsOwnId() {
        CommodityRegistry registry = registry();
        assertEquals(List.of("#minecraft:logs", "minecraft:diamond", "minecraft:dirt"),
                registry.commodities().stream().map(CommodityDefinition::key).toList());
        assertSame(DIAMOND, registry.forItem("minecraft:diamond"));
        assertSame(LOGS, registry.forItem("minecraft:oak_log"));
        assertSame(LOGS, registry.forItem("minecraft:birch_log"));
        assertNull(registry.forItem("minecraft:emerald"));
        assertNull(registry.forItem(null));
        // A tag is a key, never an item.
        assertNull(registry.forItem("#minecraft:logs"));
        assertSame(LOGS, registry.get("#minecraft:logs"));
        assertNull(registry.get("minecraft:oak_log"));
        assertNull(registry.get(null));
        assertEquals(List.of("minecraft:birch_log", "minecraft:oak_log"), registry.itemsOf("#minecraft:logs"));
        assertEquals(List.of("minecraft:diamond"), registry.itemsOf("minecraft:diamond"));
        assertEquals(List.of(), registry.itemsOf("minecraft:emerald"));
        assertThrows(UnsupportedOperationException.class, () -> registry.commodities().add(DIAMOND));
        assertThrows(UnsupportedOperationException.class, () -> registry.itemsOf("#minecraft:logs").add("x"));
    }

    @Test void onlyAnUnmodifiedLotIsACommodity() {
        CommodityRegistry registry = registry();
        assertSame(DIAMOND, registry.forLot(Lot.of("minecraft:diamond", 3)));
        // The same item with a stored prototype (a name, enchantments, contents) is not.
        assertNull(registry.forLot(new Lot("minecraft:diamond", 3, 7)));
        assertNull(registry.forLot(Lot.of("minecraft:emerald", 1)));
        assertNull(registry.forLot(null));
    }

    @Test void theHousesTradeOnlyTheSidesTheDataGives() {
        CommodityRegistry registry = registry();
        // No supply: bought, never sold.
        assertFalse(registry.npcSells(DIAMOND));
        assertTrue(registry.npcBuys(DIAMOND));
        assertTrue(registry.npcSells(LOGS));
        assertTrue(registry.npcBuys(LOGS));
        // A reference price of 1 has a bid of 0: sold, never bought, whatever its demand says.
        assertTrue(registry.npcSells(DIRT));
        assertFalse(registry.npcBuys(DIRT));
        assertFalse(registry.npcSells(null));
        assertFalse(registry.npcBuys(null));
    }

    @Test void theGuardSwitchesSidesOffWithoutChangingTheCommodities() {
        CommodityRegistry base = registry();
        CommodityRegistry guarded = base.guarded(Set.of("minecraft:diamond"), Set.of("#minecraft:logs"));
        assertEquals(base.commodities(), guarded.commodities());
        assertSame(LOGS, guarded.forItem("minecraft:oak_log"));
        assertFalse(guarded.npcBuys(DIAMOND));
        assertFalse(guarded.npcSells(LOGS));
        assertTrue(guarded.npcBuys(LOGS));
        assertEquals(Set.of("minecraft:diamond"), guarded.buyOff());
        assertEquals(Set.of("#minecraft:logs"), guarded.sellOff());
        // The registry it was made from is untouched.
        assertTrue(base.npcBuys(DIAMOND));
        assertTrue(base.npcSells(LOGS));
        assertTrue(base.buyOff().isEmpty());
        assertSame(base.report(), guarded.report());
    }

    @Test void nothingLoadedIsNotTheSameAsNothingReadable() {
        assertTrue(CommodityRegistry.EMPTY.available());
        assertTrue(CommodityRegistry.EMPTY.commodities().isEmpty());
        assertNull(CommodityRegistry.EMPTY.forItem("minecraft:diamond"));
        assertFalse(CommodityRegistry.UNAVAILABLE.available());
        assertTrue(CommodityRegistry.UNAVAILABLE.commodities().isEmpty());
        assertNull(CommodityRegistry.UNAVAILABLE.forItem("minecraft:diamond"));
        assertNull(CommodityRegistry.UNAVAILABLE.forLot(Lot.of("minecraft:diamond", 1)));
        assertEquals(List.of(), CommodityRegistry.UNAVAILABLE.itemsOf("minecraft:diamond"));
        assertNotNull(CommodityRegistry.UNAVAILABLE.report());
    }

    @Test void aLookupByItemIsOneMapReadHoweverManyCommoditiesThereAre() {
        java.util.List<CommodityDefinition> many = new java.util.ArrayList<>();
        java.util.Set<String> items = new java.util.HashSet<>();
        for (int index = 0; index < CommodityRegistry.MAX_COMMODITIES; index++) {
            String item = "pack:item_" + index;
            items.add(item);
            many.add(CommodityDefinition.of(item, 5L, 10L, 10L, 1000, CommodityClass.STAPLE));
        }
        CommodityRegistry registry = CommodityRegistry.of(many, CommodityCatalog.of(Set.of(), items, Map.of(), Set.of()));
        assertEquals(CommodityRegistry.MAX_COMMODITIES, registry.commodities().size());
        long started = System.nanoTime();
        int found = 0;
        for (int round = 0; round < 250; round++)
            for (int index = 0; index < CommodityRegistry.MAX_COMMODITIES; index++)
                if (registry.forItem("pack:item_" + index) != null) found++;
        long millis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(250 * CommodityRegistry.MAX_COMMODITIES, found);
        // A million lookups, string building included; a scan of the list would take minutes.
        assertTrue(millis < 5_000L, () -> "a million lookups took " + millis + " ms");
    }

    @Test void aCompatFileNamesItsMod() {
        assertEquals("nerotech", CommodityDefinition.compatMod("neroeconomy:compat/nerotech"));
        assertEquals("nerotech", CommodityDefinition.compatMod("pack:compat/nerotech/dusts"));
        assertNull(CommodityDefinition.compatMod("neroeconomy:building"));
        assertNull(CommodityDefinition.compatMod("neroeconomy:vanilla/compat/nerotech"));
        assertNull(CommodityDefinition.compatMod("neroeconomy:compat/"));
        assertNull(CommodityDefinition.compatMod(null));
        assertNull(DIAMOND.compatMod());
    }

    @Test void aDefinitionRefusesNumbersThatMakeNoSense() {
        assertThrows(IllegalArgumentException.class,
                () -> CommodityDefinition.of("minecraft:diamond", 0L, 0L, 0L, 1000, CommodityClass.RARE));
        assertThrows(IllegalArgumentException.class,
                () -> CommodityDefinition.of("Diamond", 4L, 0L, 0L, 1000, CommodityClass.RARE));
        assertThrows(IllegalArgumentException.class,
                () -> CommodityDefinition.of("minecraft:diamond", 4L, 0L, 0L, 100, CommodityClass.RARE));
        assertThrows(IllegalArgumentException.class,
                () -> CommodityDefinition.of("minecraft:diamond", 4L, -1L, 0L, 1000, CommodityClass.RARE));
        // One account may not be allowed more than the Houses trade in total.
        assertThrows(IllegalArgumentException.class, () -> new CommodityDefinition("minecraft:diamond", "test:file", 4L,
                8L, 8L, 9L, 1L, 0.5D, 0.1D, 1000, CommodityClass.RARE, "general", "", true, true));
    }
}
