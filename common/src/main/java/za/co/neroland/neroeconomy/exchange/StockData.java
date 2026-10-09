package za.co.neroland.neroeconomy.exchange;

import java.io.BufferedReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/*
 * =====================================================================================================
 * LISTED COMPANIES — the data-pack format
 * =====================================================================================================
 *
 * Every company on the exchange is one JSON file in a data pack:
 *
 *     data/<namespace>/neroeconomy/stocks/<path>.json        ->  company id  <namespace>:<path>
 *
 * A file in a pack higher in the pack order replaces the whole file of the same id below it. The
 * files are read when a world starts and again on /reload.
 *
 *     {
 *       "name": "stock.neroeconomy.deepcore_mining",
 *       "description": "stock.neroeconomy.deepcore_mining.description",
 *       "sector": "mining",
 *       "type": "steady",
 *       "start_price": 120,
 *       "volatility": 3.6,
 *       "drift": 6.5,
 *       "dividend_yield": 1.5,
 *       "linked": ["#c:ingots/iron", "#c:raw_materials/iron", "minecraft:iron_ore"],
 *       "enabled": true
 *     }
 *
 *   name            required  lang key of the company's name. Lower-case letters, digits, . _ -
 *   description     optional  lang key of a one-line description; default: the name key + ".description"
 *   sector          required  one lower-case word (letters, digits, _), at most 32 characters. Its name is
 *                             the lang key sector.neroeconomy.<sector>. The shipped sectors are mining,
 *                             farming, energy, logistics, space, colonies, creatures and decor.
 *   type            required  "steady" (small moves, pays dividends) or "risky" (large moves either way)
 *   start_price     required  the price the company lists at, in whole credits: 1 to 1000000
 *   volatility      required  how much the random part of the price moves in one economy-day, as a
 *                             percentage (one standard deviation): above 0, at most 50
 *   drift           required  how much the price's anchor grows in one economy-week, as a percentage:
 *                             -50 to 100
 *   dividend_yield  optional  dividend per economy-week as a percentage of the price: 0 to 25; default 0
 *   linked          required  the commodities whose trade moves the company and whose market shocks reach
 *                             it: up to 32 item ids ("minecraft:diamond") and item tag ids with a leading #
 *                             ("#c:gems/diamond"), no entry twice. May be empty. A tag or an item that does
 *                             not exist in this world simply never matches.
 *   enabled         optional  false keeps the file but takes the company off the exchange; default true
 *
 * Validation is strict. A file that is not a JSON object, lacks a required field, has a field of the
 * wrong kind or out of range, or has a field that is not in the list above is skipped as a whole, with
 * ONE warning in the log that names the file's id and a fixed reason word and nothing from the file.
 * Every other file still loads; a bad file can never stop the server.
 *
 * At most 64 companies are listed at a time; further ones, in id order, are skipped with a warning.
 *
 * When a reload no longer has a company's file (or the file says "enabled": false) the company is
 * DELISTED: its price stops at its last value and its holders are paid out at that price. A company that
 * is new starts at its start price. One that comes back carries on from its last price.
 *
 * The index fund and the three Savings Bond terms are not data files: the index is derived from the
 * listed companies (IndexState) and the bond terms are fixed with their rates in the configuration
 * (BondTerm, bondRate1DayPercent / bondRate3DayPercent / bondRate7DayPercent).
 * =====================================================================================================
 */

/**
 * The game's side of the listed companies: reads the stock data files of the running server's
 * data packs into a {@link StockRegistry}, once per server and again on a data-pack reload.
 *
 * <p>Loading follows Neroland Core's own data loaders: the files are read from the server's
 * resource manager the first time they are asked for, cached for that server, and re-read when the
 * loader tells us the data packs were reloaded ({@link #reload}). Each loader's
 * {@code PlatformInfo} makes that call from its own event.
 *
 * <p>Holds company metadata only — nothing about a player.
 */
