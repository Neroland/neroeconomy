package za.co.neroland.neroeconomy.commodity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.LoadReport;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Problem;

/**
 * Turns the JSON of a commodity data file into {@link CommodityDefinition}s, strictly: an entry
 * with a missing field, a field of the wrong kind, a number out of range or a field this build
 * does not know is refused, alone — the other entries of its file still load. Pure: it is handed
 * parsed JSON and never touches a file, so the validation is unit tested. The file format is
 * documented on {@link CommodityData}.
 *
 * <p>A refusal is reported as a short fixed word ({@code bad_reference_price}); nothing from the
 * file itself is ever repeated, so a log line about a bad entry can only carry the file's id and
 * the entry's place in it.
 */
public final class CommodityParser {
    /** The spread of an entry that gives none, when the caller has no configuration to ask: 10 %. */
    public static final double DEFAULT_SPREAD_PERCENT = 10.0D;
    /** The most entries one file may carry. */
    public static final int MAX_ENTRIES_PER_FILE = 1024;

    private static final String LIST = "commodities";
    private static final Set<String> FIELDS = Set.of("item", "tag", "reference_price", "npc_daily_supply",
            "npc_daily_demand", "per_account_daily_buy", "per_account_daily_sell", "elasticity", "recovery",
            "spread_percent", "class", "house", "sector", "required", "enabled");

    private CommodityParser() { }

    /**
     * What one entry came to.
     *
     * @param definition the commodity; null when the entry was refused
     * @param problem    why it was refused, as a fixed word; null when it was not
     */
    public record Parsed(CommodityDefinition definition, String problem) {
        public boolean ok() { return definition != null; }
    }

    /**
     * What one file came to.
     *
     * @param definitions the good entries, in file order
     * @param entries     each good entry's place in the file, from 0
     * @param problems    the refused entries; a file refused as a whole is one problem with entry -1
     */
    public record ParsedFile(List<CommodityDefinition> definitions, List<Integer> entries, List<Problem> problems) { }

    private static Parsed refused(String problem) { return new Parsed(null, problem); }

    /**
     * Parses one file: a single entry, or {@code {"commodities": [entry, ...]}}.
     *
     * @param file                 the file's id, taken from where it is
     * @param defaultSpreadPercent the spread of an entry that gives none
     */
    public static ParsedFile parseFile(String file, JsonElement json, double defaultSpreadPercent) {
        List<CommodityDefinition> good = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        if (!CommodityDefinition.validId(file)) {
            problems.add(new Problem(String.valueOf(file), -1, "bad_id"));
        } else if (json == null || !json.isJsonObject()) {
            problems.add(new Problem(file, -1, "not_an_object"));
        } else if (!json.getAsJsonObject().has(LIST)) {
            add(file, 0, json, defaultSpreadPercent, good, places, problems);
        } else {
            JsonObject object = json.getAsJsonObject();
            JsonElement list = object.get(LIST);
            if (object.size() != 1) problems.add(new Problem(file, -1, "unknown_field"));
            else if (!list.isJsonArray()) problems.add(new Problem(file, -1, "bad_commodities"));
            else if (list.getAsJsonArray().size() > MAX_ENTRIES_PER_FILE)
                problems.add(new Problem(file, -1, "too_many_entries"));
            else {
                JsonArray array = list.getAsJsonArray();
                for (int entry = 0; entry < array.size(); entry++)
                    add(file, entry, array.get(entry), defaultSpreadPercent, good, places, problems);
            }
        }
        return new ParsedFile(List.copyOf(good), List.copyOf(places), List.copyOf(problems));
    }

    private static void add(String file, int entry, JsonElement json, double defaultSpreadPercent,
            List<CommodityDefinition> good, List<Integer> places, List<Problem> problems) {
        Parsed parsed;
        try {
            parsed = parse(file, json, defaultSpreadPercent);
        } catch (RuntimeException malformed) {
            parsed = refused("unreadable");
        }
        if (parsed.ok()) {
            good.add(parsed.definition());
            places.add(entry);
        } else problems.add(new Problem(file, entry, parsed.problem()));
    }

