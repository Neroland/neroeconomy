package za.co.neroland.neroeconomy.house;

import java.io.BufferedReader;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/*
 * =====================================================================================================
 * SERVER BOUNTIES — the data-pack format
 * =====================================================================================================
 *
 *     data/<namespace>/neroeconomy/bounties/<name>.json
 *
 *     { "bounties": [ { "entity": "minecraft:zombie", "quantity": 8, "payout": 40 } ] }
 *
 *   entity    the entity type id of a hostile mob; an id that is not a registered mob of the monster
 *             category is left out (players are never a target)
 *   quantity  kills a bounty asks for: 1 to 64
 *   payout    credits it pays, from the Reserve within systemJobDailyPayoutCap: 1 to 10000
 *
 * The simulation posts at most systemBountiesPerDay of them per economy day. When two files name the
 * same mob, the first in id order wins. A file that is not valid is skipped as a whole with one warning
 * naming the file and a fixed reason word. Read when a world starts and again on /reload.
 * =====================================================================================================
 */

/**
 * The game's side of the server bounties: the bounty files of the running server's data packs,
 * read into a {@link BountyTable} of hostile mobs that exist. Holds data-pack content only.
 */
public final class BountyData {
    public static final String DIRECTORY = "neroeconomy/bounties";
    private static final String SUFFIX = ".json";

    private static WeakReference<MinecraftServer> loadedFor = new WeakReference<>(null);
    private static BountyTable table = BountyTable.EMPTY;

    private BountyData() { }

    /** The bounty table of {@code server}'s data packs; read on first use. */
    public static synchronized BountyTable forServer(MinecraftServer server) {
        if (server == null) return BountyTable.EMPTY;
        if (loadedFor.get() != server) install(server, load(server.getResourceManager()), BountyTable.EMPTY);
        return table;
    }

    /** The data packs were reloaded: the files are read again. */
    public static synchronized void reload(MinecraftServer server) {
        if (server == null) return;
        install(server, load(server.getResourceManager()), loadedFor.get() == server ? table : BountyTable.EMPTY);
    }

    private static void install(MinecraftServer server, BountyTable loaded, BountyTable fallback) {
        table = loaded != null ? loaded : fallback;
        loadedFor = new WeakReference<>(server);
    }

    /** Whether an id names a registered mob of the monster category. */
    static boolean hostile(String entity) {
        Identifier id = Identifier.tryParse(entity);
        if (id == null) return false;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        return type != null && type.getCategory() == MobCategory.MONSTER;
    }

    /** @return null when the files could not be listed at all */
    private static BountyTable load(ResourceManager resources) {
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
            BountyTable loaded = BountyTable.parse(files).filter(BountyData::hostile);
            for (BountyTable.Problem problem : loaded.problems())
                NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Bounty data file {} was skipped ({}).", problem.file(),
                        problem.word());
            return loaded;
        } catch (RuntimeException | LinkageError failure) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The bounty data files could not be read; the bounties stay "
                    + "as they were.");
            return null;
        }
    }
}
