package za.co.neroland.neroeconomy.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * What the arbitrage guard is told about conversions it cannot read from a recipe file, because
 * they happen in a mod's code: a machine that gives more than its recipe says, or a step that has
 * no recipe at all. The guard applies them itself, so a commodity that is only safe as long as
 * nobody owns that machine still fails the check.
 *
 * <p>They are data: {@code data/<namespace>/neroeconomy/guard/<path>.json}, so a pack that adds a
 * machine can teach the guard about it.
 *
 * <pre>
 * {
 *   "comment": "free text, ignored",
 *   "output_bonus": [
 *     { "recipe_type": "nerotech:ore_processing", "extra_output": 1, "comment": "ignored" }
 *   ],
 *   "conversions": [
 *     { "id": "nerotech:scrubber_fouling",
 *       "inputs": [ { "item": "nerotech:filter_cartridge", "count": 1 } ],
 *       "result": { "id": "nerotech:dirty_filter", "count": 1 } }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code output_bonus}: every recipe of {@code recipe_type} is taken to give
 *       {@code extra_output} (1 to 64) more per operation — the best machine anyone could own.
 *       When several files name the same type the largest bonus counts.</li>
 *   <li>{@code conversions}: extra recipes. {@code id} names it in reports; each input is an
 *       {@code item} or a {@code tag} with an optional {@code count}; the result is an item id
 *       with an optional {@code count}.</li>
 * </ul>
 *
 * <p>A file or an entry that does not fit is skipped and reported by its file id and a fixed word.
 *
 * @param extraOutput by recipe type, how many more one operation gives
 * @param conversions the extra recipes, typed {@link #CONVERSION_TYPE}
 * @param problems    what was skipped
 */
public record GuardBonuses(Map<String, Integer> extraOutput, List<GuardRecipe> conversions, List<Problem> problems) {
    /** The recipe type the guard gives a conversion; no output bonus ever applies to it. */
    public static final String CONVERSION_TYPE = "neroeconomy:known_conversion";
    /** The largest {@code extra_output} a file may state. */
    public static final int MAX_EXTRA_OUTPUT = 64;
    /** The most conversions that are read; further ones, in file order, are skipped. */
    public static final int MAX_CONVERSIONS = 1024;

    private static final Set<String> FILE_FIELDS = Set.of("comment", "output_bonus", "conversions");
    private static final Set<String> BONUS_FIELDS = Set.of("comment", "recipe_type", "extra_output");
    private static final Set<String> CONVERSION_FIELDS = Set.of("comment", "id", "inputs", "result");

    /** Nothing is known beyond the recipe files. */
    public static final GuardBonuses NONE = new GuardBonuses(Map.of(), List.of(), List.of());

    /**
     * One file or entry that was skipped.
     *
     * @param file the id of the data file
     * @param word why, as a fixed word
     */
    public record Problem(String file, String word) { }

    public GuardBonuses {
        extraOutput = Map.copyOf(extraOutput);
        conversions = List.copyOf(conversions);
        problems = List.copyOf(problems);
    }

    /** How many more one operation of a recipe of this type gives; 0 for a type no file names. */
    public int extraOutput(String recipeType) {
        Integer extra = recipeType == null ? null : extraOutput.get(recipeType);
        return extra == null ? 0 : extra;
    }

    /** Reads every guard file; never throws. {@code files} maps a file id to its JSON (null for unreadable). */
    public static GuardBonuses parse(Map<String, JsonElement> files) {
        Map<String, Integer> extra = new LinkedHashMap<>();
        List<GuardRecipe> conversions = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            try {
                parseFile(file.getKey(), file.getValue(), extra, conversions, problems);
            } catch (RuntimeException malformed) {
                problems.add(new Problem(file.getKey(), "unreadable"));
            }
        }
        return new GuardBonuses(extra, conversions, problems);
    }

    private static void parseFile(String file, JsonElement json, Map<String, Integer> extra, List<GuardRecipe> conversions,
            List<Problem> problems) {
        if (json == null || !json.isJsonObject()) {
            problems.add(new Problem(file, "not_an_object"));
            return;
        }
        JsonObject object = json.getAsJsonObject();
        for (String key : object.keySet()) if (!FILE_FIELDS.contains(key)) {
            problems.add(new Problem(file, "unknown_field"));
            return;
        }
        if (object.has("output_bonus")) {
            if (!object.get("output_bonus").isJsonArray()) problems.add(new Problem(file, "bad_output_bonus"));
            else for (JsonElement element : object.getAsJsonArray("output_bonus")) {
                String type = element.isJsonObject() ? type(element.getAsJsonObject().get("recipe_type")) : null;
                Integer amount = element.isJsonObject() ? whole(element.getAsJsonObject().get("extra_output"),
                        MAX_EXTRA_OUTPUT) : null;
                if (type == null || amount == null || !BONUS_FIELDS.containsAll(element.getAsJsonObject().keySet()))
                    problems.add(new Problem(file, "bad_output_bonus"));
                else extra.merge(type, amount, Math::max);
            }
        }
        if (object.has("conversions")) {
            if (!object.get("conversions").isJsonArray()) problems.add(new Problem(file, "bad_conversions"));
            else for (JsonElement element : object.getAsJsonArray("conversions")) {
                GuardRecipe conversion = element.isJsonObject() ? conversion(element.getAsJsonObject()) : null;
                if (conversion == null) problems.add(new Problem(file, "bad_conversion"));
                else if (conversions.size() >= MAX_CONVERSIONS) problems.add(new Problem(file, "too_many_conversions"));
                else conversions.add(conversion);
            }
        }
    }

    private static GuardRecipe conversion(JsonObject object) {
        if (!CONVERSION_FIELDS.containsAll(object.keySet())) return null;
        String id = type(object.get("id"));
        JsonElement inputsJson = object.get("inputs");
        JsonElement resultJson = object.get("result");
        if (id == null || inputsJson == null || !inputsJson.isJsonArray() || resultJson == null
                || !resultJson.isJsonObject()) return null;
        // The reader's own shapes: a list of ingredients and a result.
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", CONVERSION_TYPE);
        recipe.add("ingredients", inputsJson);
        recipe.add("result", resultJson);
        return RecipeReader.read(id, recipe);
    }

    /** A {@code namespace:path} string, or null. */
    private static String type(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) return null;
        String text = primitive.getAsString();
        int colon = text.indexOf(':');
        return colon > 0 && colon < text.length() - 1 && text.length() <= 256 && text.indexOf(' ') < 0 ? text : null;
    }

    /** A whole number from 1 to {@code max}, or null. */
    private static Integer whole(JsonElement element, int max) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
        double value = element.getAsDouble();
        return value == Math.rint(value) && value >= 1.0D && value <= max ? (int) value : null;
    }
}
