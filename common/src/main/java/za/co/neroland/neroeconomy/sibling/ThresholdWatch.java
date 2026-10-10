package za.co.neroland.neroeconomy.sibling;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers which side of its threshold each watched quantity was on, and reports only a change.
 * The first observation of a scope sets its side without reporting, so a restart does not announce
 * every quantity again. In memory only, never personal (scopes are a world, a sector, a commodity).
 */
public final class ThresholdWatch {
    /** The most scopes one watch remembers; a new scope past it is not watched. */
    public static final int MAX_SCOPES = 4_096;

    private final Map<String, Boolean> sides = new HashMap<>();

    /**
     * Records that {@code scope} is now {@code above} its threshold (or not).
     *
     * @return the new side when it changed; null on the first observation or no change
     */
    public synchronized Boolean observe(String scope, boolean above) {
        if (scope == null) return null;
        Boolean before = sides.get(scope);
        if (before == null) {
            if (sides.size() < MAX_SCOPES) sides.put(scope, above);
            return null;
        }
        if (before == above) return null;
        sides.put(scope, above);
        return above;
    }

    /** Forgets a scope, so its next observation sets its side again without reporting. */
    public synchronized void forget(String scope) { if (scope != null) sides.remove(scope); }

    public synchronized void clear() { sides.clear(); }

    public synchronized int size() { return sides.size(); }
}
