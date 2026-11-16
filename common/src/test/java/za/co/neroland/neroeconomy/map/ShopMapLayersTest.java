package za.co.neroland.neroeconomy.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.nerolandcore.map.MapBounds;
import za.co.neroland.nerolandcore.map.MapLayerConformance;
import za.co.neroland.nerolandcore.map.MapLayerRegistry;
import za.co.neroland.nerolandcore.map.MapQueryResult;
import za.co.neroland.nerolandcore.map.MapViewer;

/**
 * {@code my_shops} against Core's {@link MapLayerConformance}, plus G6 parity: for owner, stranger, third-player and
 * erased-owner fixtures the map returns exactly the shops the store's owner rule gives that UUID in that dimension
 * (the same {@code owner.equals(player)} match the subject-access export uses).
 */
class ShopMapLayersTest {

    static final ResourceKey<Level> HOME = dim("home_test");
    static final ResourceKey<Level> MOON = dim("moon_test");
    static final ResourceKey<Level> OTHER = dim("other_test");
    static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID THIRD = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final UUID ERASED = UUID.fromString("00000000-0000-4000-8000-000000000004");

    static ResourceKey<Level> dim(String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("neroeconomy_test", path));
    }

    static String key(ResourceKey<Level> dim, BlockPos pos) {
        return dim.identifier() + "|" + pos.asLong();
    }

    /** Two own shops at home, one on another world, a third player's beside them, and an erased player's. */
    static Map<String, UUID> rows(boolean erase) {
        Map<String, UUID> rows = new LinkedHashMap<>();
        rows.put(key(HOME, new BlockPos(3, 64, 4)), OWNER);
        rows.put(key(HOME, new BlockPos(-40, 70, 12)), OWNER);
        rows.put(key(OTHER, new BlockPos(0, 64, 0)), OWNER);
        rows.put(key(HOME, new BlockPos(6, 64, 1)), THIRD);
        rows.put(dim("home_test").identifier() + "|not-a-number", OWNER);
        if (!erase) {
            rows.put(key(HOME, new BlockPos(9, 64, 9)), ERASED);
        }
        return rows;
    }

    /** The store's owner rule ({@code locationsOf}) over plain rows. */
    static ShopMapLayers.ShopIndex index(Map<String, UUID> rows) {
        return owner -> {
            List<String> out = new ArrayList<>();
            rows.forEach((location, who) -> {
                if (who.equals(owner)) {
                    out.add(location);
                }
            });
            return out;
        };
    }

    static MapViewer at(UUID id, int x, int z) {
        return MapViewer.forTest(id, null, HOME, new BlockPos(x, 64, z));
    }

    @Test
    void conformance() {
        ShopMapLayers.ShopIndex index = index(rows(true));
        MapLayerConformance.create(new ShopMapLayers(server -> index))
                .viewers(at(OWNER, 0, 0), at(STRANGER, 5, 0))
                .dimensions(HOME, MOON)
                .bounds(MapBounds.around(0, 0, 512))
                .farBounds(MapBounds.around(20_000, 20_000, 256))
                .forbiddenText("Trudy", "Sam")
                .forbiddenIds(THIRD, ERASED)
                .expectEntitledSees(ShopMapLayers.MY_SHOPS, 2)
                .verify();
    }

    private static Set<String> mapIds(ShopMapLayers.ShopIndex index, UUID viewer) {
        MapLayerRegistry.register(new ShopMapLayers(server -> index));
        MapQueryResult r = MapLayerRegistry.query("neroeconomy", MapViewer.forTest(viewer, null, null, null), HOME,
                MapBounds.around(0, 0, 1024));
        assertFalse(r.failed());
        Set<String> out = new HashSet<>();
        r.markers().forEach(m -> out.add(m.id()));
        return out;
    }

    private static Set<String> expected(Map<String, UUID> rows, UUID viewer) {
        Set<String> out = new HashSet<>();
        String prefix = HOME.identifier() + "|";
        rows.forEach((location, who) -> {
            if (who.equals(viewer) && location.startsWith(prefix)) {
                try {
                    BlockPos p = BlockPos.of(Long.parseLong(location.substring(prefix.length())));
                    out.add("shop/" + p.getX() + "_" + p.getY() + "_" + p.getZ());
                } catch (NumberFormatException skip) {
                    // not a position
                }
            }
        });
        return out;
    }

    @Test
    void parityForOwnerStrangerThirdAndErasedOwner() {
        for (boolean erased : new boolean[] {false, true}) {
            Map<String, UUID> rows = rows(erased);
            for (UUID viewer : List.of(OWNER, STRANGER, THIRD, ERASED)) {
                assertEquals(expected(rows, viewer), mapIds(index(rows), viewer),
                        "erased=" + erased + ", viewer " + viewer);
            }
        }
        assertEquals(2, mapIds(index(rows(true)), OWNER).size(), "two shops here; the other world's and the bad key are skipped");
        assertTrue(mapIds(index(rows(true)), ERASED).isEmpty(), "an erased owner's shop is gone");
    }

    /**
     * The same parity over the real {@link ShopOwnershipState} (decoded from its save codec, erased through its own
     * {@code forgetPlayer}). Older Minecraft lines cannot construct a {@code SavedData} without a bootstrapped
     * game, so there this test is skipped rather than failed.
     */
    @Test
    void parityOverTheRealStore() {
        ShopOwnershipState state;
        try {
            JsonArray shops = new JsonArray();
            rows(false).forEach((location, owner) -> {
                JsonObject row = new JsonObject();
                row.addProperty("location", location);
                row.addProperty("owner", owner.toString());
                shops.add(row);
            });
            JsonObject root = new JsonObject();
            root.add("shops", shops);
            state = ShopOwnershipState.CODEC.parse(JsonOps.INSTANCE, root).getOrThrow();
        } catch (Throwable notBootstrapped) {
            Assumptions.abort("SavedData needs a bootstrapped game on this Minecraft line");
            return;
        }
        state.forgetPlayer(ERASED);
        Map<String, UUID> after = rows(true);
        for (UUID viewer : List.of(OWNER, STRANGER, THIRD, ERASED)) {
            assertEquals(expected(after, viewer), mapIds(state::locationsOf, viewer), "viewer " + viewer);
        }
    }
}
