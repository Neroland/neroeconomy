package za.co.neroland.neroeconomy.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.nerolandcore.map.MapBounds;
import za.co.neroland.nerolandcore.map.MapLabel;
import za.co.neroland.nerolandcore.map.MapLayerInfo;
import za.co.neroland.nerolandcore.map.MapLayerProvider;
import za.co.neroland.nerolandcore.map.MapLayerRegistry;
import za.co.neroland.nerolandcore.map.MapLayerSink;
import za.co.neroland.nerolandcore.map.MapMarker;
import za.co.neroland.nerolandcore.map.MapScope;
import za.co.neroland.nerolandcore.map.MapViewer;

/**
 * NeroEconomy's map layers, offered through Neroland Core's map registry; NeroEconomy depends on no map mod.
 *
 * <p>{@code my_shops} is an {@link MapScope#OWNER} layer: the shop blocks the viewer's own UUID owns in
 * {@link ShopOwnershipState}, and nothing else.
 *
 * <p>{@code listed_shops} is a {@link MapScope#PUBLIC} layer: only the shops whose owner chose, from the hub's Shop
 * tab, to list them on maps (off by default). It returns each shop's position and a fixed lang-key label, never the
 * owner's name, UUID or online state, and it is the same for every viewer. An unlisted shop is never shown to anyone
 * but its owner, by proximity or otherwise.
 */
public final class ShopMapLayers implements MapLayerProvider {

    private static final Logger LOG = LoggerFactory.getLogger("NeroEconomy/Map");

    public static final Identifier MY_SHOPS = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "my_shops");
    public static final Identifier LISTED_SHOPS =
            Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "listed_shops");
    static final Identifier ICON = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "my_shop");
    static final Identifier LISTED_ICON = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "listed_shop");

    private static final List<MapLayerInfo> LAYERS = List.of(
            MapLayerInfo.of(MY_SHOPS, "map.layer.neroeconomy.my_shops", ICON, MapScope.OWNER),
            MapLayerInfo.of(LISTED_SHOPS, "map.layer.neroeconomy.listed_shops", LISTED_ICON, MapScope.PUBLIC));

    /** The one read {@code my_shops} needs: the shop location keys an owner UUID holds ({@code locationsOf}). */
    @FunctionalInterface
    interface ShopIndex {
        List<String> locationsOf(UUID owner);
    }

    /** The one read {@code listed_shops} needs: the location keys of every listed shop, with no owner. */
    @FunctionalInterface
    interface ListedIndex {
        List<String> listedLocations();
    }

    private final Function<MinecraftServer, ShopIndex> stores;
    private final Function<MinecraftServer, ListedIndex> listed;

    ShopMapLayers(Function<MinecraftServer, ShopIndex> stores) {
        this(stores, server -> List::of);
    }

    ShopMapLayers(Function<MinecraftServer, ShopIndex> stores, Function<MinecraftServer, ListedIndex> listed) {
        this.stores = stores;
        this.listed = listed;
    }

    /** Registers the provider with Core's map registry. Never throws. */
    public static void register() {
        try {
            MapLayerRegistry.register(new ShopMapLayers(
                    server -> server == null ? null : ShopOwnershipState.get(server)::locationsOf,
                    server -> server == null ? null : ShopOwnershipState.get(server)::listedLocations));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("NeroEconomy map layer not registered ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public String modId() {
        return NeroEconomyCommon.MOD_ID;
    }

    @Override
    public List<MapLayerInfo> layers() {
        return LAYERS;
    }

    @Override
    public void query(MapViewer viewer, ResourceKey<Level> dimension, MapBounds bounds, MapLayerSink sink) {
        if (viewer == null || dimension == null || bounds == null || sink == null) {
            return;
        }
        listedShops(viewer, dimension, bounds, sink);
        ShopIndex store = stores.apply(viewer.server());
        if (store == null) {
            return;
        }
        List<BlockPos> shops = shopsOf(store, viewer.id(), dimension);
        BlockPos from = viewer.position().orElse(new BlockPos(bounds.centreX(), 64, bounds.centreZ()));
        shops.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        for (BlockPos pos : shops) {
            if (sink.isFull(MY_SHOPS)) {
                return;
            }
            sink.marker(MapMarker.builder("shop/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ(), MY_SHOPS,
                    dimension, pos).label(MapLabel.translatable("map.neroeconomy.my_shop")).build());
        }
    }

    /**
     * The listed shops inside {@code bounds}, nearest the bounds' centre first: the same answer for every viewer,
     * online or not, since the owner consented to the listing itself and the viewer's identity plays no part.
     */
    private void listedShops(MapViewer viewer, ResourceKey<Level> dimension, MapBounds bounds, MapLayerSink sink) {
        ListedIndex index = listed.apply(viewer.server());
        if (index == null) {
            return;
        }
        List<BlockPos> shops = new ArrayList<>();
        for (BlockPos pos : decode(index.listedLocations(), dimension)) {
            if (bounds.contains(pos.getX(), pos.getZ())) {
                shops.add(pos);
            }
        }
        BlockPos centre = new BlockPos(bounds.centreX(), 64, bounds.centreZ());
        shops.sort(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(centre)).thenComparingLong(BlockPos::asLong));
        for (BlockPos pos : shops) {
            if (sink.isFull(LISTED_SHOPS)) {
                return;
            }
            sink.marker(MapMarker.builder("listed_shop/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ(),
                    LISTED_SHOPS, dimension, pos).label(MapLabel.translatable("map.neroeconomy.listed_shop")).build());
        }
    }

    /** The viewer's own shop positions in {@code dimension}, decoded from the store's location keys. */
    static List<BlockPos> shopsOf(ShopIndex store, UUID viewer, ResourceKey<Level> dimension) {
        if (store == null || viewer == null || dimension == null) {
            return new ArrayList<>();
        }
        return decode(store.locationsOf(viewer), dimension);
    }

    /** The positions in {@code dimension} among stored {@code dimension|packedPosition} keys; bad keys are skipped. */
    static List<BlockPos> decode(List<String> locations, ResourceKey<Level> dimension) {
        List<BlockPos> out = new ArrayList<>();
        if (locations == null || dimension == null) {
            return out;
        }
        String prefix = dimension.identifier() + "|";
        for (String location : locations) {
            if (!location.startsWith(prefix)) {
                continue;
            }
            try {
                out.add(BlockPos.of(Long.parseLong(location.substring(prefix.length()))));
            } catch (NumberFormatException malformed) {
                // A key this build did not write: skip it rather than guess.
            }
        }
        return out;
    }
}
