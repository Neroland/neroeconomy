package za.co.neroland.neroeconomy.shop;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.market.MarketSavedData;

/**
 * Finds shop records whose block is no longer there and releases them.
 *
 * <p>A shop reports its own removal whenever the game tells it (see {@link PlayerShopBlock}), but a
 * block swapped out with side effects suppressed — {@code /setblock}, {@code /fill}, {@code /clone},
 * structure placement — reports nothing at all. This pass is the backstop: for every recorded shop
 * location in a chunk that is loaded right now, if the block is not a Player Shop the owner row is
 * dropped and any listing stranded there goes to its owner's vault. Unloaded chunks are
 * left alone, so the pass never forces a chunk to load and never judges what it cannot see.
 */
public final class ShopSweep {
    private ShopSweep() { }

    /** A parsed {@code dimension|packedPosition} key; null fields never occur. */
    public record Location(String dimension, long packedPosition) { }

    /** Splits a stored key. @return null when the key is not one this build writes */
    public static Location parse(String key) {
        if (key == null) return null;
        int separator = key.lastIndexOf('|');
        if (separator <= 0 || separator == key.length() - 1) return null;
        try { return new Location(key.substring(0, separator), Long.parseLong(key.substring(separator + 1))); }
        catch (NumberFormatException malformed) { return null; }
    }

    /** @return how many stale locations were released */
    public static int run(MinecraftServer server) {
        if (server == null) return 0;
        ShopOwnershipState owners = ShopOwnershipState.get(server);
        MarketSavedData market = MarketSavedData.get(server);
        Set<String> keys = new LinkedHashSet<>(owners.locations());
        keys.addAll(market.shopLocations());
        int released = 0;
        for (String key : keys) {
            Location location = parse(key);
            if (location == null) continue;
            Identifier dimension = Identifier.tryParse(location.dimension());
            if (dimension == null) continue;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) continue;
            BlockPos pos = BlockPos.of(location.packedPosition());
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || chunk.getBlockState(pos).getBlock() instanceof PlayerShopBlock) continue;
            boolean changed = owners.removeKey(key);
            changed |= market.releaseShopsAt(key) > 0;
            if (changed) released++;
        }
        return released;
    }
}
