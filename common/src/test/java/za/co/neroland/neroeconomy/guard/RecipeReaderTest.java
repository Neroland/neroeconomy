package za.co.neroland.neroeconomy.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/** Reading recipes from raw JSON: the vanilla types, the machine shapes of the sibling mods, and what is skipped. */
class RecipeReaderTest {
    private static GuardRecipe read(String json) {
        return RecipeReader.read("test:recipe", JsonParser.parseString(json));
    }

    private static GuardRecipe.Input input(int count, String... options) {
        return new GuardRecipe.Input(List.of(options), count);
    }

    @Test void shapedCraftingCountsEachKeyLetterInThePattern() {
        GuardRecipe recipe = read("""
                { "type": "minecraft:crafting_shaped", "category": "misc",
                  "key": { "P": "minecraft:paper", "I": "minecraft:iron_ingot" },
                  "pattern": [ "PPP", "I I", "PPP" ],
                  "result": { "id": "nerotech:filter_cartridge", "count": 4 } }""");
        assertNotNull(recipe);
        assertEquals("test:recipe", recipe.id());
        assertEquals("minecraft:crafting_shaped", recipe.type());
        assertEquals(List.of(input(6, "minecraft:paper"), input(2, "minecraft:iron_ingot")), recipe.inputs());
        assertEquals("nerotech:filter_cartridge", recipe.result());
        assertEquals(4, recipe.count());
    }

    @Test void anIngredientMayBeATagOrAListOfAlternatives() {
        GuardRecipe torch = read("""
                { "type": "minecraft:crafting_shaped",
                  "key": { "#": "minecraft:stick", "X": [ "minecraft:coal", "minecraft:charcoal" ] },
                  "pattern": [ "X", "#" ],
                  "result": { "count": 4, "id": "minecraft:torch" } }""");
        assertEquals(List.of(input(1, "minecraft:coal", "minecraft:charcoal"), input(1, "minecraft:stick")), torch.inputs());
        GuardRecipe planks = read("""
                { "type": "minecraft:crafting_shapeless", "ingredients": [ "#minecraft:oak_logs" ],
                  "result": { "count": 4, "id": "minecraft:oak_planks" } }""");
        assertEquals(List.of(input(1, "#minecraft:oak_logs")), planks.inputs());
        assertEquals(4, planks.count());
    }

    @Test void shapelessCraftingKeepsEveryIngredient() {
        GuardRecipe recipe = read("""
                { "type": "minecraft:crafting_shapeless", "group": "netherite_ingot",
                  "ingredients": [ "minecraft:netherite_scrap", "minecraft:netherite_scrap", "minecraft:gold_ingot" ],
                  "result": { "id": "minecraft:netherite_ingot" } }""");
        assertEquals(3, recipe.inputs().size());
        assertEquals(1, recipe.count());
    }

    @Test void cookingAndStonecuttingTakeOneIngredient() {
        for (String type : List.of("smelting", "blasting", "smoking", "campfire_cooking", "stonecutting")) {
            GuardRecipe recipe = read("{ \"type\": \"minecraft:" + type + "\", \"experience\": 0.7, "
                    + "\"ingredient\": \"minecraft:raw_iron\", \"result\": { \"id\": \"minecraft:iron_ingot\" } }");
            assertNotNull(recipe, type);
            assertEquals("minecraft:" + type, recipe.type());
            assertEquals(List.of(input(1, "minecraft:raw_iron")), recipe.inputs());
            assertEquals("minecraft:iron_ingot", recipe.result());
        }
    }

    @Test void aSmithingTransformUsesUpTemplateBaseAndAddition() {
        GuardRecipe recipe = read("""
                { "type": "minecraft:smithing_transform", "addition": "#minecraft:netherite_tool_materials",
                  "base": "minecraft:diamond_sword", "result": { "id": "minecraft:netherite_sword" },
                  "template": "minecraft:netherite_upgrade_smithing_template" }""");
        assertEquals(List.of(input(1, "minecraft:netherite_upgrade_smithing_template"), input(1, "minecraft:diamond_sword"),
                input(1, "#minecraft:netherite_tool_materials")), recipe.inputs());
    }

