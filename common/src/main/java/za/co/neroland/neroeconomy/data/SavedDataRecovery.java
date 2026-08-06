package za.co.neroland.neroeconomy.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;

/**
 * Guard around the vanilla saved-data lookup. A store whose file fails to load must never take the
 * server down and must never hand back {@code null}: a fresh, dirty instance is substituted and then
 * remembered for the rest of the session, so the substituted state is not silently discarded by the
 * next lookup. {@link #reset()} drops every substitution when a server unbinds.
 */
public final class SavedDataRecovery {
    private static final Map<String, SavedData> FALLBACKS = new ConcurrentHashMap<>();

    private SavedDataRecovery() { }

    public static <T extends SavedData> T get(ServerLevel level, SavedDataType<T> type,
            Supplier<T> factory, String label) {
        String key = level.dimension().identifier() + "|" + label;
        SavedData cached = FALLBACKS.get(key);
        if (cached == null) {
            try {
                T loaded = level.getDataStorage().computeIfAbsent(type);
                if (loaded != null) return loaded;
                report(null, label);
            } catch (RuntimeException | LinkageError failure) {
                report(failure, label);
            }
        }
        return substitute(level, type, factory, key, cached);
    }

    /** Called from server unbind so a substituted store can never leak into the next world. */
    public static void reset() {
        FALLBACKS.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T extends SavedData> T substitute(ServerLevel level, SavedDataType<T> type,
            Supplier<T> factory, String key, SavedData cached) {
        if (cached != null) return (T) cached;
        T replacement = factory.get();
        replacement.setDirty();
        SavedData previous = FALLBACKS.putIfAbsent(key, replacement);
        if (previous != null) return (T) previous;
        // Best effort: re-register so vanilla persists the substitute. Failure here is not fatal.
        try { level.getDataStorage().set(type, replacement); }
        catch (RuntimeException | LinkageError ignored) { }
        return replacement;
    }

    private static void report(Throwable failure, String label) {
        NeroEconomyCommon.LOGGER.warn(
                "[NeroEconomy] Saved data '{}' could not be loaded; a fresh store was substituted.", label);
        try {
            NeroEconomyTelemetry.captureHandledException(failure == null
                    ? new IllegalStateException("Saved data lookup returned no store") : failure,
                    "saveddata_recovery");
        } catch (RuntimeException | LinkageError ignored) { }
    }
}
