package za.co.neroland.neroeconomy.commodity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Clash;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Problem;

/** The commodity data files: what a good entry comes to, what is refused, and how a world resolves them. */
class CommodityParserTest {
    private static final double SPREAD = CommodityParser.DEFAULT_SPREAD_PERCENT;
    private static final String GOOD = """
            {
              "item": "minecraft:diamond",
              "reference_price": 400,
              "npc_daily_supply": 16,
              "npc_daily_demand": 8,
              "per_account_daily_buy": 4,
              "per_account_daily_sell": 2,
              "elasticity": 0.5,
              "recovery": 0.1,
              "spread_percent": 12.5,
              "class": "rare",
              "house": "smiths",
              "sector": "mining"
            }""";

    private static JsonObject good() { return JsonParser.parseString(GOOD).getAsJsonObject(); }

    private static JsonObject with(String key, String json) {
        JsonObject object = good();
        object.add(key, JsonParser.parseString(json));
        return object;
    }

    private static JsonObject without(String key) {
        JsonObject object = good();
        object.remove(key);
        return object;
    }

    private static String problem(JsonElement json) {
        CommodityParser.Parsed parsed = CommodityParser.parse("test:file", json, SPREAD);
        assertFalse(parsed.ok());
        assertNull(parsed.definition());
        return parsed.problem();
    }

    private static CommodityDefinition parsed(JsonElement json) {
        CommodityParser.Parsed parsed = CommodityParser.parse("test:file", json, SPREAD);
        assertTrue(parsed.ok(), () -> "refused: " + parsed.problem());
        return parsed.definition();
    }

    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    /** A world with these mods, these items (beyond what the tags hold), these tags and these barred items. */
    private static CommodityCatalog world(Set<String> mods, Set<String> items, Map<String, List<String>> tags,
            Set<String> never) {
        return CommodityCatalog.of(mods, items, tags, never);
    }

    private static List<String> words(CommodityRegistry registry) {
        List<String> out = new ArrayList<>();
        for (Problem problem : registry.report().problems())
            out.add(problem.file() + '[' + problem.entry() + "] " + problem.word());
        return out;
    }

    // --- one entry -------------------------------------------------------------------------------

    @Test void aGoodEntryBecomesACommodity() {
        CommodityDefinition definition = parsed(good());
        assertEquals("minecraft:diamond", definition.key());
        assertFalse(definition.tag());
        assertEquals("minecraft:diamond", definition.target());
        assertEquals("test:file", definition.file());
        assertEquals(400L, definition.referencePrice());
        assertEquals(16L, definition.npcDailySupply());
        assertEquals(8L, definition.npcDailyDemand());
        assertEquals(4L, definition.perAccountDailyBuy());
        assertEquals(2L, definition.perAccountDailySell());
        assertEquals(0.5D, definition.elasticity());
        assertEquals(0.1D, definition.recovery());
        assertEquals(1250, definition.spreadBps());
        assertEquals(CommodityClass.RARE, definition.commodityClass());
        assertEquals("smiths", definition.house());
        assertEquals("mining", definition.sector());
        assertTrue(definition.required());
        assertTrue(definition.enabled());
        // 400 plus and minus 12.5 %.
        assertEquals(450L, definition.ask());
        assertEquals(350L, definition.bid());
        assertTrue(definition.npcSells());
        assertTrue(definition.npcBuys());
        assertNull(definition.compatMod());
    }

    @Test void aTagEntryIsKeyedByItsTagWithAHash() {
        JsonObject object = without("item");
        object.addProperty("tag", "minecraft:logs");
        CommodityDefinition definition = parsed(object);
        assertEquals("#minecraft:logs", definition.key());
        assertTrue(definition.tag());
        assertEquals("minecraft:logs", definition.target());
    }

