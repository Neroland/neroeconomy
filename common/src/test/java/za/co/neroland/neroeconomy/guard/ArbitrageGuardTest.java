package za.co.neroland.neroeconomy.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityCatalog;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.guard.GuardReport.Failure;
import za.co.neroland.neroeconomy.guard.GuardReport.Kind;

/**
 * The arbitrage guard against data that is broken on purpose: every way goods bought from the
 * Merchant Houses could be turned into a profit must be found, and safe data must pass.
 */
class ArbitrageGuardTest {
    /** 10 %: a reference price of 10 is sold at 11 and bought at 9. */
    private static final int SPREAD = 1000;

    /** Sold and bought by the Houses. */
    private static CommodityDefinition both(String key, long price) {
        return CommodityDefinition.of(key, price, 1000L, 1000L, SPREAD, CommodityClass.STAPLE);
    }

    /** Sold by the Houses, never bought. */
    private static CommodityDefinition sold(String key, long price) {
        return CommodityDefinition.of(key, price, 1000L, 0L, SPREAD, CommodityClass.STAPLE);
    }

    /** Bought by the Houses, never sold. */
    private static CommodityDefinition bought(String key, long price) {
        return CommodityDefinition.of(key, price, 0L, 1000L, SPREAD, CommodityClass.STAPLE);
    }

    private static CommodityRegistry registry(Map<String, List<String>> tags, CommodityDefinition... definitions) {
        Set<String> items = new HashSet<>();
        for (CommodityDefinition definition : definitions) if (!definition.tag()) items.add(definition.key());
        return CommodityRegistry.of(List.of(definitions), CommodityCatalog.of(Set.of(), items, tags, Set.of()));
    }

    private static CommodityRegistry registry(CommodityDefinition... definitions) {
        return registry(Map.of(), definitions);
    }

