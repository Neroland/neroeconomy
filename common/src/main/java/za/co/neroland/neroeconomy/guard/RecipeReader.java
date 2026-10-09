package za.co.neroland.neroeconomy.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reads recipes for the arbitrage guard straight from their JSON, without the game: the guard
 * runs in plain JUnit against the files in the Minecraft jar and at run time against whatever the
 * server's data packs hold, including recipe types of mods this build has never heard of.
 *
 * <p>A recipe is read by its <b>shape</b>, not by a list of known type names:
 *
 * <ul>
 *   <li>{@code pattern} + {@code key} + {@code result} — shaped crafting; each key letter is used
 *       as many times as it appears in the pattern;</li>
 *   <li>{@code ingredients} (a list) + {@code result} — shapeless crafting and machines that take
 *       several inputs;</li>
 *   <li>{@code ingredient} + {@code result} — smelting, blasting, smoking, campfire cooking,
 *       stonecutting and single-input machines; {@code input_count}, and {@code secondary} with
 *       {@code secondary_count}, are honoured when present;</li>
 *   <li>{@code input_a} + {@code input_b} + {@code result} — two-input machines;</li>
 *   <li>{@code base} + {@code addition} (+ {@code template}) + {@code result} — smithing
 *       transforms;</li>
 *   <li>{@code recipe} (an object) — a wrapper around one of the above.</li>
 * </ul>
 *
 * <p>An ingredient is an item id ({@code "minecraft:stick"}), a tag ({@code "#minecraft:planks"}),
 * a list of alternatives, or the older object forms {@code {"item": ...}} and {@code {"tag": ...}};
 * an object may carry a {@code count}. A result is {@code {"id": ..., "count": n}} (or the older
 * {@code "item"}), or a bare item id.
 *
 * <p>Anything else is <b>skipped and counted</b>: special crafting (dyeing, fireworks, banner
 * copies, trims), recipes whose result carries components (it would not be an unmodified stack),
 * custom ingredient types, and shapes this reader does not know.
 */
public final class RecipeReader {
    private static final int MAX_NESTING = 2;

    private RecipeReader() { }

    /**
     * What a set of recipe files came to.
     *
     * @param recipes the recipes that were read, in id order
     * @param skipped how many files were not read
     */
    public record Read(List<GuardRecipe> recipes, int skipped) {
        public Read {
            recipes = List.copyOf(recipes);
        }
    }

