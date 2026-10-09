package za.co.neroland.neroeconomy.house;

import java.io.BufferedReader;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/*
 * =====================================================================================================
 * MERCHANT HOUSES — the data-pack format
 * =====================================================================================================
 *
 * A Merchant House is one of the autonomous traders that sell commodities from a slowly restocked
 * stock and buy them with working capital funded from the Reserve. Each House is one JSON file:
 *
 *     data/<namespace>/neroeconomy/houses/<id>.json      ->  the House <id>
 *
 *     { "name": "house.neroeconomy.smiths", "working_capital": 8000, "restock_per_day": 1.0,
 *       "trades": ["metals"], "sectors": ["mining"] }
 *
 *   name              optional the lang key of the House's display name; default house.<namespace>.<id>
 *   working_capital   optional credits the House keeps to buy with: 0 to 1000000000; default 5000. It is
 *                              topped up from the Reserve toward this every simulation step, within
 *                              npcDailyBudget, and whatever it holds above this is swept back to the Reserve.
 *   restock_per_day   optional how much of each commodity's npc_daily_supply the House restocks per
 *                              economy-day: 0 to 24; default 1.0 (a whole day's supply over one day)
 *   trades            optional further commodity "house" ids this House trades, besides its own id
 *   sectors           optional sectors whose commodities this House trades when no House is named for them
 *   enabled           optional false leaves the House out; default true
 *
 * WHICH HOUSE TRADES A COMMODITY. The House whose id the commodity's "house" field names; else the first
 * House, in id order, that lists that id under "trades"; else the first that lists the commodity's sector
 * under "sectors"; else "general". There is always a "general" House: when no data pack defines one, a
 * built-in one with 5000 credits of working capital stands in.
 *
 * A file in a pack higher in the pack order replaces the file of the same id below it. Two files of
 * different namespaces with the same path name the same House: the first in id order wins and the other
 * is listed by /eco admin reload-check. At most 64 Houses are loaded. A file that is not valid is skipped
 * as a whole with one warning that names the file and a fixed reason word; a bad file never stops the
 * server. The files are read when a world starts and again on /reload.
 * =====================================================================================================
 */

/**
 * The game's side of the Merchant Houses: reads the House data files of the running server's data
 * packs into a {@link HouseRegistry}, once per server and again on a data-pack reload. Mirrors
 * {@code commodity.CommodityData}. Holds data-pack content only — nothing about a player.
 */
public final class HouseData {
    /** Where the House files are, under {@code data/<namespace>/}. */
    public static final String DIRECTORY = "neroeconomy/houses";
    private static final String SUFFIX = ".json";

    private static WeakReference<MinecraftServer> loadedFor = new WeakReference<>(null);
    private static HouseRegistry houses = HouseRegistry.DEFAULT;

    private HouseData() { }

    /** The Houses of {@code server}'s data packs; read on first use. */
    public static synchronized HouseRegistry forServer(MinecraftServer server) {
        if (server == null) return HouseRegistry.DEFAULT;
        if (loadedFor.get() != server) install(server, load(server.getResourceManager()), HouseRegistry.DEFAULT);
        return houses;
    }

    /** The data packs of {@code server} were reloaded: the files are read again. Safe to call at any time. */
    public static synchronized void reload(MinecraftServer server) {
        if (server == null) return;
        // Files that cannot be read at all leave the Houses as they were.
        install(server, load(server.getResourceManager()), loadedFor.get() == server ? houses : HouseRegistry.DEFAULT);
        // The server's bounty table is read with the Houses, on the same reload.
        BountyData.reload(server);
    }

    private static void install(MinecraftServer server, HouseRegistry loaded, HouseRegistry fallback) {
        houses = loaded != null ? loaded : fallback;
        loadedFor = new WeakReference<>(server);
    }

    /** @return null when the files could not be listed at all */
    private static HouseRegistry load(ResourceManager resources) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try {
            for (Map.Entry<Identifier, Resource> file : resources.listResources(DIRECTORY,
                    location -> location.getPath().endsWith(SUFFIX)).entrySet()) {
                String path = file.getKey().getPath();
                if (!path.startsWith(DIRECTORY + "/") || !path.endsWith(SUFFIX)) continue;
                String id = file.getKey().getNamespace() + ':'
                        + path.substring(DIRECTORY.length() + 1, path.length() - SUFFIX.length());
                try (BufferedReader reader = file.getValue().openAsReader()) {
                    files.put(id, JsonParser.parseReader(reader));
                } catch (Exception unreadable) {
                    files.put(id, null);
                }
            }
        // LinkageError too: a jar built for another Minecraft line fails here, and that must not
        // take the server down.
        } catch (RuntimeException | LinkageError failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The Merchant House data files could not be read; the "
                    + "Houses stay as they were.");
            return null;
        }
        HouseRegistry loaded = HouseParser.registry(files);
        for (HouseRegistry.Problem problem : loaded.problems())
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Merchant House data file {} was skipped ({}).", problem.file(),
                    problem.word());
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] {} Merchant Houses are loaded.", loaded.houses().size());
        return loaded;
    }
}
