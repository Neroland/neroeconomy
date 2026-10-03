package za.co.neroland.neroeconomy.economy;

import java.util.function.LongSupplier;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

/**
 * What a test outside this package needs from the store's package-private seams: the whole store
 * through its codec, exactly as a save followed by a load, and a wall clock a test controls.
 */
public final class StoreTesting {
    private StoreTesting() { }

    /** The store as it would be written to disk. Sections that hold nothing are left out. */
    public static JsonElement encode(EconomyStore store) {
        return EconomyStore.CODEC.encodeStart(JsonOps.INSTANCE, store).getOrThrow();
    }

    /** A fresh store read from a saved one: what a restart, or a crash followed by one, leaves. */
    public static EconomyStore decode(JsonElement saved) {
        return EconomyStore.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
    }

    /** {@link #encode} then {@link #decode}. */
    public static EconomyStore reload(EconomyStore store) { return decode(encode(store)); }

    /** Replaces the wall clock every ledger reads; null restores the real one. Reset it in a finally. */
    public static void wallClock(LongSupplier clock) { EconomySavedData.wallClockForTest(clock); }
}