public final class StockData {
    /** Where the files are, under {@code data/<namespace>/}. */
    public static final String DIRECTORY = "neroeconomy/stocks";
    private static final String SUFFIX = ".json";

    private static WeakReference<MinecraftServer> loadedFor = new WeakReference<>(null);
    private static StockRegistry registry = StockRegistry.UNAVAILABLE;
    /** The files the last load skipped. */
    private static List<Problem> problems = List.of();

    private StockData() { }

    /**
     * One stock file that was skipped.
     *
     * @param id   the company id the file would have had
     * @param word why, as a fixed word; nothing from the file itself
     */
    public record Problem(String id, String word) { }

    /** The stock files the last load for {@code server} skipped; for {@code /eco admin reload-check}. */
    public static synchronized List<Problem> problems(MinecraftServer server) {
        forServer(server);
        return problems;
    }

    /** The listed companies of {@code server}'s data packs; read and cached on first use. */
    public static synchronized StockRegistry forServer(MinecraftServer server) {
        if (server == null) return StockRegistry.UNAVAILABLE;
        if (loadedFor.get() != server) install(server, load(server.getResourceManager()), StockRegistry.UNAVAILABLE);
        return registry;
    }

    /**
     * The data packs of {@code server} were reloaded: the files are read again, and the exchange is
     * told at once, so a company that has gone is delisted and a new one is listed without waiting
     * for the next step. Called by each loader after a {@code /reload}; safe to call at any time.
     */
    public static synchronized void reload(MinecraftServer server) {
        if (server == null) return;
        // Files that cannot be read at all leave the companies as they were.
        install(server, load(server.getResourceManager()), loadedFor.get() == server ? registry : StockRegistry.UNAVAILABLE);
        if (registry.available() && !EconomyHealth.frozen() && ExchangeSettings.fromConfig().enabled())
            EconomyStore.get(server).exchange().reconcile(registry);
    }

    private static void install(MinecraftServer server, StockRegistry loaded, StockRegistry fallback) {
        registry = loaded != null ? loaded : fallback;
        loadedFor = new WeakReference<>(server);
    }

    /** Reads every stock file. @return null when the files could not be listed at all */
    private static StockRegistry load(ResourceManager resources) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try {
            for (Map.Entry<Identifier, Resource> file : resources.listResources(DIRECTORY,
                    location -> location.getPath().endsWith(SUFFIX)).entrySet()) {
                String id = stockId(file.getKey());
                if (id == null) continue;
                try (BufferedReader reader = file.getValue().openAsReader()) {
                    files.put(id, JsonParser.parseReader(reader));
                } catch (Exception unreadable) {
                    // Not JSON, or not readable: the parser refuses a null and it is reported below.
                    files.put(id, null);
                }
            }
        // LinkageError too: a jar built for another Minecraft line fails here (the signature of
        // listResources changed in 26.3), and that must not take the server down.
        } catch (RuntimeException | LinkageError failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The stock data files could not be read ({}); the "
                    + "exchange keeps the companies it had.", failure.getClass().getName());
            return null;
        }
        List<Problem> skipped = new ArrayList<>();
        StockRegistry loaded = StockParser.registry(files, (id, problem) -> {
            skipped.add(new Problem(id, problem));
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Stock data file {} was skipped ({}).", id, problem);
        });
        problems = List.copyOf(skipped);
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] {} companies are listed on the exchange.", loaded.stocks().size());
        return loaded;
    }

    /** {@code <ns>:neroeconomy/stocks/foo.json} → {@code <ns>:foo}; null for anything else. */
    private static String stockId(Identifier file) {
        String path = file.getPath();
        if (!path.startsWith(DIRECTORY + "/") || !path.endsWith(SUFFIX)) return null;
        return file.getNamespace() + ':' + path.substring(DIRECTORY.length() + 1, path.length() - SUFFIX.length());
    }
}