    @Test void everythingButTheTargetAndThePriceHasADefault() {
        CommodityDefinition staple = parsed(json("{\"item\": \"minecraft:stone\", \"reference_price\": 3}"));
        assertEquals(0L, staple.npcDailySupply());
        assertEquals(0L, staple.npcDailyDemand());
        assertEquals(0L, staple.perAccountDailyBuy());
        assertEquals(0L, staple.perAccountDailySell());
        assertEquals(CommodityClass.STAPLE, staple.commodityClass());
        assertEquals(CommodityClass.STAPLE.defaultElasticity(), staple.elasticity());
        assertEquals(CommodityClass.STAPLE.defaultRecovery(), staple.recovery());
        assertEquals(1000, staple.spreadBps());
        assertEquals(CommodityDefinition.DEFAULT_HOUSE, staple.house());
        assertEquals("", staple.sector());
        // No supply and no demand: the Houses neither sell nor buy it.
        assertFalse(staple.npcSells());
        assertFalse(staple.npcBuys());

        // The per-account caps default to a share of the day's total that depends on the class.
        CommodityDefinition farmable = parsed(json("{\"item\": \"minecraft:wheat\", \"reference_price\": 3, "
                + "\"npc_daily_supply\": 512, \"npc_daily_demand\": 64, \"class\": \"farmable\"}"));
        assertEquals(64L, farmable.perAccountDailyBuy());
        assertEquals(8L, farmable.perAccountDailySell());
        assertEquals(CommodityClass.FARMABLE.defaultElasticity(), farmable.elasticity());
        CommodityDefinition rare = parsed(json("{\"item\": \"minecraft:nether_star\", \"reference_price\": 3000, "
                + "\"npc_daily_demand\": 1, \"class\": \"rare\"}"));
        // A share of one unit is still one unit.
        assertEquals(1L, rare.perAccountDailySell());
        // A whole number written with a fraction of zero is still a whole number.
        assertEquals(45L, parsed(with("reference_price", "45.0")).referencePrice());
    }

    @Test void theDefaultSpreadComesFromTheCallerAndIsKeptInRange() {
        JsonObject plain = without("spread_percent");
        assertEquals(1500, CommodityParser.parse("test:file", plain, 15.0D).definition().spreadBps());
        // A configured default outside the range is clamped; it never refuses a file.
        assertEquals(CommodityDefinition.MIN_SPREAD_BPS, CommodityParser.parse("test:file", plain, 0.5D).definition().spreadBps());
        assertEquals(CommodityDefinition.MAX_SPREAD_BPS, CommodityParser.parse("test:file", plain, 99.0D).definition().spreadBps());
        assertEquals(CommodityDefinition.MIN_SPREAD_BPS, CommodityParser.parse("test:file", plain, Double.NaN).definition().spreadBps());
    }

    @Test void pricesRoundAwayFromTheReferenceSoTheAskIsAlwaysAboveTheBid() {
        for (long price : new long[] {1L, 2L, 3L, 7L, 10L, 99L, 100L, 12_345L, CommodityDefinition.MAX_REFERENCE_PRICE})
            for (int bps : new int[] {CommodityDefinition.MIN_SPREAD_BPS, 1000, 3333, CommodityDefinition.MAX_SPREAD_BPS}) {
                long ask = CommodityDefinition.askAt(price, bps);
                long bid = CommodityDefinition.bidAt(price, bps);
                assertTrue(ask > price, () -> "ask " + ask + " for " + price);
                assertTrue(bid < price, () -> "bid " + bid + " for " + price);
                assertTrue(bid >= 0L);
            }
        assertEquals(2L, CommodityDefinition.askAt(1L, 1000));
        assertEquals(0L, CommodityDefinition.bidAt(1L, 1000));
        assertEquals(3L, CommodityDefinition.askAt(2L, 1000));
        assertEquals(1L, CommodityDefinition.bidAt(2L, 1000));
        assertEquals(110L, CommodityDefinition.askAt(100L, 1000));
        assertEquals(90L, CommodityDefinition.bidAt(100L, 1000));
        // A price of 1 has a bid of 0: the Houses sell it and never buy it.
        CommodityDefinition dirt = parsed(json("{\"item\": \"minecraft:dirt\", \"reference_price\": 1, "
                + "\"npc_daily_supply\": 64, \"npc_daily_demand\": 64}"));
        assertTrue(dirt.npcSells());
        assertFalse(dirt.npcBuys());
    }

