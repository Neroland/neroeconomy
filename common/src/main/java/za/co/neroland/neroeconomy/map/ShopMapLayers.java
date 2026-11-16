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
 * NeroEconomy's map layer, offered through Neroland Core's map registry; NeroEconomy depends on no map mod.
 *
 * <p>{@code my_shops} is an {@link MapScope#OWNER} layer: the shop blocks the viewer's own UUID owns in
 * {@link ShopOwnershipState}, and nothing else. Other players' shops are never shown, by proximity or otherwise:
 * a shop has an owner, and there is no owner opt-in to being listed on maps.
 */
public final class ShopMapLayers implements MapLayerProvider {

    private static final Logger LOG = LoggerFactory.getLogger("NeroEconomy/Map");

    public static final Identifier MY_SHOPS = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "my_shops");
    static final Identifier ICON = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "my_shop");

    private static final List<MapLayerInfo> LAYERS = List.of(
            MapLayerInfo.of(MY_SHOPS, "map.layer.neroeconomy.my_shops", ICON, MapScope.OWNER));

    /** The one read this layer needs: the shop location keys an owner UUID holds ({@code locationsOf}). */
    @FunctionalInterface
    interface ShopIndex {
        List<String> locationsOf(UUID owner);
    }

    private final Function<MinecraftServer, ShopIndex> stores;

    ShopMapLayers(Function<MinecraftServer, ShopIndex> stores) {
        this.stores = stores;
    }

    /** Registers the provider with Core's map registry. Never throws. */
    public static void register() {
        try {
            MapLayerRegistry.register(new ShopMapLayers(server -> server == null ? null
                    : ShopOwnershipState.get(server)::locationsOf));
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

    /** The viewer's own shop positions in {@code dimension}, decoded from the store's location keys. */
    static List<BlockPos> shopsOf(ShopIndex store, UUID viewer, ResourceKey<Level> dimension) {
        List<BlockPos> out = new ArrayList<>();
        if (store == null || viewer == null || dimension == null) {
            return out;
        }
        String prefix = dimension.identifier() + "|";
        for (String location : store.locationsOf(viewer)) {
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