    @Test void theFiveNerotechShapesAreRead() {
        Map<String, JsonElement> sample = GuardTesting.nerotechRecipes();
        RecipeReader.Read read = RecipeReader.readAll(sample);
        assertEquals(sample.size(), read.recipes().size());
        assertEquals(0, read.skipped());
        Map<String, GuardRecipe> byId = new TreeMap<>();
        for (GuardRecipe recipe : read.recipes()) byId.put(recipe.id(), recipe);

        GuardRecipe ore = byId.get("nerotech:ore_processing_iron");
        assertEquals("nerotech:ore_processing", ore.type());
        assertEquals(List.of(input(1, "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:raw_iron")), ore.inputs());
        assertEquals("nerotech:iron_dust", ore.result());
        assertEquals(2, ore.count());

        GuardRecipe chemical = byId.get("nerotech:chemical_processing_raw_iron");
        assertEquals("nerotech:chemical_processing", chemical.type());
        assertEquals(3, chemical.count());

        GuardRecipe fabricating = byId.get("nerotech:fabricating_circuit_board");
        assertEquals("nerotech:fabricating", fabricating.type());
        assertEquals(List.of(input(1, "nerotech:gold_dust")), fabricating.inputs());
        assertEquals(2, fabricating.count());

        GuardRecipe advanced = byId.get("nerotech:advanced_fabricating_fusion_cell");
        assertEquals("nerotech:advanced_fabricating", advanced.type());
        assertEquals(List.of(input(1, "#c:gems/void_crystal")), advanced.inputs());

        GuardRecipe collider = byId.get("nerotech:collider_antimatter_cell");
        assertEquals("nerotech:collider", collider.type());
        assertEquals(List.of(input(1, "nerotech:stellar_cell"), input(1, "nerotech:stellar_cell")), collider.inputs());
        assertEquals("nerotech:antimatter_cell", collider.result());
    }

    @Test void statedInputCountsAndOlderIngredientFormsAreHonoured() {
        GuardRecipe conversion = read("""
                { "type": "neroagriculture:fragment_fusion", "ingredient": "neroagriculture:territe_fragment",
                  "input_count": 4, "secondary": "minecraft:redstone", "secondary_count": 8,
                  "result": { "id": "neroagriculture:forgite_fragment" }, "energy": 6400, "ticks": 200 }""");
        assertEquals(List.of(input(4, "neroagriculture:territe_fragment"), input(8, "minecraft:redstone")), conversion.inputs());

        GuardRecipe older = read("""
                { "type": "othermod:press", "ingredients": [ { "item": "minecraft:iron_ingot", "count": 3 },
                  { "tag": "c:dusts/redstone" } ], "result": { "item": "othermod:plate", "count": 2 } }""");
        assertEquals(List.of(input(3, "minecraft:iron_ingot"), input(1, "#c:dusts/redstone")), older.inputs());
        assertEquals("othermod:plate", older.result());
        assertEquals(2, older.count());

        // A bare result id, and ids without a namespace, are the game's own.
        GuardRecipe bare = read("{ \"type\": \"smelting\", \"ingredient\": \"sand\", \"result\": \"glass\" }");
        assertEquals("minecraft:smelting", bare.type());
        assertEquals(List.of(input(1, "minecraft:sand")), bare.inputs());
        assertEquals("minecraft:glass", bare.result());
    }

    @Test void aWrapperAroundARecipeIsReadThrough() {
        GuardRecipe gated = read("""
                { "type": "nerofactions:gated", "faction": "nerofactions:free_colonists", "tier": "associate",
                  "recipe": { "type": "minecraft:crafting_shapeless",
                              "ingredients": [ "minecraft:torch", "minecraft:iron_nugget" ],
                              "result": { "id": "minecraft:lantern" } } }""");
        assertNotNull(gated);
        assertEquals("minecraft:crafting_shapeless", gated.type());
        assertEquals("minecraft:lantern", gated.result());
    }

