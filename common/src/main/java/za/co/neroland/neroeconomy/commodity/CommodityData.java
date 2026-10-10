package za.co.neroland.neroeconomy.commodity;

import java.io.BufferedReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Problem;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.guard.ArbitrageGuard;
import za.co.neroland.neroeconomy.guard.GuardBonuses;
import za.co.neroland.neroeconomy.guard.GuardReport;
import za.co.neroland.neroeconomy.guard.RecipeReader;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.platform.Services;

/*
 * =====================================================================================================
 * COMMODITIES — the data-pack format
 * =====================================================================================================
 *
 * A commodity is goods the Merchant Houses trade: an item, or every item in a tag, with a reference
 * price and daily limits. Commodities are JSON files in a data pack:
 *
 *     data/<namespace>/neroeconomy/commodities/<path>.json          ->  file id  <namespace>:<path>
 *     data/<namespace>/neroeconomy/commodities/compat/<modid>.json  ->  read ONLY when <modid> is loaded
 *
 * A file in a pack higher in the pack order replaces the whole file of the same id below it. The
 * files are read when a world starts and again on /reload (tags are looked up again then, too).
 *
 * A file holds ONE entry, or a list of them, so one file can carry a whole mod:
 *
 *     { "item": "minecraft:diamond", "reference_price": 400, "npc_daily_demand": 8, "class": "rare" }
 *
 *     { "commodities": [
 *         { "tag": "minecraft:logs", "reference_price": 4, "npc_daily_supply": 2048,
 *           "npc_daily_demand": 512, "class": "farmable", "house": "masons", "sector": "colonies" },
 *         { "item": "minecraft:glass", "reference_price": 3, "npc_daily_supply": 1024,
 *           "npc_daily_demand": 512, "spread_percent": 12.5 }
 *     ] }
 *
 *   item                    one of   an item id. The commodity is that item.
 *   tag                     the two  an item tag id, WITHOUT a leading #. The commodity covers every item
 *                                    in the tag, all at the same price.
 *   reference_price         required whole credits per unit: 1 to 1000000
 *   npc_daily_supply        optional units the Merchant Houses will SELL per economy-day in total:
 *                                    0 to 1000000; default 0 = they never sell it
 *   npc_daily_demand        optional units they will BUY per economy-day in total: 0 to 1000000;
 *                                    default 0 = they never buy it
 *   per_account_daily_buy   optional units ONE account may buy from the Houses per economy-day: 0 to
 *                                    npc_daily_supply; default: a share of npc_daily_supply set by the class
 *   per_account_daily_sell  optional units ONE account may sell to the Houses per economy-day: 0 to
 *                                    npc_daily_demand; default: a share of npc_daily_demand set by the class
 *   elasticity              optional how hard the price reacts to trade with the Houses: 0.5 moves it by
 *                                    50 % across a full day's cap. 0 to 4; default set by the class
 *   recovery                optional the fraction of that pressure that decays per simulation step: above
 *                                    0, at most 1; default set by the class
 *   spread_percent          optional the Houses sell this far ABOVE the reference price and buy this far
 *                                    BELOW it: 2 to 75; default: commoditySpreadPercent in the configuration
 *   class                   optional "staple", "farmable", "crafted" or "rare"; default "staple".
 *                                      staple    elasticity 0.4  recovery 0.04  one account: 1/4 of the day
 *                                      farmable  elasticity 0.9  recovery 0.02  one account: 1/8 of the day
 *                                      crafted   elasticity 0.6  recovery 0.03  one account: 1/4 of the day
 *                                      rare      elasticity 0.8  recovery 0.01  one account: 1/4 of the day
 *   house                   optional the id of the Merchant House that trades it: lower-case letters,
 *                                    digits, _ . -, at most 64; default "general"
 *   sector                  optional the exchange sector its trade counts toward (one lower-case word, as
 *                                    in a stock file); default none
 *   required                optional false = the item or tag is expected to be missing in some worlds, so
 *                                    the entry is skipped WITHOUT a warning when it is. Default true, but
 *                                    always false in a compat/<modid>.json file.
 *   enabled                 optional false keeps the entry but takes the commodity off the market; default true
 *
 * PRICES. All prices are whole credits per unit. The Houses sell at the ASK = reference price plus the
 * spread, rounded up, and buy at the BID = reference price less the spread, rounded down. The ask is
 * always at least 1 above the reference and the bid at least 1 below, so a reference price of 1 has a
 * bid of 0: the Houses sell it but never buy it.
 *
 * WHAT A COMMODITY COVERS. Only UNMODIFIED stacks: a stack with a custom name, enchantments, damage or
 * contents is never a commodity, whatever its item. These can never be a commodity at all, and an
 * entry that names one is refused: this mod's Credit Chip, any item in the tag neroeconomy:untradeable,
 * and any item in the tag neroeconomy:never_commodity (items whose identity lives in their components:
 * potions, enchanted books, written books, filled maps, shulker boxes, bundles, storage cells, pressed
 * disks, resource seeds and the like). A tag entry that contains such items leaves them out.
 *
 * ONE COMMODITY PER ITEM. An entry that names an item always wins over an entry that names a tag the
 * item is in. Between two entries of the same kind the first in file-id order wins and the clash is
 * listed by /eco admin reload-check.
 *
 * COMPAT FILES. commodities/compat/<modid>.json is not read at all unless <modid> is loaded, and each
 * of its entries is silently skipped when its item or tag has no members in this world. The game runs
 * identically with or without any of those mods.
 *
 * VALIDATION is strict. An entry that is not a JSON object, lacks a required field, has a field of the
 * wrong kind or out of range, or has a field that is not in the list above is skipped, with ONE
 * warning in the log that names the file's id, the entry's place in it and a fixed reason word, and
 * nothing from the file. Every other entry still loads; a bad file can never stop the server.
 *
 * At most 4096 commodities are loaded and at most 1024 entries are read from one file.
 *
 * THE ARBITRAGE GUARD runs after every load against the server's recipes (guard.ArbitrageGuard). A
 * commodity that could be bought from the Houses, crafted and sold back at a profit has its NPC buy
 * side switched off until a reload passes; ingredients that craft into a Credit Chip for less than it
 * redeems for have their NPC sell side switched off. Code-side bonuses the guard must know about are
 * data too: data/<namespace>/neroeconomy/guard/<path>.json (see guard.GuardBonuses).
 * =====================================================================================================
 */

