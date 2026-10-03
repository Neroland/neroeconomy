package za.co.neroland.neroeconomy.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * A tiny store of its own that records one fact: this world has an economy. It exists so that a
 * missing or unreadable economy store can be told apart from a world that never had one. It holds
 * no player data and nothing about any account.
 */
public final class EconomyMarker extends SavedData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "economy_marker");
    static final Codec<EconomyMarker> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("exists", false).forGetter(marker -> marker.exists),
            Codec.INT.optionalFieldOf("schema", 0).forGetter(marker -> marker.schema))
            .apply(instance, EconomyMarker::new));
    public static final SavedDataType<EconomyMarker> TYPE = new SavedDataType<>(ID, EconomyMarker::new, CODEC, null);

    private boolean exists;
    private int schema;

    public EconomyMarker() { }

    private EconomyMarker(boolean exists, int schema) {
        this.exists = exists;
        this.schema = Math.max(0, schema);
    }

    /** Whether an economy store has been seen on disk in this world. */
    public boolean exists() { return exists; }

    /** The schema of the economy store this marker was last written for. */
    public int schema() { return schema; }

    /** Records that the economy store exists at {@code storeSchema}. */
    public void markExists(int storeSchema) {
        if (exists && schema == storeSchema) return;
        exists = true;
        schema = storeSchema;
        setDirty();
    }
}