    @Test void whatCannotBeReadIsSkippedAndCounted() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("a:good", JsonParser.parseString(
                "{ \"type\": \"minecraft:smelting\", \"ingredient\": \"minecraft:sand\", \"result\": { \"id\": \"minecraft:glass\" } }"));
        // Special crafting has no ingredients or no result to read.
        files.put("b:special", JsonParser.parseString("{ \"type\": \"minecraft:crafting_special_repairitem\" }"));
        files.put("c:trim", JsonParser.parseString("{ \"type\": \"minecraft:smithing_trim\", \"addition\": \"#minecraft:trim_materials\", "
                + "\"base\": \"#minecraft:trimmable_armor\", \"pattern\": \"minecraft:bolt\", \"template\": \"minecraft:bolt_armor_trim_smithing_template\" }"));
        files.put("d:transmute", JsonParser.parseString("{ \"type\": \"minecraft:crafting_transmute\", \"input\": \"#minecraft:shulker_boxes\", "
                + "\"material\": \"minecraft:blue_dye\", \"result\": { \"id\": \"minecraft:blue_shulker_box\" } }"));
        // A result with components is not an unmodified stack.
        files.put("e:components", JsonParser.parseString("{ \"type\": \"minecraft:crafting_shapeless\", \"ingredients\": [ \"minecraft:paper\" ], "
                + "\"result\": { \"id\": \"minecraft:firework_rocket\", \"components\": { \"minecraft:fireworks\": { \"flight_duration\": 1 } } } }"));
        // A custom ingredient type is not one item or one tag.
        files.put("f:custom", JsonParser.parseString("{ \"type\": \"minecraft:crafting_shapeless\", \"ingredients\": [ "
                + "{ \"type\": \"neoforge:components\", \"items\": \"minecraft:potion\" } ], \"result\": { \"id\": \"minecraft:glass_bottle\" } }"));
        files.put("g:unknown_shape", JsonParser.parseString("{ \"type\": \"othermod:ritual\", \"altar\": \"minecraft:gold_block\", "
                + "\"result\": { \"id\": \"minecraft:diamond\" } }"));
        files.put("h:bad_pattern", JsonParser.parseString("{ \"type\": \"minecraft:crafting_shaped\", \"key\": { \"A\": \"minecraft:stick\" }, "
                + "\"pattern\": [ \"AB\" ], \"result\": { \"id\": \"minecraft:ladder\" } }"));
        files.put("i:no_type", JsonParser.parseString("{ \"ingredient\": \"minecraft:sand\", \"result\": { \"id\": \"minecraft:glass\" } }"));
        files.put("j:bad_count", JsonParser.parseString("{ \"type\": \"minecraft:smelting\", \"ingredient\": \"minecraft:sand\", "
                + "\"result\": { \"id\": \"minecraft:glass\", \"count\": 0 } }"));
        files.put("k:not_json", null);
        files.put("l:array", JsonParser.parseString("[1, 2]"));
        RecipeReader.Read read = RecipeReader.readAll(files);
        assertEquals(1, read.recipes().size());
        assertEquals("a:good", read.recipes().get(0).id());
        assertEquals(files.size() - 1, read.skipped());
        assertNull(RecipeReader.read(null, files.get("a:good")));
    }

    @Test void everyVanillaRecipeOfAReadableTypeIsRead() {
        Map<String, JsonElement> vanilla = GuardTesting.vanillaRecipes();
        RecipeReader.Read read = RecipeReader.readAll(vanilla);
        assertEquals(vanilla.size(), read.recipes().size() + read.skipped());
        // Brewing (data-driven from 26.3) only ever makes a potion, whose contents are components:
        // never an unmodified stack, so never a commodity, and every one of them is left out.
        long brewing = vanilla.values().stream()
                .filter(json -> "minecraft:brewing".equals(json.getAsJsonObject().get("type").getAsString())).count();
        long otherSkipped = read.skipped() - brewing;
        java.util.Set<String> readBeforeCheck = new java.util.HashSet<>();
        for (GuardRecipe recipe : read.recipes()) readBeforeCheck.add(recipe.id());
        for (Map.Entry<String, JsonElement> file : vanilla.entrySet())
            if ("minecraft:brewing".equals(file.getValue().getAsJsonObject().get("type").getAsString()))
                assertFalse(readBeforeCheck.contains(file.getKey()), () -> file.getKey() + " (brewing) was read");
        // Otherwise only special crafting, dyeing, transmuting and trims are left out.
        assertTrue(otherSkipped < 120, () -> otherSkipped + " vanilla recipes other than brewing were skipped");
        assertTrue(read.recipes().size() > 1300, () -> "only " + read.recipes().size() + " vanilla recipes were read");
        java.util.Set<String> readable = java.util.Set.of("minecraft:crafting_shaped", "minecraft:crafting_shapeless",
                "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking",
                "minecraft:stonecutting", "minecraft:smithing_transform");
        java.util.Set<String> readIds = new java.util.HashSet<>();
        for (GuardRecipe recipe : read.recipes()) readIds.add(recipe.id());
        for (Map.Entry<String, JsonElement> file : vanilla.entrySet()) {
            String type = file.getValue().getAsJsonObject().get("type").getAsString();
            boolean components = file.getValue().getAsJsonObject().has("result")
                    && file.getValue().getAsJsonObject().get("result").isJsonObject()
                    && file.getValue().getAsJsonObject().getAsJsonObject("result").has("components");
            if (readable.contains(type) && !components)
                assertTrue(readIds.contains(file.getKey()), () -> file.getKey() + " (" + type + ") was not read");
        }
    }
}
