package za.co.neroland.neroeconomy.sibling;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import za.co.neroland.neroeconomy.platform.Services;

/**
 * Which other mods have claimed a handle on the ledger, and with which reason codes. A mod id is
 * claimed once, by a loaded mod, before the first server starts; after that the registry is
 * closed for the life of the process, so a class loaded later cannot claim a handle in another
 * mod's name.
 */
public final class CallerRegistry {
    /** The most callers that can ever claim a handle. */
    public static final int MAX_CALLERS = 256;
    /** The registry of the running game; closed when the first server is bound. */
    public static final CallerRegistry GAME = new CallerRegistry(modId -> Services.PLATFORM.isModLoaded(modId));

    private final Predicate<String> loaded;
    private final Map<String, Set<String>> claimed = new LinkedHashMap<>();
    private boolean closed;

    public CallerRegistry(Predicate<String> loaded) {
        if (loaded == null) throw new IllegalArgumentException("loaded");
        this.loaded = loaded;
    }

    /**
     * Claims {@code modId} with its closed set of reason codes.
     *
     * @return an unmodifiable copy of the reasons
     * @throws IllegalArgumentException for a malformed or reserved id, an empty or oversized set of
     *         reasons, or a malformed reason
     * @throws IllegalStateException when the id is already claimed, is not a loaded mod, the
     *         registry is full, or the registry has closed
     */
    public synchronized Set<String> claim(String modId, Set<String> reasons) {
        if (!SiblingRules.validModId(modId) || SiblingRules.RESERVED.contains(modId))
            throw new IllegalArgumentException("Not a mod id another mod may claim");
        if (reasons == null || reasons.isEmpty() || reasons.size() > SiblingRules.MAX_REASONS)
            throw new IllegalArgumentException("Between 1 and " + SiblingRules.MAX_REASONS + " reasons are needed");
        Set<String> copy = new LinkedHashSet<>();
        for (String reason : reasons) {
            if (!SiblingRules.validReason(reason)) throw new IllegalArgumentException("Malformed reason code");
            copy.add(reason);
        }
        if (closed) throw new IllegalStateException("Claims close when the first server starts");
        if (claimed.containsKey(modId)) throw new IllegalStateException("Already claimed: " + modId);
        if (claimed.size() >= MAX_CALLERS) throw new IllegalStateException("Too many callers");
        if (!loaded.test(modId)) throw new IllegalStateException("Not a loaded mod: " + modId);
        Set<String> frozen = Set.copyOf(copy);
        claimed.put(modId, frozen);
        return frozen;
    }

    /** Closes the registry for good: called when the first server is bound. */
    public synchronized void close() { closed = true; }

    public synchronized boolean closed() { return closed; }

    /** Whether {@code modId} holds a handle. */
    public synchronized boolean claimed(String modId) { return modId != null && claimed.containsKey(modId); }

    /** The callers holding a handle, in the order they claimed. */
    public synchronized Set<String> callers() { return Set.copyOf(claimed.keySet()); }
}
