package za.co.neroland.neroeconomy.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/** The guard's data files: code-side output bonuses and conversions that have no recipe. */
class GuardBonusesTest {
    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    @Test void theShippedFileTeachesTheGuardNerotechsTwoCodeSideRules() {
        GuardBonuses bonuses = GuardTesting.shippedBonuses();
        assertTrue(bonuses.problems().isEmpty(), () -> bonuses.problems().toString());
        // The Advanced Ore Processor: one more dust per operation.
        assertEquals(1, bonuses.extraOutput("nerotech:ore_processing"));
        assertEquals(0, bonuses.extraOutput("nerotech:fabricating"));
        assertEquals(0, bonuses.extraOutput(null));
        // The filter loop: the Scrubber step, and the whole loop in one.
        assertEquals(2, bonuses.conversions().size());
        GuardRecipe fouling = bonuses.conversions().get(0);
        assertEquals("nerotech:scrubber_fouls_filter", fouling.id());
        assertEquals(GuardBonuses.CONVERSION_TYPE, fouling.type());
        assertEquals(List.of(new GuardRecipe.Input(List.of("nerotech:filter_cartridge"), 1)), fouling.inputs());
        assertEquals("nerotech:dirty_filter", fouling.result());
        GuardRecipe loop = bonuses.conversions().get(1);
        assertEquals("nerotech:filter_loop", loop.id());
        assertEquals(List.of(new GuardRecipe.Input(List.of("minecraft:iron_ingot"), 2),
                new GuardRecipe.Input(List.of("minecraft:paper"), 6)), loop.inputs());
        assertEquals("nerotech:iron_dust", loop.result());
        assertEquals(8, loop.count());
    }

    @Test void theLargestBonusForATypeCountsAcrossFiles() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("a:one", json("{\"output_bonus\": [{\"recipe_type\": \"mod:press\", \"extra_output\": 1}]}"));
        files.put("b:two", json("{\"output_bonus\": [{\"recipe_type\": \"mod:press\", \"extra_output\": 3},"
                + "{\"recipe_type\": \"mod:saw\", \"extra_output\": 2}]}"));
        GuardBonuses bonuses = GuardBonuses.parse(files);
        assertEquals(3, bonuses.extraOutput("mod:press"));
        assertEquals(2, bonuses.extraOutput("mod:saw"));
        assertTrue(bonuses.problems().isEmpty());
    }

    @Test void aConversionMayUseTagsAndCounts() {
        GuardBonuses bonuses = GuardBonuses.parse(Map.of("pack:machines", json("""
                { "conversions": [ { "id": "pack:crusher", "inputs": [ { "tag": "c:ores/iron" }, { "item": "minecraft:coal", "count": 2 } ],
                    "result": { "id": "pack:crushed_iron", "count": 3 } } ] }""")));
        assertTrue(bonuses.problems().isEmpty());
        GuardRecipe crusher = bonuses.conversions().get(0);
        assertEquals(List.of(new GuardRecipe.Input(List.of("#c:ores/iron"), 1),
                new GuardRecipe.Input(List.of("minecraft:coal"), 2)), crusher.inputs());
        assertEquals(3, crusher.count());
    }

    @Test void badFilesAndEntriesAreSkippedAndReportedByAFixedWord() {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        files.put("a:null", null);
        files.put("b:array", json("[]"));
        files.put("c:field", json("{\"bonus\": []}"));
        files.put("d:bonus", json("{\"output_bonus\": [{\"recipe_type\": \"press\", \"extra_output\": 1},"
                + "{\"recipe_type\": \"mod:press\", \"extra_output\": 0},"
                + "{\"recipe_type\": \"mod:press\", \"extra_output\": 65},"
                + "{\"recipe_type\": \"mod:press\", \"extra_output\": 1, \"always\": true}, 7,"
                + "{\"recipe_type\": \"mod:saw\", \"extra_output\": 2}]}"));
        files.put("e:conversion", json("{\"conversions\": [{\"id\": \"mod:x\", \"inputs\": [], \"result\": {\"id\": \"mod:y\"}},"
                + "{\"id\": \"mod:x\", \"inputs\": [{\"item\": \"mod:a\"}]},"
                + "{\"inputs\": [{\"item\": \"mod:a\"}], \"result\": {\"id\": \"mod:y\"}},"
                + "{\"id\": \"mod:ok\", \"inputs\": [{\"item\": \"mod:a\"}], \"result\": {\"id\": \"mod:y\"}}]}"));
        files.put("f:kinds", json("{\"output_bonus\": {}, \"conversions\": 3}"));
        GuardBonuses bonuses = GuardBonuses.parse(files);
        // What is good in a file still loads.
        assertEquals(2, bonuses.extraOutput("mod:saw"));
        assertEquals(0, bonuses.extraOutput("mod:press"));
        assertEquals(1, bonuses.conversions().size());
        assertEquals("mod:ok", bonuses.conversions().get(0).id());
        assertEquals(List.of(new GuardBonuses.Problem("a:null", "not_an_object"),
                new GuardBonuses.Problem("b:array", "not_an_object"),
                new GuardBonuses.Problem("c:field", "unknown_field"),
                new GuardBonuses.Problem("d:bonus", "bad_output_bonus"),
                new GuardBonuses.Problem("d:bonus", "bad_output_bonus"),
                new GuardBonuses.Problem("d:bonus", "bad_output_bonus"),
                new GuardBonuses.Problem("d:bonus", "bad_output_bonus"),
                new GuardBonuses.Problem("d:bonus", "bad_output_bonus"),
                new GuardBonuses.Problem("e:conversion", "bad_conversion"),
                new GuardBonuses.Problem("e:conversion", "bad_conversion"),
                new GuardBonuses.Problem("e:conversion", "bad_conversion"),
                new GuardBonuses.Problem("f:kinds", "bad_output_bonus"),
                new GuardBonuses.Problem("f:kinds", "bad_conversions")), bonuses.problems());
    }
}
