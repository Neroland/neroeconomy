package za.co.neroland.neroeconomy.sibling;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Decodes a market-shock request another mod sends through Core's {@code ThresholdEvents} on the
 * channel {@code <modid>:market_shock_request}: no compile dependency on this mod and no
 * reflection. Pure, so every rule is unit tested.
 *
 * <ul>
 *   <li>{@code scope}: the item id or {@code #tag} to shock;</li>
 *   <li>{@code value}: the multiplier times 1000 (clamped to the price band when applied);</li>
 *   <li>{@code threshold}: how long, in economy-minutes, 1 to {@code shockMaxMinutes};</li>
 *   <li>{@code rising}: true applies, false clears that key early.</li>
 * </ul>
 * Only a loaded mod named in {@code shockSources} is heard; at most {@code maxActiveShocks} are active.
 */
public final class ShockRequests {
    /** The channel path a request comes in on. */
    public static final String PATH = "market_shock_request";

    private static final Pattern KEY = Pattern.compile("#?[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");

    public enum Kind { IGNORE, APPLY, CLEAR }

    /** What to do with one request. */
    public record Decision(Kind kind, String key, double multiplier, long minutes, String why) {
        static Decision ignore(String why) { return new Decision(Kind.IGNORE, "", 1.0D, 0L, why); }
    }

    private ShockRequests() { }

    /**
     * @param existing whether a shock on {@code scope} is already active (replacing one does not count toward the cap)
     */
    public static Decision decode(String namespace, String path, String scope, long value, long threshold,
            boolean rising, Set<String> sources, Predicate<String> loaded, long maxMinutes, int active, int maxActive,
            boolean existing) {
        if (!PATH.equals(path)) return Decision.ignore("channel");
        if (namespace == null || sources == null || !sources.contains(namespace)) return Decision.ignore("source");
        if (loaded == null || !loaded.test(namespace)) return Decision.ignore("not loaded");
        if (scope == null || !KEY.matcher(scope).matches()) return Decision.ignore("scope");
        if (!rising) return new Decision(Kind.CLEAR, scope, 1.0D, 0L, "clear");
        if (value <= 0L) return Decision.ignore("multiplier");
        if (threshold < 1L || maxMinutes < 1L) return Decision.ignore("duration");
        if (!existing && active >= maxActive) return Decision.ignore("too many shocks");
        return new Decision(Kind.APPLY, scope, value / 1000.0D, Math.min(threshold, maxMinutes), "apply");
    }

    /** Reads a comma-separated list of mod ids; malformed entries are skipped, at most 64 are read. */
    public static Set<String> parseSources(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String entry : text.split(",")) {
            String id = entry.trim();
            if (out.size() < 64 && SiblingRules.validModId(id) && !"neroeconomy".equals(id)) out.add(id);
        }
        return out;
    }
}