    private static List<GuardRecipe> recipes(String... json) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (int index = 0; index < json.length; index++) files.put("test:recipe_" + index, JsonParser.parseString(json[index]));
        RecipeReader.Read read = RecipeReader.readAll(files);
        assertEquals(json.length, read.recipes().size(), "a test recipe could not be read");
        return read.recipes();
    }

    private static String smelt(String from, String to) {
        return "{\"type\": \"minecraft:smelting\", \"ingredient\": \"" + from + "\", \"result\": {\"id\": \"" + to + "\"}}";
    }

    private static String craft(String from, int uses, String to, int gives) {
        StringBuilder ingredients = new StringBuilder();
        for (int index = 0; index < uses; index++) ingredients.append(index == 0 ? "" : ", ").append('"').append(from).append('"');
        return "{\"type\": \"minecraft:crafting_shapeless\", \"ingredients\": [" + ingredients + "], \"result\": {\"id\": \""
                + to + "\", \"count\": " + gives + "}}";
    }

    private static GuardReport check(CommodityRegistry registry, List<GuardRecipe> recipes) {
        return ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null, null, null,
                ArbitrageGuard.DEFAULT_DEPTH));
    }

    private static Failure only(GuardReport report) {
        assertEquals(1, report.failures().size(), () -> describe(report));
        return report.failures().get(0);
    }

    private static String describe(GuardReport report) {
        List<String> lines = new ArrayList<>();
        for (Failure failure : report.failures()) lines.add(failure.describe());
        return String.join("; ", lines);
    }

    // --- safe data -------------------------------------------------------------------------------

    @Test void aRecipeThatLosesMoneyPasses() {
        // Cobblestone is sold at 3; the stone it smelts into is bought at 2.
        CommodityRegistry registry = registry(both("minecraft:cobblestone", 2L), both("minecraft:stone", 3L));
        GuardReport report = check(registry, recipes(smelt("minecraft:cobblestone", "minecraft:stone")));
        assertTrue(report.passed(), () -> describe(report));
        assertTrue(report.buyOff().isEmpty());
        assertTrue(report.sellOff().isEmpty());
        assertEquals(2, report.commodities());
        assertEquals(1, report.recipesRead());
        assertEquals(1, report.chainsChecked());
        assertEquals(ArbitrageGuard.DEFAULT_DEPTH, report.depth());
    }

    @Test void aRecipeWithAnIngredientTheHousesDoNotSellIsNotAChain() {
        // Iron is bought, never sold: nobody can get the ingredient from a House.
        CommodityRegistry registry = registry(bought("minecraft:raw_iron", 9L), bought("minecraft:iron_ingot", 50L),
                sold("minecraft:coal", 6L));
        GuardReport report = check(registry, recipes(smelt("minecraft:raw_iron", "minecraft:iron_ingot"),
                "{\"type\": \"mod:alloying\", \"ingredients\": [\"minecraft:coal\", \"minecraft:raw_iron\"], "
                        + "\"result\": {\"id\": \"minecraft:iron_ingot\", \"count\": 4}}"));
        assertTrue(report.passed(), () -> describe(report));
        assertEquals(0, report.chainsChecked());
    }

    // --- broken on purpose -----------------------------------------------------------------------

    @Test void aProfitableSmeltFails() {
        // Sand is sold at 5; glass is bought at 7.
        CommodityRegistry registry = registry(both("minecraft:sand", 4L), both("minecraft:glass", 8L));
        GuardReport report = check(registry, recipes(smelt("minecraft:sand", "minecraft:glass")));
        assertFalse(report.passed());
        Failure failure = only(report);
        assertEquals(Kind.CHAIN, failure.kind());
        assertEquals("minecraft:glass", failure.commodity());
        assertEquals("test:recipe_0", failure.recipe());
        assertEquals("5.00", failure.cost());
        assertEquals("7.00", failure.revenue());
        assertEquals(1, failure.steps());
        assertEquals(Set.of("minecraft:glass"), report.buyOff());
        assertTrue(report.sellOff().isEmpty());
        assertEquals("minecraft:glass: recipe test:recipe_0 turns goods bought for 5.00 into goods sold for 7.00 (1 step)",
                failure.describe());
    }

    @Test void breakingEvenIsAFailureToo() {
        // One ore at 6 gives two dust bought at 3 each: nothing gained, but nothing lost either,
        // and the revenue has to be strictly below the cost.
        CommodityRegistry registry = registry(sold("mod:ore", 5L), bought("mod:dust", 4L));
        assertEquals(6L, registry.get("mod:ore").ask());
        assertEquals(3L, registry.get("mod:dust").bid());
        GuardReport report = check(registry, recipes(craft("mod:ore", 1, "mod:dust", 2)));
        assertEquals(Kind.CHAIN, only(report).kind());
        assertEquals(Set.of("mod:dust"), report.buyOff());
    }

    @Test void costIsCarriedPerUnitThroughRecipesThatGiveSeveral() {
        // One log at 5 gives four planks. Bought at 1 each that is 4 back: safe. At 2 each it is 8.
        List<GuardRecipe> recipes = recipes(craft("minecraft:oak_log", 1, "minecraft:oak_planks", 4));
        GuardReport safe = check(registry(both("minecraft:oak_log", 4L), both("minecraft:oak_planks", 2L)), recipes);
        assertTrue(safe.passed(), () -> describe(safe));
        GuardReport broken = check(registry(both("minecraft:oak_log", 4L), both("minecraft:oak_planks", 3L)), recipes);
        Failure failure = only(broken);
        assertEquals("5.00", failure.cost());
        assertEquals("8.00", failure.revenue());
    }

    @Test void aProfitableTwoStepChainFails() {
        // Clay at 11 smelts into a brick nobody trades; one brick crafts into four tiles bought
        // at 3 each. Neither step alone ends at something the Houses buy.
        CommodityRegistry registry = registry(sold("mod:clay", 10L), bought("mod:tile", 4L));
        List<GuardRecipe> recipes = recipes(smelt("mod:clay", "mod:brick"), craft("mod:brick", 1, "mod:tile", 4));
        GuardReport report = check(registry, recipes);
        Failure failure = only(report);
        assertEquals(Kind.CHAIN, failure.kind());
        assertEquals("mod:tile", failure.commodity());
        assertEquals("test:recipe_1", failure.recipe());
        assertEquals("11.00", failure.cost());
        assertEquals("12.00", failure.revenue());
        assertEquals(2, failure.steps());
        assertEquals(Set.of("mod:tile"), report.buyOff());

        // A guard that looks one step deep cannot see it; the default depth can.
        GuardReport shallow = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null, null, null, 1));
        assertTrue(shallow.passed());
        assertEquals(1, shallow.depth());
    }

    @Test void aChainLongerThanTheDepthIsNotFollowedButOneWithinItIs() {
        CommodityRegistry registry = registry(sold("mod:a", 10L), bought("mod:e", 100L));
        List<GuardRecipe> recipes = recipes(smelt("mod:a", "mod:b"), smelt("mod:b", "mod:c"), smelt("mod:c", "mod:d"),
                smelt("mod:d", "mod:e"));
        assertTrue(check(registry, recipes).passed());
        GuardReport deep = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null, null, null, 4));
        assertEquals(4, only(deep).steps());
        // The depth is kept within bounds whatever is asked for.
        assertEquals(ArbitrageGuard.MAX_DEPTH, ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0,
                null, null, null, 99)).depth());
        assertEquals(1, ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null, null, null,
                -3)).depth());
    }

    @Test void costsAreExactFractions() {
        // 10 buys one block, which gives three shards (3 1/3 each); three shards make one gem
        // bought at exactly 10. In floating point three thirds need not be one whole.
        CommodityDefinition block = CommodityDefinition.of("mod:block", 9L, 100L, 0L, SPREAD, CommodityClass.STAPLE);
        CommodityDefinition gem = CommodityDefinition.of("mod:gem", 11L, 0L, 100L, SPREAD, CommodityClass.STAPLE);
        assertEquals(10L, block.ask());
        assertEquals(9L, gem.bid());
        List<GuardRecipe> recipes = recipes(craft("mod:block", 1, "mod:shard", 3), craft("mod:shard", 3, "mod:gem", 1));
        // Revenue 9 against a cost of exactly 10: safe.
        assertTrue(check(registry(block, gem), recipes).passed());
        CommodityDefinition dearer = CommodityDefinition.of("mod:gem", 12L, 0L, 100L, SPREAD, CommodityClass.STAPLE);
        assertEquals(10L, dearer.bid());
        // Revenue 10 against a cost of exactly 10: not strictly below.
        Failure failure = only(check(registry(block, dearer), recipes));
        assertEquals("10.00", failure.cost());
        assertEquals("10.00", failure.revenue());
    }

    @Test void anAskThatIsNotAboveTheBidFails() {
        CommodityRegistry registry = registry(both("minecraft:coal", 6L), both("minecraft:diamond", 400L));
        // At the reference prices the spread always keeps them apart.
        assertTrue(check(registry, List.of()).passed());
        ArbitrageGuard.Quotes flat = new ArbitrageGuard.Quotes() {
            @Override public long ask(CommodityDefinition commodity) {
                return commodity.key().equals("minecraft:coal") ? 6L : commodity.ask();
            }
            @Override public long bid(CommodityDefinition commodity) {
                return commodity.key().equals("minecraft:coal") ? 6L : commodity.bid();
            }
        };
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, flat, List.of(), 0, null, null, null, 3));
        Failure failure = only(report);
        assertEquals(Kind.SPREAD, failure.kind());
        assertEquals("minecraft:coal", failure.commodity());
        assertEquals("6.00", failure.cost());
        assertEquals("6.00", failure.revenue());
        assertEquals(Set.of("minecraft:coal"), report.buyOff());
        // A commodity that is only bought has no ask to compare.
        assertTrue(ArbitrageGuard.check(new ArbitrageGuard.Input(registry(bought("minecraft:coal", 6L)), flat, List.of(), 0,
                null, null, null, 3)).passed());
    }

    @Test void theCheapestAlternativeOfAnIngredientIsTheOneThatCounts() {
        // The ingredient is a gold block OR anything in a tag; dirt is in the tag.
        Map<String, List<String>> tags = Map.of("mod:fillers", List.of("minecraft:dirt", "minecraft:gravel"));
        CommodityRegistry registry = registry(sold("minecraft:gold_block", 200L), sold("minecraft:dirt", 1L),
                bought("mod:idol", 50L));
        List<GuardRecipe> recipes = recipes("{\"type\": \"minecraft:crafting_shaped\", \"pattern\": [\"X\"], "
                + "\"key\": {\"X\": [\"minecraft:gold_block\", \"#mod:fillers\"]}, \"result\": {\"id\": \"mod:idol\"}}");
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null,
                tag -> tags.getOrDefault(tag, List.of()), null, 3));
        Failure failure = only(report);
        assertEquals("2.00", failure.cost());
        assertEquals("45.00", failure.revenue());
        // Without the tag only the gold block is left, and that route loses.
        assertTrue(check(registry, recipes).passed());
    }

    @Test void aCommodityKeyedOnATagIsBoughtAndSoldByEveryItemInIt() {
        Map<String, List<String>> tags = Map.of("minecraft:logs", List.of("minecraft:oak_log", "minecraft:birch_log"),
                "minecraft:planks", List.of("minecraft:oak_planks", "minecraft:birch_planks"));
        CommodityRegistry registry = registry(tags, both("#minecraft:logs", 4L), both("#minecraft:planks", 3L));
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null,
                recipes(craft("minecraft:birch_log", 1, "minecraft:birch_planks", 4)), 0, null,
                tag -> tags.getOrDefault(tag, List.of()), null, 3));
        Failure failure = only(report);
        assertEquals("#minecraft:planks", failure.commodity());
        assertEquals(Set.of("#minecraft:planks"), report.buyOff());
    }

    @Test void aCodeSideBonusCanMakeASafeRecipeProfitable() {
        // Raw ore at 11 gives two dust bought at 5: 10 back, safe. The better machine gives three.
        CommodityRegistry registry = registry(sold("mod:raw_ore", 10L), bought("mod:dust", 6L));
        List<GuardRecipe> recipes = recipes("{\"type\": \"mod:ore_processing\", \"ingredient\": \"mod:raw_ore\", "
                + "\"result\": {\"id\": \"mod:dust\", \"count\": 2}}");
        assertTrue(check(registry, recipes).passed());
        GuardBonuses bonuses = GuardBonuses.parse(Map.of("pack:machines", JsonParser.parseString(
                "{\"output_bonus\": [{\"recipe_type\": \"mod:ore_processing\", \"extra_output\": 1}]}")));
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, bonuses, null, null, 3));
        Failure failure = only(report);
        assertEquals(Kind.CHAIN, failure.kind());
        assertEquals("11.00", failure.cost());
        assertEquals("15.00", failure.revenue());
        assertEquals(Set.of("mod:dust"), report.buyOff());
    }

    @Test void aKnownConversionIsAStepLikeAnyRecipe() {
        // Cartridges at 5 foul into dirty filters in a machine's code; a dirty filter is
        // processed into dust, two with the better machine, bought at 3 each.
        CommodityRegistry registry = registry(sold("nerotech:filter_cartridge", 4L), bought("nerotech:iron_dust", 4L));
        List<GuardRecipe> recipes = recipes("{\"type\": \"nerotech:ore_processing\", \"ingredient\": \"nerotech:dirty_filter\", "
                + "\"result\": {\"id\": \"nerotech:iron_dust\"}}");
        // Without the conversion nothing links a cartridge to a dirty filter.
        assertTrue(check(registry, recipes).passed());
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0,
                GuardTesting.shippedBonuses(), null, null, 3));
        Failure failure = only(report);
        assertEquals("nerotech:iron_dust", failure.commodity());
        assertEquals("5.00", failure.cost());
        assertEquals("6.00", failure.revenue());
        assertEquals(2, failure.steps());
        // The two shipped conversions count as recipes that were read.
        assertEquals(3, report.recipesRead());
    }

    // --- the Credit Chip -------------------------------------------------------------------------

    private static List<GuardRecipe> chipRecipe() {
        return RecipeReader.readAll(Map.of("neroeconomy:credit_chip", GuardTesting.ownRecipes().get("neroeconomy:credit_chip")))
                .recipes();
    }

    @Test void chipIngredientsSoldForLessThanTheChipRedeemsForLoseTheirSellSide() {
        // 2 copper at 6, 1 gold at 22, 2 redstone at 4, 1 iron at 11: 53 for a chip worth 100.
        CommodityRegistry registry = registry(both("minecraft:copper_ingot", 5L), both("minecraft:gold_ingot", 20L),
                both("minecraft:redstone", 3L), both("minecraft:iron_ingot", 10L), both("minecraft:coal", 6L));
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, chipRecipe(), 0, null, null,
                Map.of(GuardTesting.CREDIT_CHIP, 100L), 3));
        Failure failure = only(report);
        assertEquals(Kind.REDEEM, failure.kind());
        assertEquals(GuardTesting.CREDIT_CHIP, failure.commodity());
        assertEquals("neroeconomy:credit_chip", failure.recipe());
        assertEquals("53.00", failure.cost());
        assertEquals("100.00", failure.revenue());
        // The outlet is not a House, so it is the ingredients that stop being sold; coal is untouched.
        assertEquals(Set.of("minecraft:copper_ingot", "minecraft:gold_ingot", "minecraft:redstone", "minecraft:iron_ingot"),
                report.sellOff());
        assertTrue(report.buyOff().isEmpty());

        // A chip that redeems for less than its ingredients cost is safe; for exactly as much it is not.
        assertTrue(ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, chipRecipe(), 0, null, null,
                Map.of(GuardTesting.CREDIT_CHIP, 52L), 3)).passed());
        assertFalse(ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, chipRecipe(), 0, null, null,
                Map.of(GuardTesting.CREDIT_CHIP, 53L), 3)).passed());
    }

    @Test void theChipIsSafeWhenOneIngredientCannotBeBought() {
        CommodityRegistry registry = registry(both("minecraft:copper_ingot", 5L), both("minecraft:gold_ingot", 20L),
                both("minecraft:redstone", 3L), bought("minecraft:iron_ingot", 10L));
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, chipRecipe(), 0, null, null,
                Map.of(GuardTesting.CREDIT_CHIP, 100L), 3));
        assertTrue(report.passed(), () -> describe(report));
        assertTrue(report.sellOff().isEmpty());
    }

    @Test void sellSidesAreSwitchedOffUntilNoRedeemableChainIsLeft() {
        // Either of two cheap things makes the token; switching off one leaves the other.
        CommodityRegistry registry = registry(sold("mod:a", 2L), sold("mod:b", 3L), sold("mod:c", 500L));
        List<GuardRecipe> recipes = recipes("{\"type\": \"minecraft:crafting_shapeless\", \"ingredients\": "
                + "[[\"mod:a\", \"mod:b\", \"mod:c\"]], \"result\": {\"id\": \"mod:token\"}}");
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(registry, null, recipes, 0, null, null,
                Map.of("mod:token", 10L), 3));
        assertEquals(2, report.count(Kind.REDEEM), () -> describe(report));
        // The dear one stays on sale: a token made from it costs more than it is worth.
        assertEquals(Set.of("mod:a", "mod:b"), report.sellOff());
    }

    // --- clashes ---------------------------------------------------------------------------------

    @Test void anItemTwoCommoditiesClaimIsReported() {
        Map<String, List<String>> tags = Map.of("mod:first", List.of("mod:thing"), "mod:second", List.of("mod:thing", "mod:other"));
        CommodityRegistry registry = registry(tags, bought("#mod:first", 4L), bought("#mod:second", 9L));
        GuardReport report = check(registry, List.of());
        Failure failure = only(report);
        assertEquals(Kind.CLASH, failure.kind());
        assertEquals("#mod:second", failure.commodity());
        assertEquals("mod:thing", failure.recipe());
        assertEquals("#mod:first", failure.other());
        assertEquals("mod:thing is claimed by #mod:first and by #mod:second; #mod:first keeps it", failure.describe());
        // A clash was settled when the files were read; it switches nothing off.
        assertTrue(report.buyOff().isEmpty());
        assertTrue(report.sellOff().isEmpty());
        assertEquals(1, report.count(Kind.CLASH));
        assertEquals(0, report.count(Kind.CHAIN));
    }

    @Test void aSideTheGuardAlreadySwitchedOffIsNotAChain() {
        CommodityRegistry registry = registry(both("minecraft:sand", 4L), both("minecraft:glass", 8L));
        List<GuardRecipe> recipes = recipes(smelt("minecraft:sand", "minecraft:glass"));
        GuardReport first = check(registry, recipes);
        assertFalse(first.passed());
        // With the verdict applied the same data has no profitable route left.
        GuardReport second = check(registry.guarded(first.buyOff(), first.sellOff()), recipes);
        assertTrue(second.passed(), () -> describe(second));
    }

    @Test void everyFailingRecipeIsListed() {
        CommodityRegistry registry = registry(sold("mod:a", 10L), sold("mod:b", 3L), bought("mod:x", 20L), bought("mod:y", 20L));
        GuardReport report = check(registry, recipes(smelt("mod:a", "mod:x"), smelt("mod:b", "mod:x"), smelt("mod:b", "mod:y"),
                craft("mod:b", 9, "mod:y", 1)));
        // Three recipes profit; the fourth (nine b for one y: 36 against 18) loses.
        assertEquals(3, report.count(Kind.CHAIN), () -> describe(report));
        assertEquals(Set.of("mod:x", "mod:y"), report.buyOff());
        assertEquals(4, report.chainsChecked());
        assertEquals(List.of("mod:x", "mod:x", "mod:y"), report.failures().stream().map(Failure::commodity).toList());
    }
}