    /** Reads every file; never throws. {@code files} maps a recipe id to its JSON (null for unreadable). */
    public static Read readAll(Map<String, JsonElement> files) {
        List<GuardRecipe> recipes = new ArrayList<>(files.size());
        int skipped = 0;
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            GuardRecipe recipe = read(file.getKey(), file.getValue());
            if (recipe == null) skipped++;
            else recipes.add(recipe);
        }
        return new Read(recipes, skipped);
    }

    /** The recipe in {@code json}, or null when it is not one this reader can read. Never throws. */
    public static GuardRecipe read(String id, JsonElement json) {
        if (id == null || json == null || !json.isJsonObject()) return null;
        try {
            return read(id, json.getAsJsonObject(), 0);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static GuardRecipe read(String id, JsonObject object, int nesting) {
        if (!object.has("result") && object.has("recipe") && object.get("recipe").isJsonObject() && nesting < MAX_NESTING)
            return read(id, object.getAsJsonObject("recipe"), nesting + 1);
        String type = string(object.get("type"));
        if (type == null) return null;
        if (type.indexOf(':') < 0) type = "minecraft:" + type;

        JsonElement resultJson = object.get("result");
        String result;
        int count = 1;
        if (resultJson == null) return null;
        if (resultJson.isJsonObject()) {
            JsonObject resultObject = resultJson.getAsJsonObject();
            result = string(resultObject.has("id") ? resultObject.get("id") : resultObject.get("item"));
            if (resultObject.has("count")) {
                Integer stated = count(resultObject.get("count"));
                if (stated == null) return null;
                count = stated;
            }
            // A result with components is not an unmodified stack: it is never a commodity.
            JsonElement components = resultObject.get("components");
            if (components != null && components.isJsonObject() && components.getAsJsonObject().size() > 0) return null;
        } else result = string(resultJson);
        result = id(result);
        if (result == null) return null;

        List<GuardRecipe.Input> inputs;
        if (object.has("pattern") && object.has("key")) inputs = shaped(object);
        else if (object.has("ingredients")) inputs = list(object.get("ingredients"));
        else if (object.has("ingredient")) inputs = single(object);
        else if (object.has("input_a")) inputs = named(object, "input_a", "input_b");
        else if (object.has("base") && object.has("addition")) inputs = named(object, "template", "base", "addition");
        else return null;
        if (inputs == null || inputs.isEmpty()) return null;
        return new GuardRecipe(id, type, inputs, result, count);
    }

    /** {@code pattern} rows of key letters; a space is an empty cell. */
    private static List<GuardRecipe.Input> shaped(JsonObject object) {
        JsonElement pattern = object.get("pattern");
        JsonElement key = object.get("key");
        if (!pattern.isJsonArray() || !key.isJsonObject()) return null;
        Map<Character, Integer> uses = new LinkedHashMap<>();
        for (JsonElement row : pattern.getAsJsonArray()) {
            String cells = string(row);
            if (cells == null) return null;
            for (int index = 0; index < cells.length(); index++) {
                char cell = cells.charAt(index);
                if (cell != ' ') uses.merge(cell, 1, Integer::sum);
            }
        }
        List<GuardRecipe.Input> inputs = new ArrayList<>(uses.size());
        for (Map.Entry<Character, Integer> use : uses.entrySet()) {
            Counted ingredient = ingredient(key.getAsJsonObject().get(String.valueOf(use.getKey())));
            if (ingredient == null) return null;
            inputs.add(new GuardRecipe.Input(ingredient.options(), ingredient.count() * use.getValue()));
        }
        return inputs;
    }

    /** {@code ingredients}: every element is one ingredient. */
    private static List<GuardRecipe.Input> list(JsonElement ingredients) {
        if (!ingredients.isJsonArray()) return null;
        List<GuardRecipe.Input> inputs = new ArrayList<>(ingredients.getAsJsonArray().size());
        for (JsonElement element : ingredients.getAsJsonArray()) {
            Counted ingredient = ingredient(element);
            if (ingredient == null) return null;
            inputs.add(new GuardRecipe.Input(ingredient.options(), ingredient.count()));
        }
        return inputs;
    }

    /** {@code ingredient}, with {@code input_count}, {@code secondary} and {@code secondary_count} when given. */
    private static List<GuardRecipe.Input> single(JsonObject object) {
        Counted first = ingredient(object.get("ingredient"));
        if (first == null) return null;
        int firstCount = first.count();
        if (object.has("input_count")) {
            Integer stated = count(object.get("input_count"));
            if (stated == null) return null;
            firstCount = stated;
        }
        List<GuardRecipe.Input> inputs = new ArrayList<>(2);
        inputs.add(new GuardRecipe.Input(first.options(), firstCount));
        if (object.has("secondary")) {
            Counted second = ingredient(object.get("secondary"));
            if (second == null) return null;
            int secondCount = second.count();
            if (object.has("secondary_count")) {
                Integer stated = count(object.get("secondary_count"));
                if (stated == null) return null;
                secondCount = stated;
            }
            inputs.add(new GuardRecipe.Input(second.options(), secondCount));
        }
        return inputs;
    }

    /** One ingredient under each of {@code keys}; a key that is absent is simply not an input. */
    private static List<GuardRecipe.Input> named(JsonObject object, String... keys) {
        List<GuardRecipe.Input> inputs = new ArrayList<>(keys.length);
        for (String key : keys) {
            if (!object.has(key)) continue;
            Counted ingredient = ingredient(object.get(key));
            if (ingredient == null) return null;
            inputs.add(new GuardRecipe.Input(ingredient.options(), ingredient.count()));
        }
        return inputs;
    }

    private record Counted(List<String> options, int count) { }

    /** An item id, a {@code #tag}, a list of those, or {@code {"item"|"tag": ..., "count": n}}; null when unreadable. */
    private static Counted ingredient(JsonElement element) {
        if (element == null) return null;
        List<String> options = new ArrayList<>();
        int count = 1;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) return null;
            for (JsonElement alternative : array) {
                String option = option(alternative);
                if (option == null) return null;
                if (!options.contains(option)) options.add(option);
            }
        } else {
            String option = option(element);
            if (option == null) return null;
            options.add(option);
            if (element.isJsonObject() && element.getAsJsonObject().has("count")) {
                Integer stated = count(element.getAsJsonObject().get("count"));
                if (stated == null) return null;
                count = stated;
            }
        }
        return new Counted(options, count);
    }

    /** One alternative as an item id or a {@code #tag}; null for anything else, such as a custom ingredient type. */
    private static String option(JsonElement element) {
        if (element == null) return null;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            // A custom ingredient (components, differences, compounds) is not one item or one tag.
            for (String key : object.keySet())
                if (!key.equals("item") && !key.equals("tag") && !key.equals("count")) return null;
            if (object.has("item") == object.has("tag")) return null;
            String id = id(string(object.get(object.has("item") ? "item" : "tag")));
            if (id == null) return null;
            return object.has("item") ? id : "#" + id;
        }
        String text = string(element);
        if (text == null || text.isEmpty()) return null;
        boolean tag = text.charAt(0) == '#';
        String id = id(tag ? text.substring(1) : text);
        if (id == null) return null;
        return tag ? "#" + id : id;
    }

    /** The id with its namespace ({@code stick} is {@code minecraft:stick}), or null when it is not an id. */
    private static String id(String id) {
        if (id == null || id.isEmpty() || id.length() > 256 || id.charAt(0) == '#' || id.indexOf(' ') >= 0) return null;
        int colon = id.indexOf(':');
        if (colon < 0) return "minecraft:" + id;
        return colon > 0 && colon < id.length() - 1 && id.indexOf(':', colon + 1) < 0 ? id : null;
    }

    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    /** A whole count from 1 to {@link GuardRecipe#MAX_COUNT}, or null. */
    private static Integer count(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
        double value = element.getAsDouble();
        if (value != Math.rint(value) || value < 1.0D || value > GuardRecipe.MAX_COUNT) return null;
        return (int) value;
    }
}