/**
 * The game's side of the commodities: reads the commodity data files of the running server's data
 * packs into a {@link CommodityRegistry}, once per server and again on a data-pack reload, runs the
 * arbitrage guard over them and the server's recipes, and hands out the registry with the guard's
 * verdict applied.
 *
 * <p>Loading mirrors {@code exchange.StockData}: read on first use, cached for that server, read
 * again when the loader says the data packs were reloaded ({@link #reload}). Tags are resolved to
 * item ids at that moment, which is after the game has bound them, so a lookup by item id is one
 * map read.
 *
 * <p>Holds data-pack content only — nothing about a player.
 */
public final class CommodityData {
    /** Where the commodity files are, under {@code data/<namespace>/}. */
    public static final String DIRECTORY = "neroeconomy/commodities";
    /** Where the guard's code-side bonus files are, under {@code data/<namespace>/}. */
    public static final String GUARD_DIRECTORY = "neroeconomy/guard";
    private static final String RECIPE_DIRECTORY = "recipe";
    private static final String SUFFIX = ".json";

    /** Items whose identity lives in their components: never a commodity, whatever a data file says. */
    public static final TagKey<Item> NEVER_COMMODITY_TAG = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "never_commodity"));
    /** This mod's Credit Chip: money, not goods. */
    public static final String CREDIT_CHIP_ID = NeroEconomyCommon.MOD_ID + ":credit_chip";

    private static WeakReference<MinecraftServer> loadedFor = new WeakReference<>(null);
    /** The commodities as the files give them. */
    private static CommodityRegistry base = CommodityRegistry.UNAVAILABLE;
    /** The same with the guard's verdict applied: what everything else is handed. */
    private static CommodityRegistry guarded = CommodityRegistry.UNAVAILABLE;
    private static Check lastCheck = Check.NONE;
    /** The recipes and bonuses the guard last ran over, kept for a check at other prices; null before any. */
    private static RecipeReader.Read lastRecipes;
    private static GuardBonuses lastBonuses = GuardBonuses.NONE;

    private CommodityData() { }

    /**
     * The outcome of one run of the arbitrage guard in the game.
     *
     * @param report         what the guard found
     * @param guardProblems  guard data files or entries that were skipped
     * @param recipesReadable false when the server's recipes could not be listed at all; every NPC
     *                       buy side is then off, because nothing could be proved
     */
    public record Check(GuardReport report, List<GuardBonuses.Problem> guardProblems, boolean recipesReadable) {
        public static final Check NONE = new Check(GuardReport.NONE, List.of(), true);

        public Check {
            guardProblems = List.copyOf(guardProblems);
        }
    }

    /**
     * The commodities of {@code server}'s data packs, with the arbitrage guard's verdict applied;
     * read, resolved and checked on first use.
     */
    public static synchronized CommodityRegistry forServer(MinecraftServer server) {
        if (server == null) return CommodityRegistry.UNAVAILABLE;
        if (loadedFor.get() != server) install(server, load(server.getResourceManager()), CommodityRegistry.UNAVAILABLE);
        return guarded;
    }

    /**
     * The data packs of {@code server} were reloaded: the files are read again, tags are looked up
     * again and the guard runs again. Called by each loader after a {@code /reload}; safe to call
     * at any time.
     */
    public static synchronized void reload(MinecraftServer server) {
        if (server == null) return;
        // Files that cannot be read at all leave the commodities as they were.
        install(server, load(server.getResourceManager()), loadedFor.get() == server ? base : CommodityRegistry.UNAVAILABLE);
    }

    /**
     * Runs the arbitrage guard again over the loaded commodities and the server's live recipes,
     * applies what it finds, and returns it: {@code /eco admin reload-check}.
     */
    public static synchronized Check check(MinecraftServer server) {
        if (server == null) return Check.NONE;
        forServer(server);
        guard(server, false);
        return lastCheck;
    }

    /** What the guard found when it last ran for {@code server}; runs it when it has not yet. */
    public static synchronized Check lastCheck(MinecraftServer server) {
        if (server == null) return Check.NONE;
        forServer(server);
        return lastCheck;
    }

    /**
     * Runs the arbitrage guard again over the loaded commodities and the recipes it last read, at
     * other prices — the Merchant Houses' drifted reference prices — without changing its verdict.
     *
     * @return the keys of the commodities whose NPC buy side would have to be off at those prices;
     *         empty when the guard has not been able to read the recipes (every buy side is off then anyway)
     */
    public static synchronized Set<String> buyOffAt(MinecraftServer server, ArbitrageGuard.Quotes quotes) {
        if (server == null || quotes == null) return Set.of();
        forServer(server);
        RecipeReader.Read recipes = lastRecipes;
        if (!guarded.available() || recipes == null) return Set.of();
        Map<String, Long> redeemable = EconomyConfig.CREDIT_CHIP_DAILY_LIMIT.get() > 0
                ? Map.of(CREDIT_CHIP_ID, EconomyConfig.CREDIT_CHIP_VALUE.get()) : Map.of();
        return ArbitrageGuard.check(new ArbitrageGuard.Input(guarded, quotes, recipes.recipes(), recipes.skipped(),
                lastBonuses, GAME::tagItems, redeemable, EconomyConfig.ARBITRAGE_GUARD_DEPTH.get())).buyOff();
    }

    private static void install(MinecraftServer server, CommodityRegistry loaded, CommodityRegistry fallback) {
        base = loaded != null ? loaded : fallback;
        loadedFor = new WeakReference<>(server);
        guard(server, true);
    }

    // --- the commodity files ---------------------------------------------------------------------

    /** Reads every commodity file. @return null when the files could not be listed at all */
    private static CommodityRegistry load(ResourceManager resources) {
        Map<String, JsonElement> files = read(resources, DIRECTORY);
        if (files == null) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The commodity data files could not be read; the "
                    + "Merchant Houses keep the commodities they had.");
            return null;
        }
        CommodityRegistry loaded;
        try {
            loaded = CommodityParser.registry(files, CommodityDefaults.GAME.files(),
                    EconomyConfig.COMMODITY_SPREAD_PERCENT.get(), GAME);
        } catch (RuntimeException | LinkageError failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The commodity data files could not be resolved ({}); the "
                    + "Merchant Houses keep the commodities they had.", failure.getClass().getName());
            return null;
        }
        for (Problem problem : loaded.report().problems()) {
            if (problem.entry() < 0)
                NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Commodity data file {} was skipped ({}).", problem.file(),
                        problem.word());
            else if ("members_excluded".equals(problem.word()))
                NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Commodity data file {} entry {} names a tag with items "
                        + "that can never be a commodity; they were left out.", problem.file(), problem.entry());
            else NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Commodity data file {} entry {} was skipped ({}).",
                    problem.file(), problem.entry(), problem.word());
        }
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] {} commodities are loaded.", loaded.commodities().size());
        return loaded;
    }

    /**
     * The JSON of every file under {@code directory}, by {@code <namespace>:<path>} id; a file that
     * is not JSON maps to null. @return null when the files could not be listed at all
     */
    private static Map<String, JsonElement> read(ResourceManager resources, String directory) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try {
            for (Map.Entry<Identifier, Resource> file : resources.listResources(directory,
                    location -> location.getPath().endsWith(SUFFIX)).entrySet()) {
                String path = file.getKey().getPath();
                if (!path.startsWith(directory + "/") || !path.endsWith(SUFFIX)) continue;
                String id = file.getKey().getNamespace() + ':'
                        + path.substring(directory.length() + 1, path.length() - SUFFIX.length());
                try (BufferedReader reader = file.getValue().openAsReader()) {
                    files.put(id, JsonParser.parseReader(reader));
                } catch (Exception unreadable) {
                    // Not JSON, or not readable: the parsers refuse a null and report it.
                    files.put(id, null);
                }
            }
        // LinkageError too: a jar built for another Minecraft line fails here, and that must not
        // take the server down.
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
        return files;
    }

    // --- the guard -------------------------------------------------------------------------------

    /** Runs the guard over {@link #base} and the live recipes and applies the verdict. */
    private static void guard(MinecraftServer server, boolean afterLoad) {
        if (!base.available()) {
            guarded = base;
            lastCheck = Check.NONE;
            return;
        }
        Map<String, JsonElement> recipeFiles = read(server.getResourceManager(), RECIPE_DIRECTORY);
        Map<String, JsonElement> guardFiles = read(server.getResourceManager(), GUARD_DIRECTORY);
        if (recipeFiles == null) {
            // Nothing can be proved without the recipes, so the Houses buy nothing until it can be.
            Set<String> every = new TreeSet<>();
            for (CommodityDefinition commodity : base.commodities()) every.add(commodity.key());
            guarded = base.guarded(every, Set.of());
            lastCheck = new Check(new GuardReport(List.of(), every, Set.of(), base.commodities().size(), 0, 0, 0, 0),
                    List.of(), false);
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The recipes could not be read, so the arbitrage guard could "
                    + "not run: the Merchant Houses buy nothing until a reload succeeds.");
            return;
        }
        GuardBonuses bonuses = guardFiles == null ? GuardBonuses.NONE : GuardBonuses.parse(guardFiles);
        if (afterLoad) for (GuardBonuses.Problem problem : bonuses.problems())
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Guard data file {} has a part that was skipped ({}).",
                    problem.file(), problem.word());
        RecipeReader.Read recipes = RecipeReader.readAll(recipeFiles);
        lastRecipes = recipes;
        lastBonuses = bonuses;
        Map<String, Long> redeemable = EconomyConfig.CREDIT_CHIP_DAILY_LIMIT.get() > 0
                ? Map.of(CREDIT_CHIP_ID, EconomyConfig.CREDIT_CHIP_VALUE.get()) : Map.of();
        GuardReport report = ArbitrageGuard.check(new ArbitrageGuard.Input(base, ArbitrageGuard.Quotes.REFERENCE,
                recipes.recipes(), recipes.skipped(), bonuses, GAME::tagItems, redeemable,
                EconomyConfig.ARBITRAGE_GUARD_DEPTH.get()));
        guarded = base.guarded(report.buyOff(), report.sellOff());
        lastCheck = new Check(report, bonuses.problems(), true);
        // Counts only: what failed is listed, to an operator who asks, by /eco admin reload-check.
        if (!report.passed())
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The arbitrage guard found {} problems in the commodity data. "
                    + "The Merchant Houses stop buying {} commodities and stop selling {} until the data is fixed; "
                    + "/eco admin reload-check lists them.", report.failures().size(), report.buyOff().size(),
                    report.sellOff().size());
        else if (afterLoad)
            NeroEconomyCommon.LOGGER.info("[NeroEconomy] The arbitrage guard passed: {} commodities, {} recipes read, "
                    + "{} skipped, {} chains checked.", report.commodities(), report.recipesRead(),
                    report.recipesSkipped(), report.chainsChecked());
    }

    // --- the world, as the resolver and the guard see it -----------------------------------------

    private static final CommodityCatalog GAME = new CommodityCatalog() {
        @Override public boolean modLoaded(String modId) { return Services.PLATFORM.isModLoaded(modId); }

        @Override public boolean itemKnown(String item) {
            Identifier id = item == null ? null : Identifier.tryParse(item);
            Item value = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            return value != null && value != Items.AIR;
        }

        @Override public Collection<String> tagItems(String tag) {
            Identifier id = tag == null ? null : Identifier.tryParse(tag);
            if (id == null) return List.of();
            try {
                List<String> members = new ArrayList<>();
                BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id)).ifPresent(named -> {
                    for (Holder<Item> holder : named) members.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString());
                });
                return members;
            } catch (RuntimeException unbound) {
                return List.of();
            }
        }

        @Override public String refusal(String item) {
            if (CREDIT_CHIP_ID.equals(item)) return CREDIT_CHIP;
            Identifier id = item == null ? null : Identifier.tryParse(item);
            Holder<Item> holder = id == null ? null : BuiltInRegistries.ITEM.get(id).orElse(null);
            if (holder == null) return null;
            if (holder.is(MarketSavedData.UNTRADEABLE)) return UNTRADEABLE;
            // Another mod excluded it in code: its value lives in its components.
            if (CommodityDefaults.GAME.excluded(item)) return NEVER_COMMODITY;
            return holder.is(NEVER_COMMODITY_TAG) ? NEVER_COMMODITY : null;
        }
    };
}
