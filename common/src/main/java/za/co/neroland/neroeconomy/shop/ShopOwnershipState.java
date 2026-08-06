package za.co.neroland.neroeconomy.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.data.SavedDataRecovery;

/** Server-wide canonical shop ownership index, so erasure also reaches unloaded shops. */
public final class ShopOwnershipState extends SavedData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "shop_owners");
    public static final SavedDataType<ShopOwnershipState> TYPE =
            new SavedDataType<>(ID, ShopOwnershipState::new, codec(), null);

    private final Map<String, UUID> owners = new LinkedHashMap<>();

    public ShopOwnershipState() { }
    public static ShopOwnershipState get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ShopOwnershipState::new, ID.toString());
    }

    public UUID owner(ServerLevel level, BlockPos pos) { return owners.get(key(level, pos)); }
    public void claim(ServerLevel level, BlockPos pos, UUID player) {
        if (player != null && owners.putIfAbsent(key(level, pos), player) == null) setDirty();
    }
    public void remove(ServerLevel level, BlockPos pos) {
        if (owners.remove(key(level, pos)) != null) setDirty();
    }
    public void forgetPlayer(UUID player) {
        if (player != null && owners.entrySet().removeIf(entry -> player.equals(entry.getValue()))) setDirty();
    }

    private static String key(ServerLevel level, BlockPos pos) {
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
