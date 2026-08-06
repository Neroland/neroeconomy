package za.co.neroland.neroeconomy.api;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministically ordered modifier registry keyed by owning mod id. */
public final class PriceModifierRegistry {
    private static final Map<String, PriceModifier> MODIFIERS = new TreeMap<>();

    private PriceModifierRegistry() { }

    public static synchronized void register(String modId, PriceModifier modifier) {
        if (modId == null || !modId.matches("[a-z0-9_.-]+") || modifier == null)
            throw new IllegalArgumentException("Invalid price modifier registration");
        MODIFIERS.put(modId, modifier);
    }

    static synchronized List<Map.Entry<String, PriceModifier>> ordered() {
        return List.copyOf(MODIFIERS.entrySet());
    }

    static synchronized void clearForTest() { MODIFIERS.clear(); }
}
