package za.co.neroland.neroeconomy.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityParser;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.exchange.ExchangeTesting;
import za.co.neroland.neroeconomy.exchange.StockDefinition;
import za.co.neroland.neroeconomy.guard.GuardReport.Failure;
import za.co.neroland.neroeconomy.guard.GuardReport.Kind;

/**
 * The commodity data that ships, read from the very files: every entry parses, every item exists,
 * every name has wording — and the arbitrage guard finds no way to profit from the Merchant
 * Houses with the vanilla recipes, this mod's own and a sample of Nerotech's. A shipped file that
 * opens a profitable chain fails this test.
 */
class ShippedCommoditiesTest {
    private static final long CHIP_VALUE = 100L;

    private static String describe(GuardReport report) {
        List<String> lines = new ArrayList<>();
        for (Failure failure : report.failures()) lines.add(failure.describe());
        return String.join("; ", lines);
    }

    private static List<String> problems(CommodityRegistry registry) {
        List<String> out = new ArrayList<>();
        for (CommodityRegistry.Problem problem : registry.report().problems())
            out.add(problem.file() + '[' + problem.entry() + "] " + problem.word());
        return out;
    }

    /** The check with every sibling's recipes in the world. */
    private static GuardReport guard(CommodityRegistry registry, int depth) {
        return guard(registry, depth, GuardTesting.allRecipes());
    }

    /** The check in a world with no other mod: the vanilla recipes and this mod's own. */
    private static GuardReport vanillaGuard(CommodityRegistry registry, int depth) {
        Map<String, JsonElement> files = new LinkedHashMap<>(GuardTesting.vanillaRecipes());
        files.putAll(GuardTesting.ownRecipes());
        return guard(registry, depth, RecipeReader.readAll(files));
    }

    private static GuardReport guard(CommodityRegistry registry, int depth, RecipeReader.Read recipes) {
        return ArbitrageGuard.check(new ArbitrageGuard.Input(registry, ArbitrageGuard.Quotes.REFERENCE, recipes.recipes(),
                recipes.skipped(), GuardTesting.shippedBonuses(), GuardTesting.tags(),
                Map.of(GuardTesting.CREDIT_CHIP, CHIP_VALUE), depth));
    }