    @Test void badEntriesAreRefusedWithAFixedWord() {
        assertEquals("not_an_object", problem(json("[]")));
        assertEquals("not_an_object", problem(null));
        assertEquals("unknown_field", problem(with("colour", "\"red\"")));
        assertEquals("missing_item", problem(without("item")));
        assertEquals("item_and_tag", problem(with("tag", "\"minecraft:logs\"")));
        assertEquals("bad_item", problem(with("item", "\"Diamond\"")));
        assertEquals("bad_item", problem(with("item", "\"#minecraft:logs\"")));
        assertEquals("bad_item", problem(with("item", "7")));
        JsonObject tag = without("item");
        tag.addProperty("tag", "#minecraft:logs");
        assertEquals("bad_tag", problem(tag));
        assertEquals("missing_reference_price", problem(without("reference_price")));
        assertEquals("bad_reference_price", problem(with("reference_price", "0")));
        assertEquals("bad_reference_price", problem(with("reference_price", "2.5")));
        assertEquals("bad_reference_price", problem(with("reference_price", "1000001")));
        assertEquals("bad_reference_price", problem(with("reference_price", "\"40\"")));
        assertEquals("bad_npc_daily_supply", problem(with("npc_daily_supply", "-1")));
        assertEquals("bad_npc_daily_supply", problem(with("npc_daily_supply", "1000001")));
        assertEquals("bad_npc_daily_demand", problem(with("npc_daily_demand", "1.5")));
        // One account cannot be allowed more than the Houses trade in total.
        assertEquals("bad_per_account_daily_buy", problem(with("per_account_daily_buy", "17")));
        assertEquals("bad_per_account_daily_sell", problem(with("per_account_daily_sell", "9")));
        assertEquals("bad_per_account_daily_sell", problem(with("per_account_daily_sell", "-1")));
        assertEquals("bad_elasticity", problem(with("elasticity", "-0.1")));
        assertEquals("bad_elasticity", problem(with("elasticity", "4.5")));
        assertEquals("bad_recovery", problem(with("recovery", "0")));
        assertEquals("bad_recovery", problem(with("recovery", "1.5")));
        assertEquals("bad_spread_percent", problem(with("spread_percent", "1.9")));
        assertEquals("bad_spread_percent", problem(with("spread_percent", "80")));
        assertEquals("bad_spread_percent", problem(with("spread_percent", "\"wide\"")));
        assertEquals("bad_class", problem(with("class", "\"common\"")));
        assertEquals("bad_class", problem(with("class", "3")));
        assertEquals("bad_house", problem(with("house", "\"The Smiths\"")));
        assertEquals("bad_sector", problem(with("sector", "\"deep-mining\"")));
        assertEquals("bad_required", problem(with("required", "\"no\"")));
        assertEquals("bad_enabled", problem(with("enabled", "1")));
        assertEquals("bad_id", CommodityParser.parse("Not An Id", good(), SPREAD).problem());
    }

    // --- one file --------------------------------------------------------------------------------

    @Test void aFileHoldsOneEntryOrAList() {
        CommodityParser.ParsedFile single = CommodityParser.parseFile("test:single", good(), SPREAD);
        assertEquals(1, single.definitions().size());
        assertEquals(List.of(0), single.entries());
        assertTrue(single.problems().isEmpty());

        CommodityParser.ParsedFile list = CommodityParser.parseFile("test:list", json("""
                { "commodities": [
                    { "item": "minecraft:coal", "reference_price": 6 },
                    { "item": "minecraft:coal", "reference_price": "six" },
                    { "tag": "minecraft:logs", "reference_price": 4 },
                    7
                ] }"""), SPREAD);
        // The good entries load; each bad one is reported by its place in the list, and nothing else.
        assertEquals(2, list.definitions().size());
        assertEquals(List.of(0, 2), list.entries());
        assertEquals(List.of(new Problem("test:list", 1, "bad_reference_price"), new Problem("test:list", 3, "not_an_object")),
                list.problems());
        assertEquals("#minecraft:logs", list.definitions().get(1).key());
    }

    @Test void aFileThatIsNotAnEntryOrAListIsRefusedAsAWhole() {
        assertEquals(List.of(new Problem("test:file", -1, "not_an_object")),
                CommodityParser.parseFile("test:file", json("[1, 2]"), SPREAD).problems());
        assertEquals(List.of(new Problem("test:file", -1, "not_an_object")),
                CommodityParser.parseFile("test:file", null, SPREAD).problems());
        assertEquals(List.of(new Problem("test:file", -1, "bad_commodities")),
                CommodityParser.parseFile("test:file", json("{\"commodities\": {}}"), SPREAD).problems());
        assertEquals(List.of(new Problem("test:file", -1, "unknown_field")),
                CommodityParser.parseFile("test:file", json("{\"commodities\": [], \"note\": \"x\"}"), SPREAD).problems());
        assertEquals(List.of(new Problem("Bad Id", -1, "bad_id")),
                CommodityParser.parseFile("Bad Id", good(), SPREAD).problems());
        // An empty list is a file with nothing in it, not a bad file.
        CommodityParser.ParsedFile empty = CommodityParser.parseFile("test:file", json("{\"commodities\": []}"), SPREAD);
        assertTrue(empty.definitions().isEmpty());
        assertTrue(empty.problems().isEmpty());
    }

