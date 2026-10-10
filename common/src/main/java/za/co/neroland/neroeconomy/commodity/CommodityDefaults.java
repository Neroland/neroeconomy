package za.co.neroland.neroeconomy.commodity;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import za.co.neroland.neroeconomy.api.CommodityApi;
import za.co.neroland.neroeconomy.platform.Services;
import za.co.neroland.neroeconomy.sibling.CallerRegistry;
import za.co.neroland.neroeconomy.sibling.SiblingRules;

/**
 * Commodity defaults and exclusions other mods register in code ({@link CommodityApi#registerDefault},
 * {@link CommodityApi#exclude}), for what they want without shipping a data file.
 *
 * <ul>
 *   <li>A default is one data entry built from a {@link CommodityApi.CommoditySpec} and checked by
 *       {@link CommodityParser} exactly as a file entry is. It is read as the file
 *       {@code code:<modid>} (the source {@code /eco admin reload-check} names), after every data
 *       file, and a data-pack entry for the same key always wins. It loads only when its items
 *       resolve and goes through the arbitrage guard like data.</li>
 *   <li>A key is an item in the caller's own namespace, or a tag {@code #<caller>:...} or
 *       {@code #c:...}; an exclusion is an item in the caller's namespace. An excluded unmodified
 *       item is never a commodity, whatever the data says.</li>
 *   <li>The caller must be a loaded mod; at most {@link #MAX_PER_CALLER} of each per caller;
 *       registration closes when the first server starts.</li>
 * </ul>
 */
public final class CommodityDefaults {
    /** The most defaults, and the most exclusions, one caller may register. */
    public static final int MAX_PER_CALLER = 256;
    /** The file id prefix code defaults are read under. */
    public static final String SOURCE_NAMESPACE = "code";

    /** The registry of the running game: closes with the cross-mod API's claims, when the first server starts. */
    public static final CommodityDefaults GAME = new CommodityDefaults(modId -> Services.PLATFORM.isModLoaded(modId),
            () -> CallerRegistry.GAME.closed());

    private final Predicate<String> loaded;
    private final java.util.function.BooleanSupplier closed;
    private final Map<String, Map<String, JsonObject>> defaults = new TreeMap<>();
    private final Map<String, Set<String>> exclusions = new TreeMap<>();

    public CommodityDefaults(Predicate<String> loaded, java.util.function.BooleanSupplier closed) {
        this.loaded = loaded;
        this.closed = closed;
    }

    public synchronized CommodityApi.Registration register(String caller, CommodityApi.CommoditySpec spec) {
        CommodityApi.Registration refused = callerCheck(caller);
        if (refused != null) return refused;
        if (spec == null || !CommodityDefinition.validKey(spec.key())) return CommodityApi.Registration.BAD_KEY;
        if (!ownKey(caller, spec.key(), true)) return CommodityApi.Registration.FOREIGN_KEY;
        Map<String, JsonObject> mine = defaults.computeIfAbsent(caller, ignored -> new LinkedHashMap<>());
        if (mine.containsKey(spec.key())) return CommodityApi.Registration.INVALID;
        if (mine.size() >= MAX_PER_CALLER) return CommodityApi.Registration.TOO_MANY;
        JsonObject entry = entry(spec);
        if (!CommodityParser.parse(sourceOf(caller), entry, CommodityParser.DEFAULT_SPREAD_PERCENT).ok())
            return CommodityApi.Registration.INVALID;
        mine.put(spec.key(), entry);
        return CommodityApi.Registration.ACCEPTED;
    }

    public synchronized CommodityApi.Registration exclude(String caller, String itemId) {
        CommodityApi.Registration refused = callerCheck(caller);
        if (refused != null) return refused;
        if (itemId == null || !CommodityDefinition.validId(itemId)) return CommodityApi.Registration.BAD_KEY;
        if (!ownKey(caller, itemId, false)) return CommodityApi.Registration.FOREIGN_KEY;
        Set<String> mine = exclusions.computeIfAbsent(caller, ignored -> new LinkedHashSet<>());
        if (!mine.contains(itemId) && mine.size() >= MAX_PER_CALLER) return CommodityApi.Registration.TOO_MANY;
        mine.add(itemId);
        return CommodityApi.Registration.ACCEPTED;
    }

    private CommodityApi.Registration callerCheck(String caller) {
        if (!SiblingRules.validModId(caller) || SiblingRules.RESERVED.contains(caller))
            return CommodityApi.Registration.BAD_CALLER;
        if (closed.getAsBoolean()) return CommodityApi.Registration.TOO_LATE;
        boolean known;
        try {
            known = loaded.test(caller);
        } catch (RuntimeException unknown) {
            known = false;
        }
        return known ? null : CommodityApi.Registration.BAD_CALLER;
    }

    /** An item in the caller's namespace; with {@code tags}, also {@code #<caller>:...} or {@code #c:...}. */
    static boolean ownKey(String caller, String key, boolean tags) {
        boolean tag = key.startsWith("#");
        if (tag && !tags) return false;
        String id = tag ? key.substring(1) : key;
        String namespace = id.substring(0, id.indexOf(':'));
        return namespace.equals(caller) || (tag && namespace.equals("c"));
    }

    /** The data entry a spec stands for. Null fields are left out, so the class's defaults apply. */
    static JsonObject entry(CommodityApi.CommoditySpec spec) {
        JsonObject entry = new JsonObject();
        boolean tag = spec.key().startsWith("#");
        entry.addProperty(tag ? "tag" : "item", tag ? spec.key().substring(1) : spec.key());
        entry.addProperty("reference_price", spec.referencePrice());
        if (spec.commodityClass() != null) entry.addProperty("class", spec.commodityClass().key());
        if (spec.npcDailySupply() != null) entry.addProperty("npc_daily_supply", spec.npcDailySupply());
        if (spec.npcDailyDemand() != null) entry.addProperty("npc_daily_demand", spec.npcDailyDemand());
        if (spec.perAccountDailyBuy() != null) entry.addProperty("per_account_daily_buy", spec.perAccountDailyBuy());
        if (spec.perAccountDailySell() != null) entry.addProperty("per_account_daily_sell", spec.perAccountDailySell());
        if (spec.elasticity() != null) entry.addProperty("elasticity", spec.elasticity());
        if (spec.recovery() != null) entry.addProperty("recovery", spec.recovery());
        if (spec.house() != null) entry.addProperty("house", spec.house());
        if (spec.sector() != null) entry.addProperty("sector", spec.sector());
        // A default expects to find nothing in some worlds: an unknown item or an empty tag is skipped quietly.
        entry.addProperty("required", false);
        return entry;
    }

    /** The file id a caller's defaults are read under: {@code code:<modid>}. */
    public static String sourceOf(String caller) { return SOURCE_NAMESPACE + ':' + caller; }

    /** Every caller's defaults as data files, by file id, for {@link CommodityParser#registry}. */
    public synchronized Map<String, JsonElement> files() {
        Map<String, JsonElement> files = new TreeMap<>();
        defaults.forEach((caller, entries) -> {
            if (entries.isEmpty()) return;
            JsonArray list = new JsonArray();
            entries.values().forEach(entry -> list.add(entry.deepCopy()));
            JsonObject file = new JsonObject();
            file.add("commodities", list);
            files.put(sourceOf(caller), file);
        });
        return files;
    }

    /** Whether some mod excluded {@code item}. */
    public synchronized boolean excluded(String item) {
        if (item == null) return false;
        for (Set<String> mine : exclusions.values()) if (mine.contains(item)) return true;
        return false;
    }
}