    /** Parses one entry. {@code file} is the id of the file it is in. */
    public static Parsed parse(String file, JsonElement json, double defaultSpreadPercent) {
        if (!CommodityDefinition.validId(file)) return refused("bad_id");
        if (json == null || !json.isJsonObject()) return refused("not_an_object");
        JsonObject object = json.getAsJsonObject();
        for (String key : object.keySet()) if (!FIELDS.contains(key)) return refused("unknown_field");

        boolean hasItem = object.has("item");
        boolean hasTag = object.has("tag");
        if (hasItem == hasTag) return refused(hasItem ? "item_and_tag" : "missing_item");
        String id = string(object, hasItem ? "item" : "tag");
        if (id == null || !CommodityDefinition.validId(id)) return refused(hasItem ? "bad_item" : "bad_tag");
        String key = hasItem ? id : "#" + id;

        if (!object.has("reference_price")) return refused("missing_reference_price");
        Long reference = whole(object, "reference_price");
        if (reference == null || reference < CommodityDefinition.MIN_REFERENCE_PRICE
                || reference > CommodityDefinition.MAX_REFERENCE_PRICE) return refused("bad_reference_price");

        CommodityClass commodityClass = CommodityClass.STAPLE;
        if (object.has("class")) {
            commodityClass = CommodityClass.parse(string(object, "class"));
            if (commodityClass == null) return refused("bad_class");
        }

        long supply = 0L;
        if (object.has("npc_daily_supply")) {
            Long value = whole(object, "npc_daily_supply");
            if (value == null || !CommodityDefinition.validUnits(value)) return refused("bad_npc_daily_supply");
            supply = value;
        }
        long demand = 0L;
        if (object.has("npc_daily_demand")) {
            Long value = whole(object, "npc_daily_demand");
            if (value == null || !CommodityDefinition.validUnits(value)) return refused("bad_npc_daily_demand");
            demand = value;
        }
        long accountBuy = commodityClass.defaultAccountCap(supply);
        if (object.has("per_account_daily_buy")) {
            Long value = whole(object, "per_account_daily_buy");
            if (value == null || value < 0L || value > supply) return refused("bad_per_account_daily_buy");
            accountBuy = value;
        }
        long accountSell = commodityClass.defaultAccountCap(demand);
        if (object.has("per_account_daily_sell")) {
            Long value = whole(object, "per_account_daily_sell");
            if (value == null || value < 0L || value > demand) return refused("bad_per_account_daily_sell");
            accountSell = value;
        }

        double elasticity = commodityClass.defaultElasticity();
        if (object.has("elasticity")) {
            Double value = number(object, "elasticity");
            if (value == null || !CommodityDefinition.validElasticity(value)) return refused("bad_elasticity");
            elasticity = value;
        }
        double recovery = commodityClass.defaultRecovery();
        if (object.has("recovery")) {
            Double value = number(object, "recovery");
            if (value == null || !CommodityDefinition.validRecovery(value)) return refused("bad_recovery");
            recovery = value;
        }

        Double spreadPercent = defaultSpreadPercent;
        if (object.has("spread_percent")) {
            spreadPercent = number(object, "spread_percent");
            if (spreadPercent == null) return refused("bad_spread_percent");
        }
        int spreadBps = spreadBps(spreadPercent);
        // The configured default is clamped into range; a file's own number has to be in it.
        if (!CommodityDefinition.validSpreadBps(spreadBps)) {
            if (object.has("spread_percent")) return refused("bad_spread_percent");
            spreadBps = Math.max(CommodityDefinition.MIN_SPREAD_BPS, Math.min(CommodityDefinition.MAX_SPREAD_BPS,
                    spreadBps));
        }

        String house = CommodityDefinition.DEFAULT_HOUSE;
        if (object.has("house")) {
            house = string(object, "house");
            if (!CommodityDefinition.validHouse(house)) return refused("bad_house");
        }
        String sector = "";
        if (object.has("sector")) {
            sector = string(object, "sector");
            if (!CommodityDefinition.validSector(sector)) return refused("bad_sector");
        }
        Boolean required = flag(object, "required", true);
        if (required == null) return refused("bad_required");
        Boolean enabled = flag(object, "enabled", true);
        if (enabled == null) return refused("bad_enabled");

        return new Parsed(new CommodityDefinition(key, file, reference, supply, demand, accountBuy, accountSell,
                elasticity, recovery, spreadBps, commodityClass, house, sector, required, enabled), null);
    }

    /** A spread in percent as hundredths of a percent; out of any range for a number that is not finite. */
    public static int spreadBps(double percent) {
        if (!Double.isFinite(percent) || percent < 0.0D || percent > 100.0D) return -1;
        return (int) Math.round(percent * 100.0D);
    }

    /**
     * Builds the registry from a set of files: {@code compat/<modid>} files of mods that are not
     * loaded are left out, every good entry is resolved against {@code catalog}, and every bad one
     * is recorded in the registry's report — its file, its place and a fixed word. Never throws.
     *
     * @param files                the JSON of each file, by file id; null for a file that could not be read
     * @param defaultSpreadPercent the spread of an entry that gives none
     * @param catalog              the world the entries are resolved against
     */
    public static CommodityRegistry registry(Map<String, JsonElement> files, double defaultSpreadPercent,
            CommodityCatalog catalog) {
        List<CommodityDefinition> definitions = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        int read = 0;
        int gated = 0;
        // In id order, so which entry wins a clash does not depend on how the files were listed.
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            String mod = CommodityDefinition.compatMod(file.getKey());
            boolean loaded;
            try {
                loaded = mod == null || catalog.modLoaded(mod);
            } catch (RuntimeException unknown) {
                loaded = false;
            }
            if (!loaded) {
                gated++;
                continue;
            }
            read++;
            ParsedFile parsed = parseFile(file.getKey(), file.getValue(), defaultSpreadPercent);
            definitions.addAll(parsed.definitions());
            places.addAll(parsed.entries());
            problems.addAll(parsed.problems());
        }
        int[] entries = new int[places.size()];
        for (int index = 0; index < entries.length; index++) entries[index] = places.get(index);
        return CommodityResolver.resolve(definitions, catalog, new LoadReport(read, gated, 0, 0, problems, List.of()),
                entries);
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
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

    /** The whole number under {@code key}; null when it is not a number or has a fraction. */
    private static Long whole(JsonObject object, String key) {
        Double value = number(object, key);
        if (value == null || value != Math.rint(value) || Math.abs(value) > 1.0e15D) return null;
        return value.longValue();
    }

    /** The boolean under {@code key}, {@code otherwise} when absent, null when it is not a boolean. */
    private static Boolean flag(JsonObject object, String key, boolean otherwise) {
        if (!object.has(key)) return otherwise;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) return null;
        return element.getAsBoolean();
    }
}