    // --- a world ---------------------------------------------------------------------------------

    @Test void badEntriesAreSkippedWithOneProblemEachAndTheGoodOnesStillLoad() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("pack:good", good());
        files.put("pack:broken", null);
        files.put("pack:typo", with("reference_price", "-4"));
        files.put("pack:missing", json("{\"item\": \"minecraft:unobtainium\", \"reference_price\": 4}"));
        files.put("pack:hollow", json("{\"tag\": \"minecraft:no_such_tag\", \"reference_price\": 4}"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD,
                world(Set.of(), Set.of("minecraft:diamond"), Map.of(), Set.of()));
        assertEquals(1, registry.commodities().size());
        assertNotNull(registry.forItem("minecraft:diamond"));
        assertEquals(List.of("pack:broken[-1] not_an_object", "pack:typo[0] bad_reference_price",
                "pack:missing[0] unknown_item", "pack:hollow[0] empty_tag"), words(registry));
        assertEquals(5, registry.report().files());
        assertTrue(registry.available());
    }

    @Test void aCompatFileIsReadOnlyWhenItsModIsLoaded() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("neroeconomy:compat/nerotech", json("""
                { "commodities": [
                    { "item": "nerotech:nero_coil", "reference_price": 3, "npc_daily_demand": 64 },
                    { "item": "nerotech:not_built_yet", "reference_price": 9, "npc_daily_demand": 64 },
                    { "tag": "c:dusts/iron", "reference_price": 9, "npc_daily_demand": 64 },
                    { "tag": "c:dusts/unobtainium", "reference_price": 9, "npc_daily_demand": 64 }
                ] }"""));
        // Not even parsed when the mod is absent: a file that is nonsense without it does no harm.
        files.put("neroeconomy:compat/absentmod", json("{\"nonsense\": true}"));
        files.put("neroeconomy:vanilla", json("{\"item\": \"minecraft:coal\", \"reference_price\": 6}"));
        Map<String, List<String>> tags = Map.of("c:dusts/iron", List.of("nerotech:iron_dust"));
        Set<String> items = Set.of("minecraft:coal", "nerotech:nero_coil");

        CommodityRegistry with = CommodityParser.registry(files, SPREAD, world(Set.of("nerotech"), items, tags, Set.of()));
        assertEquals(List.of("#c:dusts/iron", "minecraft:coal", "nerotech:nero_coil"),
                with.commodities().stream().map(CommodityDefinition::key).toList());
        assertEquals("nerotech", with.get("nerotech:nero_coil").compatMod());
        // The item and the tag this world does not have are skipped without a word.
        assertTrue(with.report().problems().isEmpty(), () -> words(with).toString());
        assertEquals(2, with.report().absent());
        assertEquals(1, with.report().gatedFiles());
        assertEquals(2, with.report().files());

