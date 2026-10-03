package za.co.neroland.neroeconomy.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;

/**
 * Server-wide canonical shop ownership index, so erasure also reaches unloaded shops: the
 * shop-owner section of the economy store.
 */
public final class ShopOwnershipState extends EconomySection {
    /** The id this section was stored under when it was a store of its own; read once, on migration. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "shop_owners");
    public static final Codec<ShopOwnershipState> CODEC = codec();
    /** The pre-combined-store type. Only {@link EconomyStore} uses it, to migrate and then empty the old file. */
    public static final SavedDataType<ShopOwnershipState> TYPE =
            new SavedDataType<>(ID, ShopOwnershipState::new, CODEC, null);

    private final Map<String, UUID> owners = new LinkedHashMap<>();

    public ShopOwnershipState() { }

    /** The shop-owner section of the world's economy store. */
    public static ShopOwnershipState get(MinecraftServer server) {
        return EconomyStore.get(server).shopOwners();
    }

    /**
     * Erasure entry point; tolerates an unknown player. The purge is pushed into Core's recovery
     * backup in the same request, because that backup is a second copy of the same rows.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        get(server).forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    @Override public boolean isEmpty() { return owners.isEmpty(); }

    public UUID owner(ServerLevel level, BlockPos pos) { return owners.get(key(level, pos)); }
    /**
     * Records who placed the shop at this position. A row already there is replaced: it can only
     * belong to an earlier shop whose removal was never reported, and the new shop is the placer's.
     */
    public void claim(ServerLevel level, BlockPos pos, UUID player) {
        if (player != null && !player.equals(owners.put(key(level, pos), player))) setDirty();
    }
    public void remove(ServerLevel level, BlockPos pos) { removeKey(key(level, pos)); }
    /** Drops the row stored under a {@code dimension|packedPosition} key. @return true when one existed */
    public boolean removeKey(String location) {
        if (owners.remove(location) == null) return false;
        setDirty();
        return true;
    }
    /** Every stored location key, for the stranded-shop sweep. */
    public List<String> locations() { return List.copyOf(owners.keySet()); }
    public void forgetPlayer(UUID player) {
        if (player != null && owners.entrySet().removeIf(entry -> player.equals(entry.getValue()))) setDirty();
    }

    /** Data-subject export: the dimension and block position of every shop this player owns. */
    public JsonArray exportFor(UUID player) {
        JsonArray out = new JsonArray();
        owners.forEach((location, owner) -> {
            if (owner.equals(player)) out.add(describe(location));
        });
        return out;
    }

    /** Splits a stored {@code dimension|packedPosition} key into readable coordinates. */
    static JsonObject describe(String location) {
        JsonObject row = new JsonObject();
        int separator = location.lastIndexOf('|');
        row.addProperty("dimension", separator < 0 ? location : location.substring(0, separator));
        if (separator < 0) return row;
        try {
            long packed = Long.parseLong(location.substring(separator + 1));
            row.addProperty("x", BlockPos.getX(packed));
            row.addProperty("y", BlockPos.getY(packed));
            row.addProperty("z", BlockPos.getZ(packed));
        } catch (NumberFormatException malformed) {
            // A key this build did not write; the dimension alone is still the subject's data.
        }
        return row;
    }

    /** The {@code dimension|packedPosition} key a shop at this position is stored and listed under. */
    public static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.asLong();
    }

    private record Row(String location, String owner) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("location").forGetter(Row::location),
                Codec.STRING.fieldOf("owner").forGetter(Row::owner)).apply(instance, Row::new));
    }
    private List<Row> rows() {
        return owners.entrySet().stream().map(entry -> new Row(entry.getKey(), entry.getValue().toString())).toList();
    }
    private static Codec<ShopOwnershipState> codec() {
        return Row.CODEC.listOf().optionalFieldOf("shops", List.of()).xmap(ShopOwnershipState::fromRows,
                ShopOwnershipState::rows).codec();
    }
    private static ShopOwnershipState fromRows(List<Row> rows) {
        ShopOwnershipState state = new ShopOwnershipState();
        for (Row row : new ArrayList<>(rows)) {
            try { state.owners.put(row.location(), UUID.fromString(row.owner())); }
            catch (IllegalArgumentException ignored) { }
        }
        return state;
    }
}
