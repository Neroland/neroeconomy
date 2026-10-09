package za.co.neroland.neroeconomy.house;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;

/**
 * Reads Merchant House data files into {@link HouseDefinition}s. Pure: it is handed the JSON of
 * each file by id, so the game ({@link HouseData}) and the tests use the very same rules. The
 * format is documented on {@link HouseData}. Strict: a file with an unknown field, a field of the
 * wrong kind or a value out of range is skipped as a whole, with one fixed reason word.
 */
public final class HouseParser {
    private static final Set<String> FIELDS = Set.of("name", "trades", "sectors", "working_capital",
            "restock_per_day", "enabled");

    private HouseParser() { }

    /**
     * What one file came to.
     *
     * @param house   the House; null when the file was refused or is disabled
     * @param problem why it was refused, as a fixed word; null when it was not
     */
    public record Parsed(HouseDefinition house, String problem) {
        public boolean ok() { return house != null; }
    }

    private static Parsed refused(String problem) { return new Parsed(null, problem); }

    /**
     * Parses one file. {@code file} is its id ({@code <namespace>:<path>}); the House's id is the
     * path. A file with {@code "enabled": false} is neither a House nor a problem.
     */
    public static Parsed parse(String file, JsonElement json) {
        if (!CommodityDefinition.validId(file)) return refused("bad_id");
        String id = file.substring(file.indexOf(':') + 1);
        if (!CommodityDefinition.validHouse(id)) return refused("bad_house_id");
        if (json == null || !json.isJsonObject()) return refused("not_an_object");
        JsonObject object = json.getAsJsonObject();
        for (String key : object.keySet()) if (!FIELDS.contains(key)) return refused("unknown_field");
        try {
            Boolean enabled = flag(object, "enabled");
            if (enabled == null) return refused("bad_enabled");
            if (!enabled) return new Parsed(null, null);
            String name = "house." + file.substring(0, file.indexOf(':')) + "." + id;
            if (object.has("name")) {
                name = string(object, "name");
                if (name == null || name.isEmpty() || name.length() > 128) return refused("bad_name");
            }
            List<String> trades = list(object, "trades", CommodityDefinition::validHouse);
            if (trades == null) return refused("bad_trades");
            List<String> sectors = list(object, "sectors", CommodityDefinition::validSector);
            if (sectors == null) return refused("bad_sectors");
            long capital = HouseDefinition.DEFAULT_WORKING_CAPITAL;
            if (object.has("working_capital")) {
                Long value = whole(object, "working_capital");
                if (value == null || value < 0L || value > HouseDefinition.MAX_WORKING_CAPITAL)
                    return refused("bad_working_capital");
                capital = value;
            }
            double restock = HouseDefinition.DEFAULT_RESTOCK_PER_DAY;
            if (object.has("restock_per_day")) {
                Double value = number(object, "restock_per_day");
                if (value == null || !HouseDefinition.validRestock(value)) return refused("bad_restock_per_day");
                restock = value;
            }
            return new Parsed(new HouseDefinition(id, file, name, trades, sectors, capital, restock), null);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException malformed) {
            return refused("unreadable");
        }
    }

    /**
     * Builds the registry from a set of files, in id order: the first file of an id wins, and every
     * refused file is recorded with a fixed word. Never throws.
     *
     * @param files the JSON of each file, by file id; null for a file that could not be read
     */
    public static HouseRegistry registry(Map<String, JsonElement> files) {
        List<HouseDefinition> houses = new ArrayList<>();
        List<HouseRegistry.Problem> problems = new ArrayList<>();
        if (files != null) for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            Parsed parsed = parse(file.getKey(), file.getValue());
            if (parsed.ok()) houses.add(parsed.house());
            else if (parsed.problem() != null) problems.add(new HouseRegistry.Problem(file.getKey(), parsed.problem()));
        }
        return HouseRegistry.build(houses, problems);
    }

    // --- fields ------------------------------------------------------------------------------

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }

    private static Double number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) return null;
        double number = primitive.getAsDouble();
        return Double.isFinite(number) ? number : null;
    }

    private static Long whole(JsonObject object, String field) {
        Double number = number(object, field);
        if (number == null || number != Math.rint(number) || Math.abs(number) > 9.0E15D) return null;
        return number.longValue();
    }

    private static Boolean flag(JsonObject object, String field) {
        if (!object.has(field)) return Boolean.TRUE;
        JsonElement value = object.get(field);
        return value instanceof JsonPrimitive primitive && primitive.isBoolean() ? primitive.getAsBoolean() : null;
    }

    /** A list of ids, each passing {@code valid}; empty when absent; null when malformed. */
    private static List<String> list(JsonObject object, String field, java.util.function.Predicate<String> valid) {
        if (!object.has(field)) return List.of();
        JsonElement value = object.get(field);
        if (!(value instanceof JsonArray array) || array.size() > HouseDefinition.MAX_LIST) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement element : array) {
            if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) return null;
            String id = primitive.getAsString();
            if (!valid.test(id)) return null;
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }
}