        // Without the mod the file is left out whole, even though its items would resolve.
        CommodityRegistry without = CommodityParser.registry(files, SPREAD, world(Set.of(), items, tags, Set.of()));
        assertEquals(List.of("minecraft:coal"), without.commodities().stream().map(CommodityDefinition::key).toList());
        assertTrue(without.report().problems().isEmpty());
        assertEquals(2, without.report().gatedFiles());
        assertNull(without.forItem("nerotech:nero_coil"));
    }

    @Test void anEntryMaySayItsItemIsOptionalOutsideACompatFile() {
        Map<String, JsonElement> files = Map.of("pack:metals", json("""
                { "commodities": [
                    { "tag": "c:ingots/tin", "reference_price": 8, "required": false },
                    { "tag": "c:ingots/lead", "reference_price": 8, "required": false },
                    { "item": "othermod:gear", "reference_price": 8, "required": false },
                    { "tag": "c:ingots/zinc", "reference_price": 8 }
                ] }"""));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of(), Set.of(),
                Map.of("c:ingots/tin", List.of("othermod:tin_ingot")), Set.of()));
        assertEquals(List.of("#c:ingots/tin"), registry.commodities().stream().map(CommodityDefinition::key).toList());
        assertEquals(registry.get("#c:ingots/tin"), registry.forItem("othermod:tin_ingot"));
        // Only the entry that did not say "required": false is worth a warning.
        assertEquals(List.of("pack:metals[3] empty_tag"), words(registry));
        assertEquals(2, registry.report().absent());
    }

    @Test void whatMayNeverBeACommodityIsRefusedEvenInACompatFile() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("neroeconomy:compat/neroagriculture", json("""
                { "commodities": [
                    { "item": "neroagriculture:resource_seed", "reference_price": 50 },
                    { "item": "neroagriculture:biomass", "reference_price": 6 }
                ] }"""));
        files.put("pack:books", json("{\"item\": \"minecraft:enchanted_book\", \"reference_price\": 90}"));
        files.put("pack:containers", json("{\"tag\": \"pack:containers\", \"reference_price\": 20}"));
        Map<String, List<String>> tags = Map.of("pack:containers",
                List.of("minecraft:barrel", "minecraft:shulker_box", "minecraft:chest"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of("neroagriculture"),
                Set.of("neroagriculture:resource_seed", "neroagriculture:biomass", "minecraft:enchanted_book"), tags,
                Set.of("neroagriculture:resource_seed", "minecraft:enchanted_book", "minecraft:shulker_box")));
        assertNull(registry.forItem("neroagriculture:resource_seed"));
        assertNull(registry.forItem("minecraft:enchanted_book"));
        assertNotNull(registry.forItem("neroagriculture:biomass"));
        // The tag still covers its other members; the barred one is left out and reported once.
        assertEquals(List.of("minecraft:barrel", "minecraft:chest"), registry.itemsOf("#pack:containers"));
        assertNull(registry.forItem("minecraft:shulker_box"));
        assertEquals(List.of("neroeconomy:compat/neroagriculture[0] never_commodity", "pack:books[0] never_commodity",
                "pack:containers[0] members_excluded"), words(registry));
    }

    @Test void theCatalogueSaysWhyAnItemIsRefused() {
        CommodityCatalog catalog = new CommodityCatalog() {
            @Override public boolean modLoaded(String modId) { return false; }
            @Override public boolean itemKnown(String item) { return true; }
            @Override public java.util.Collection<String> tagItems(String tag) { return List.of(); }
            @Override public String refusal(String item) {
                if (item.equals("neroeconomy:credit_chip")) return CREDIT_CHIP;
                return item.equals("minecraft:bedrock") ? UNTRADEABLE : null;
            }
        };
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("pack:chip", json("{\"item\": \"neroeconomy:credit_chip\", \"reference_price\": 90}"));
        files.put("pack:rock", json("{\"item\": \"minecraft:bedrock\", \"reference_price\": 90}"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, catalog);
        assertTrue(registry.commodities().isEmpty());
        assertEquals(List.of("pack:chip[0] credit_chip", "pack:rock[0] untradeable"), words(registry));
    }

    @Test void anItemEntryWinsOverATagEntryWithoutItBeingAClash() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        // The tag file sorts first, and still loses the item.
        files.put("pack:a_tags", json("{\"tag\": \"c:ingots/iron\", \"reference_price\": 5, \"npc_daily_demand\": 8}"));
        files.put("pack:b_items", json("{\"item\": \"minecraft:iron_ingot\", \"reference_price\": 12, \"npc_daily_demand\": 8}"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of(), Set.of(),
                Map.of("c:ingots/iron", List.of("minecraft:iron_ingot", "othermod:iron_ingot")), Set.of()));
        assertEquals(12L, registry.forItem("minecraft:iron_ingot").referencePrice());
        assertEquals(5L, registry.forItem("othermod:iron_ingot").referencePrice());
        assertEquals(List.of("othermod:iron_ingot"), registry.itemsOf("#c:ingots/iron"));
        assertTrue(registry.report().clashes().isEmpty());
        assertTrue(registry.report().problems().isEmpty());
    }

    @Test void aTagWhoseItemsAllHaveAPriceOfTheirOwnCoversNothingAndIsDropped() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("pack:a_tags", json("{\"tag\": \"c:ingots/iron\", \"reference_price\": 5}"));
        files.put("pack:b_items", json("{\"item\": \"minecraft:iron_ingot\", \"reference_price\": 12}"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of(), Set.of(),
                Map.of("c:ingots/iron", List.of("minecraft:iron_ingot")), Set.of()));
        assertEquals(List.of("minecraft:iron_ingot"), registry.commodities().stream().map(CommodityDefinition::key).toList());
        assertNull(registry.get("#c:ingots/iron"));
        assertTrue(registry.report().problems().isEmpty());
        assertTrue(registry.report().clashes().isEmpty());
    }

    @Test void betweenTwoTagsTheFirstByFileIdWinsAndTheClashIsRecorded() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        // Handed over in the other order: the file id decides, not the order of listing.
        files.put("pack:z_wood", json("{\"tag\": \"pack:burnable\", \"reference_price\": 9}"));
        files.put("pack:a_wood", json("{\"tag\": \"minecraft:logs\", \"reference_price\": 4}"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of(), Set.of(), Map.of(
                "minecraft:logs", List.of("minecraft:oak_log", "minecraft:birch_log"),
                "pack:burnable", List.of("minecraft:oak_log", "minecraft:coal")), Set.of()));
        assertEquals("#minecraft:logs", registry.forItem("minecraft:oak_log").key());
        assertEquals("#pack:burnable", registry.forItem("minecraft:coal").key());
        // The loser keeps its other members.
        assertEquals(List.of("minecraft:coal"), registry.itemsOf("#pack:burnable"));
        assertEquals(List.of(new Clash("minecraft:oak_log", "#minecraft:logs", "pack:a_wood", "#pack:burnable", "pack:z_wood")),
                registry.report().clashes());
    }

    @Test void theSameItemOrTagTwiceIsAClashAndTheFirstByFileIdStays() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("pack:b", json("{\"item\": \"minecraft:coal\", \"reference_price\": 9}"));
        files.put("pack:a", json("""
                { "commodities": [
                    { "item": "minecraft:coal", "reference_price": 6 },
                    { "tag": "minecraft:logs", "reference_price": 4 },
                    { "tag": "minecraft:logs", "reference_price": 5 }
                ] }"""));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of(), Set.of("minecraft:coal"),
                Map.of("minecraft:logs", List.of("minecraft:oak_log")), Set.of()));
        assertEquals(6L, registry.forItem("minecraft:coal").referencePrice());
        assertEquals(4L, registry.forItem("minecraft:oak_log").referencePrice());
        assertEquals(List.of("pack:b[0] duplicate_item", "pack:a[2] duplicate_tag"), words(registry));
        assertEquals(List.of(new Clash("minecraft:coal", "minecraft:coal", "pack:a", "minecraft:coal", "pack:b"),
                new Clash("#minecraft:logs", "#minecraft:logs", "pack:a", "#minecraft:logs", "pack:a")),
                registry.report().clashes());
    }

    @Test void aDisabledEntryIsCountedAndClaimsNothing() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("pack:a", json("{\"item\": \"minecraft:coal\", \"reference_price\": 6, \"enabled\": false}"));
        files.put("pack:b", json("{\"item\": \"minecraft:coal\", \"reference_price\": 9}"));
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD,
                world(Set.of(), Set.of("minecraft:coal"), Map.of(), Set.of()));
        assertEquals(9L, registry.forItem("minecraft:coal").referencePrice());
        assertEquals(1, registry.report().disabled());
        assertTrue(registry.report().clashes().isEmpty());
        assertTrue(registry.report().problems().isEmpty());
    }

    @Test void commoditiesPastTheCapAreSkippedWithAProblem() {
        StringBuilder list = new StringBuilder("{\"commodities\": [");
        Set<String> items = new java.util.HashSet<>();
        Map<String, JsonElement> files = new LinkedHashMap<>();
        int perFile = CommodityParser.MAX_ENTRIES_PER_FILE;
        int total = CommodityRegistry.MAX_COMMODITIES + 3;
        for (int index = 0; index < total; index++) {
            String item = "pack:item_" + index;
            items.add(item);
            list.append("{\"item\": \"").append(item).append("\", \"reference_price\": 1}");
            if ((index + 1) % perFile == 0 || index == total - 1) {
                files.put("pack:file_" + (index / perFile), json(list.append("]}").toString()));
                list = new StringBuilder("{\"commodities\": [");
            } else list.append(',');
        }
        CommodityRegistry registry = CommodityParser.registry(files, SPREAD, world(Set.of(), items, Map.of(), Set.of()));
        assertEquals(CommodityRegistry.MAX_COMMODITIES, registry.commodities().size());
        assertEquals(3, registry.report().problems().size());
        assertEquals("too_many_commodities", registry.report().problems().get(0).word());
        // One entry too many in a single file refuses that file as a whole.
        StringBuilder long1 = new StringBuilder("{\"commodities\": [");
        for (int index = 0; index <= perFile; index++)
            long1.append(index == 0 ? "" : ",").append("{\"item\": \"pack:item_").append(index).append("\", \"reference_price\": 1}");
        assertEquals(List.of(new Problem("pack:long", -1, "too_many_entries")),
                CommodityParser.parseFile("pack:long", json(long1.append("]}").toString()), SPREAD).problems());
    }
}
