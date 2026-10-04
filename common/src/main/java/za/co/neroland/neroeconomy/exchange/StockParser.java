package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import za.co.neroland.neroeconomy.api.StockType;

/**
 * Turns the JSON of a stock data file into a {@link StockDefinition}, strictly: a file with a
 * missing field, a field of the wrong kind, a number out of range or a field this build does not
 * know is refused as a whole. Pure — it is handed parsed JSON and never touches a file — so the
 * validation is unit tested. The file format is documented on {@link StockData}.
 *
 * <p>A refusal is reported as a short fixed word ({@code bad_start_price}); nothing from the file
 * itself is ever repeated, so a log line about a bad file can only carry the file's id.
 */
public final class StockParser {
    private static final Set<String> FIELDS = Set.of("name", "description", "sector", "type", "start_price",
            "volatility", "drift", "dividend_yield", "linked", "enabled");

    private StockParser() { }

    /**
     * What a file came to.
     *
     * @param definition the company; null when the file was refused
     * @param problem    why it was refused, as a fixed word; null when it was not
     */
    public record Parsed(StockDefinition definition, String problem) {
        public boolean ok() { return definition != null; }
    }

    private static Parsed refused(String problem) { return new Parsed(null, problem); }

    /** Parses one file. {@code id} is the company's id, taken from where the file is. */
    public static Parsed parse(String id, JsonElement json) {
        if (!StockDefinition.validId(id)) return refused("bad_id");
        if (json == null || !json.isJsonObject()) return refused("not_an_object");
        JsonObject object = json.getAsJsonObject();
        for (String key : object.keySet()) if (!FIELDS.contains(key)) return refused("unknown_field");

        String name = string(object, "name");
        if (name == null) return refused(object.has("name") ? "bad_name" : "missing_name");
        if (!StockDefinition.validKey(name)) return refused("bad_name");

        String description = name + ".description";
        if (object.has("description")) {
            description = string(object, "description");
            if (description == null || !StockDefinition.validKey(description)) return refused("bad_description");
        } else if (!StockDefinition.validKey(description)) return refused("bad_name");

        String sector = string(object, "sector");
        if (sector == null) return refused(object.has("sector") ? "bad_sector" : "missing_sector");
        if (!StockDefinition.validSector(sector)) return refused("bad_sector");

        String typeKey = string(object, "type");
        if (typeKey == null) return refused(object.has("type") ? "bad_type" : "missing_type");
        StockType type = StockType.parse(typeKey);
        if (type == null) return refused("bad_type");

        if (!object.has("start_price")) return refused("missing_start_price");
        Double start = number(object, "start_price");
        if (start == null || start != Math.rint(start) || start < StockDefinition.MIN_START_PRICE
                || start > StockDefinition.MAX_START_PRICE) return refused("bad_start_price");

        if (!object.has("volatility")) return refused("missing_volatility");
        Double volatility = number(object, "volatility");
        if (volatility == null || !StockDefinition.validVolatility(volatility)) return refused("bad_volatility");

        if (!object.has("drift")) return refused("missing_drift");
        Double drift = number(object, "drift");
        if (drift == null || !StockDefinition.validDrift(drift)) return refused("bad_drift");

        double dividend = 0.0D;
        if (object.has("dividend_yield")) {
            Double value = number(object, "dividend_yield");
            if (value == null || !StockDefinition.validDividendYield(value)) return refused("bad_dividend_yield");
            dividend = value;
        }

        if (!object.has("linked")) return refused("missing_linked");
        JsonElement linkedJson = object.get("linked");
        if (!linkedJson.isJsonArray()) return refused("bad_linked");
        JsonArray array = linkedJson.getAsJsonArray();
        if (array.size() > StockDefinition.MAX_LINKED) return refused("bad_linked");
        List<String> linked = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            String link = string(element);
            if (link == null || !StockDefinition.validLink(link) || linked.contains(link)) return refused("bad_linked");
            linked.add(link);
        }

        boolean enabled = true;
        if (object.has("enabled")) {
            JsonElement value = object.get("enabled");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return refused("bad_enabled");
            enabled = value.getAsBoolean();
        }
        return new Parsed(new StockDefinition(id, name, description, sector, type, start.longValue(), volatility, drift,
                dividend, linked, enabled), null);
    }

    /**
     * Builds the registry from a set of files: every good file is a company, every bad one is
     * reported to {@code warn} once — its id and a fixed word — and skipped. Never throws.
     *
     * @param files the JSON of each file, by company id
     * @param warn  told (id, problem) for each file that was skipped; may be null
     */
    public static StockRegistry registry(Map<String, JsonElement> files, BiConsumer<String, String> warn) {
        List<StockDefinition> good = new ArrayList<>();
        // In id order, so which companies make the cap does not depend on how the files were listed.
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            Parsed parsed;
            try {
                parsed = parse(file.getKey(), file.getValue());
            } catch (RuntimeException malformed) {
                parsed = refused("unreadable");
            }
            if (!parsed.ok()) {
                if (warn != null) warn.accept(file.getKey(), parsed.problem());
                continue;
            }
            if (!parsed.definition().enabled()) continue;
            if (good.size() >= StockRegistry.MAX_LISTED) {
                if (warn != null) warn.accept(file.getKey(), "too_many_stocks");
                continue;
            }
            good.add(parsed.definition());
        }
        return StockRegistry.of(good);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? string(object.get(key)) : null;
    }

    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    /** The number under {@code key}, or null when it is missing, not a number, or not finite. */
    private static Double number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
        double value = element.getAsDouble();
        return Double.isFinite(value) ? value : null;
    }
}