    /** Every entry of every shipped file, as written, before any world is consulted. */
    private static Map<String, List<CommodityDefinition>> entries() {
        Map<String, List<CommodityDefinition>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> file : GuardTesting.shippedCommodityFiles().entrySet()) {
            CommodityParser.ParsedFile parsed = CommodityParser.parseFile(file.getKey(), file.getValue(),
                    CommodityParser.DEFAULT_SPREAD_PERCENT);
            assertTrue(parsed.problems().isEmpty(), () -> file.getKey() + ": " + parsed.problems());
            out.put(file.getKey(), parsed.definitions());
        }
        return out;
    }

    // --- the files -------------------------------------------------------------------------------

    @Test void everyShippedFileIsOnTheListThisTestReads() throws IOException {
        Path directory = GuardTesting.sourceFile("common/src/main/resources/data/neroeconomy/neroeconomy/commodities");
        assertNotNull(directory, "the source tree was not found from " + System.getProperty("user.dir"));
        Set<String> found = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String path = directory.relativize(file).toString().replace('\\', '/');
                assertTrue(path.endsWith(".json"), path);
                found.add("neroeconomy:" + path.substring(0, path.length() - 5));
            }
        }
        assertEquals(new TreeSet<>(GuardTesting.SHIPPED_FILES), found);
    }

    @Test void everyShippedEntryParsesAndTheCountsAreWhatTheReportSays() {
        Map<String, Integer> counts = new TreeMap<>();
        entries().forEach((file, definitions) -> counts.put(file, definitions.size()));
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("neroeconomy:building", 13);
        expected.put("neroeconomy:metals_and_gems", 19);
        expected.put("neroeconomy:farming", 23);
        expected.put("neroeconomy:mob_drops", 15);
        expected.put("neroeconomy:c_ingots", 24);
        expected.put("neroeconomy:c_gems_and_dusts", 16);
        expected.put("neroeconomy:compat/nerotech", 12);
        expected.put("neroeconomy:compat/nerospace", 12);
        expected.put("neroeconomy:compat/neroagriculture", 11);
        expected.put("neroeconomy:compat/nerocreatures", 10);
        expected.put("neroeconomy:compat/nerocolonies", 5);
        expected.put("neroeconomy:compat/nerologistics", 7);
        expected.put("neroeconomy:compat/neropower", 7);
        expected.put("neroeconomy:compat/nerodecor", 8);
        expected.put("neroeconomy:compat/neronotes", 1);
        assertEquals(expected, counts);
    }

    @Test void aWorldWithNoOtherModLoadsTheVanillaFilesAndNothingElse() {
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.vanillaWorld());
        // No warning at all: every vanilla item and tag named exists, and what is optional is quiet.
        assertTrue(registry.report().problems().isEmpty(), () -> problems(registry).toString());
        assertTrue(registry.report().clashes().isEmpty(), () -> registry.report().clashes().toString());
        assertEquals(9, registry.report().gatedFiles());
        assertEquals(6, registry.report().files());
        // The four vanilla files load whole; the convention-tag files find nothing to price.
        assertEquals(13 + 19 + 23 + 15, registry.commodities().size());
        assertEquals(24 + 16, registry.report().absent());
        for (CommodityDefinition commodity : registry.commodities()) {
            assertNull(commodity.compatMod(), commodity.key());
            assertTrue(commodity.target().startsWith("minecraft:"), commodity.key());
            assertFalse(registry.itemsOf(commodity.key()).isEmpty(), commodity.key());
        }
        // A tag covers every item in it, at one price.
        assertEquals("#minecraft:logs", registry.forItem("minecraft:oak_log").key());
        assertEquals("#minecraft:logs", registry.forItem("minecraft:stripped_birch_wood").key());
        assertEquals("#minecraft:wool", registry.forItem("minecraft:red_wool").key());
        assertNull(registry.forItem("nerotech:nero_coil"));
    }

    @Test void withEverySiblingInstalledEveryCompatEntryFindsItsItem() {
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.fullWorld());
        assertTrue(registry.report().problems().isEmpty(), () -> problems(registry).toString());
        assertTrue(registry.report().clashes().isEmpty(), () -> registry.report().clashes().toString());
        assertEquals(0, registry.report().gatedFiles());
        assertEquals(GuardTesting.SHIPPED_FILES.size(), registry.report().files());
        entries().forEach((file, definitions) -> {
            if (CommodityDefinition.compatMod(file) == null) return;
            for (CommodityDefinition definition : definitions)
                assertNotNull(registry.get(definition.key()), () -> file + " names " + definition.key()
                        + ", which the frozen sibling world does not have");
        });
        // An explicit item keeps its own price inside a tag another file prices.
        assertEquals("minecraft:bread", registry.forItem("minecraft:bread").key());
        assertEquals("#nerocolonies:colony_food/staple", registry.forItem("minecraft:dried_kelp").key());
        // A third-party metal is priced through its convention tag.
        assertEquals("#c:ingots/tin", registry.forItem("thirdparty:tin_ingot").key());
    }

    @Test void nothingThatCanNeverBeACommodityIsOne() {
        Set<String> never = GuardTesting.neverCommodity();
        // The survey's list, by id.
        for (String id : List.of("neroagriculture:resource_fragment", "neroagriculture:resource_seed",
                "neroagriculture:charged_seed", "neroagriculture:engineered_food", "neroagriculture:alien_produce",
                "neropower:fuel_rod", "neronotes:custom_disk", "nerologistics:item_cell_1k", "nerologistics:item_cell_64k",
                "nerocreatures:apex_trophy", "minecraft:shulker_box", "minecraft:red_shulker_box", "minecraft:bundle",
                "minecraft:written_book", "minecraft:filled_map", "minecraft:potion", "minecraft:enchanted_book"))
            assertTrue(never.contains(id), id);
        // Every vanilla id in the tag is a real item, so the tag loads in a world with no other mod.
        for (String id : never)
            if (id.startsWith("minecraft:")) assertTrue(GuardTesting.vanillaItemKnown(id), id);
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.fullWorld());
        for (String id : never) assertNull(registry.forItem(id), id);
        assertNull(registry.forItem(GuardTesting.CREDIT_CHIP));
        // And the tag file marks every id of another mod as optional.
        for (JsonElement value : GuardTesting.read("common/src/main/resources/",
                "data/neroeconomy/tags/item/never_commodity.json").getAsJsonObject().getAsJsonArray("values")) {
            if (value.isJsonObject()) assertFalse(value.getAsJsonObject().get("required").getAsBoolean());
            else assertTrue(value.getAsString().startsWith("minecraft:"), value.getAsString());
        }
    }

    @Test void everyClassHouseAndSectorHasWording() {
        JsonObject lang = GuardTesting.lang();
        Set<String> sectors = new TreeSet<>();
        for (StockDefinition stock : ExchangeTesting.shipped().stocks()) sectors.add(stock.sector());
        List<String> missing = new ArrayList<>();
        for (CommodityClass value : CommodityClass.values())
            if (!lang.has(value.translationKey())) missing.add(value.translationKey());
        if (!lang.has("house.neroeconomy." + CommodityDefinition.DEFAULT_HOUSE)) missing.add("house.neroeconomy.general");
        entries().forEach((file, definitions) -> {
            for (CommodityDefinition definition : definitions) {
                if (!lang.has("house.neroeconomy." + definition.house())) missing.add("house.neroeconomy." + definition.house());
                assertFalse(definition.sector().isEmpty(), () -> definition.key() + " has no sector");
                // A commodity's sector is one a listed company is in, so its trade moves a share price.
                assertTrue(sectors.contains(definition.sector()), () -> definition.key() + ": " + definition.sector());
                if (!lang.has("sector.neroeconomy." + definition.sector())) missing.add("sector.neroeconomy." + definition.sector());
            }
        });
        for (String key : List.of("command.neroeconomy.reload_check.header", "command.neroeconomy.reload_check.stocks",
                "command.neroeconomy.reload_check.commodities", "command.neroeconomy.reload_check.commodity_problems",
                "command.neroeconomy.reload_check.clashes", "command.neroeconomy.reload_check.guard_problems",
                "command.neroeconomy.reload_check.file_problem", "command.neroeconomy.reload_check.entry_problem",
                "command.neroeconomy.reload_check.clash", "command.neroeconomy.reload_check.more",
                "command.neroeconomy.reload_check.guard", "command.neroeconomy.reload_check.failure.spread",
                "command.neroeconomy.reload_check.failure.chain", "command.neroeconomy.reload_check.failure.redeem",
                "command.neroeconomy.reload_check.passed", "command.neroeconomy.reload_check.failed",
                "command.neroeconomy.reload_check.unavailable", "command.neroeconomy.reload_check.no_recipes"))
            if (!lang.has(key)) missing.add(key);
        assertTrue(missing.isEmpty(), () -> "No English string for: " + new TreeSet<>(missing));
    }

    @Test void theHousesSellOnlyStaplesAndBuildingBlocks() {
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.fullWorld());
        for (CommodityDefinition commodity : registry.commodities()) {
            if (!commodity.npcSells()) continue;
            // Nothing rare is ever on sale, and nothing dear enough to be worth hoarding.
            assertFalse(commodity.commodityClass() == CommodityClass.RARE, commodity.key());
            assertTrue(commodity.referencePrice() <= 12L, () -> commodity.key() + " is sold at " + commodity.ask());
        }
        // The four Credit Chip ingredients are bought, never sold.
        for (String item : List.of("minecraft:copper_ingot", "minecraft:gold_ingot", "minecraft:redstone", "minecraft:iron_ingot")) {
            assertFalse(registry.forItem(item).npcSells(), item);
            assertTrue(registry.forItem(item).npcBuys(), item);
        }
        // What an automated source can turn out without limit is capped tightly per account.
        for (String item : List.of("minecraft:diamond", "minecraft:nether_star", "minecraft:echo_shard", "minecraft:netherite_scrap")) {
            CommodityDefinition rare = registry.forItem(item);
            assertEquals(CommodityClass.RARE, rare.commodityClass(), item);
            assertTrue(rare.npcDailyDemand() <= 8L, item);
            assertTrue(rare.perAccountDailySell() <= 2L, item);
        }
    }

    // --- the guard -------------------------------------------------------------------------------

    @Test void noShippedCommodityOpensAProfitableChain() {
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.fullWorld());
        GuardReport report = guard(registry, ArbitrageGuard.DEFAULT_DEPTH);
        assertTrue(report.passed(), () -> "The shipped commodity data lets a player profit from the Merchant Houses: "
                + describe(report));
        assertTrue(report.buyOff().isEmpty());
        assertTrue(report.sellOff().isEmpty());
        // The check really ran: the vanilla recipes were read and chains were compared.
        assertTrue(report.recipesRead() > 1300, () -> "only " + report.recipesRead() + " recipes were read");
        assertTrue(report.chainsChecked() >= 10, () -> "only " + report.chainsChecked() + " chains were checked");
        assertEquals(registry.commodities().size(), report.commodities());
        System.out.println("[guard] shipped data, every sibling installed: " + report.commodities() + " commodities, "
                + report.recipesRead() + " recipes read, " + report.recipesSkipped() + " skipped, "
                + report.chainsChecked() + " chains checked, depth " + report.depth());
    }

    @Test void noShippedCommodityOpensAProfitableChainInAVanillaWorldOrAtTheDeepestSetting() {
        GuardReport vanilla = vanillaGuard(GuardTesting.shipped(GuardTesting.vanillaWorld()), ArbitrageGuard.DEFAULT_DEPTH);
        assertTrue(vanilla.passed(), () -> describe(vanilla));
        // The vanilla commodities are just as safe when Nerotech's machines are in the world.
        GuardReport mixed = guard(GuardTesting.shipped(GuardTesting.vanillaWorld()), ArbitrageGuard.MAX_DEPTH);
        assertTrue(mixed.passed(), () -> describe(mixed));
        GuardReport deep = guard(GuardTesting.shipped(GuardTesting.fullWorld()), ArbitrageGuard.MAX_DEPTH);
        assertTrue(deep.passed(), () -> describe(deep));
        assertTrue(deep.chainsChecked() >= vanilla.chainsChecked());
        System.out.println("[guard] shipped data, vanilla world: " + vanilla.commodities() + " commodities, "
                + vanilla.chainsChecked() + " chains checked; every sibling at depth " + deep.depth() + ": "
                + deep.chainsChecked() + " chains checked");
    }

    @Test void theChipStaysSafeWhateverItIsSetToRedeemFor() {
        CommodityRegistry registry = GuardTesting.shipped(GuardTesting.fullWorld());
        RecipeReader.Read recipes = GuardTesting.allRecipes();
        // creditChipValue can be configured up to a thousand million; no House sells every ingredient.
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes.recipes(), recipes.skipped(),
                GuardTesting.shippedBonuses(), GuardTesting.tags(), Map.of(GuardTesting.CREDIT_CHIP, 1_000_000_000L),
                ArbitrageGuard.MAX_DEPTH));
        assertTrue(report.passed(), () -> describe(report));
    }

    @Test void theSameCheckFailsWhenAShippedPriceIsBroken() {
        // Planks bought at 20 each, with logs on sale at 5 for four of them: the test above would
        // fail if a shipped file said this.
        Map<String, JsonElement> files = new LinkedHashMap<>(GuardTesting.shippedCommodityFiles());
        JsonObject building = files.get("neroeconomy:building").deepCopy().getAsJsonObject();
        boolean changed = false;
        for (JsonElement entry : building.getAsJsonArray("commodities")) {
            JsonObject object = entry.getAsJsonObject();
            if (object.has("tag") && object.get("tag").getAsString().equals("minecraft:planks")) {
                object.addProperty("reference_price", 22);
                changed = true;
            }
        }
        assertTrue(changed);
        files.put("neroeconomy:building", building);
        CommodityCatalog world = GuardTesting.fullWorld();
        CommodityRegistry registry = CommodityParser.registry(files, CommodityParser.DEFAULT_SPREAD_PERCENT, world);
        GuardReport report = guard(registry, ArbitrageGuard.DEFAULT_DEPTH);
        assertFalse(report.passed());
        assertTrue(report.count(Kind.CHAIN) >= 1);
        assertEquals(Set.of("#minecraft:planks"), report.buyOff());
        assertTrue(report.failures().stream().allMatch(failure -> failure.commodity().equals("#minecraft:planks")),
                () -> describe(report));
    }

    @Test void theNerotechLoopsWouldBeCaughtIfTheHousesSoldTheirInputs() {
        // The shipped data is safe because no House sells raw ore, iron or paper. Put raw iron on
        // sale at its reference price and the ore multiplier is found at once: one raw iron
        // becomes three dust (chemical processing), each smelting into an ingot the Houses buy.
        Map<String, JsonElement> files = new LinkedHashMap<>(GuardTesting.shippedCommodityFiles());
        JsonObject metals = files.get("neroeconomy:metals_and_gems").deepCopy().getAsJsonObject();
        for (JsonElement entry : metals.getAsJsonArray("commodities")) {
            JsonObject object = entry.getAsJsonObject();
            if (object.has("item") && object.get("item").getAsString().equals("minecraft:raw_iron"))
                object.addProperty("npc_daily_supply", 64);
        }
        files.put("neroeconomy:metals_and_gems", metals);
        CommodityRegistry registry = CommodityParser.registry(files, CommodityParser.DEFAULT_SPREAD_PERCENT,
                GuardTesting.fullWorld());
        GuardReport report = guard(registry, ArbitrageGuard.DEFAULT_DEPTH);
        assertFalse(report.passed());
        assertTrue(report.buyOff().contains("#c:dusts/iron"), () -> describe(report));
        assertTrue(report.buyOff().contains("minecraft:iron_ingot"), () -> describe(report));
        // In a world without Nerotech one raw iron smelts into one ingot, bought for exactly what
        // the ore cost: breaking even is a failure too, but it is the only one.
        GuardReport vanilla = vanillaGuard(CommodityParser.registry(files, CommodityParser.DEFAULT_SPREAD_PERCENT,
                GuardTesting.vanillaWorld()), ArbitrageGuard.DEFAULT_DEPTH);
        assertEquals(Set.of("minecraft:iron_ingot"), vanilla.buyOff(), () -> describe(vanilla));
    }
}
