package za.co.neroland.neroeconomy.sibling;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The pure rules of the cross-mod API: what a caller id, a reason code, a request key and a ledger
 * row's origin may look like, the transfer fee, and the per-caller budget overrides. No game
 * classes, so every rule is tested in plain JUnit.
 */
public final class SiblingRules {
    /** The most reason codes one caller may claim. */
    public static final int MAX_REASONS = 32;
    /** The longest request key. */
    public static final int MAX_KEY_LENGTH = 64;
    /** The most per-caller budget overrides read from the config. */
    public static final int MAX_OVERRIDES = 256;
    /** Ids no other mod may claim: this mod's own, and the label untyped Core deposits are counted under. */
    public static final Set<String> RESERVED = Set.of("neroeconomy", "unknown");

    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern REASON = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:/#-]{1,64}");
    private static final Pattern HEX32 = Pattern.compile(".*[0-9a-fA-F]{32}.*");

    private SiblingRules() { }

    public static boolean validModId(String modId) { return modId != null && MOD_ID.matcher(modId).matches(); }

    public static boolean validReason(String reason) { return reason != null && REASON.matcher(reason).matches(); }

    /**
     * A request key a caller may use: 1 to 64 characters from a small set, and never anything that
     * reads as a player id. A key that parses as a UUID, or that holds 32 hex digits in a row, is
     * refused, so a caller cannot store a player id in the idempotency table by accident.
     */
    public static boolean validRequestKey(String key) {
        if (key == null || !KEY.matcher(key).matches() || HEX32.matcher(key).matches()) return false;
        try {
            UUID.fromString(key);
            return false;
        } catch (IllegalArgumentException notAUuid) {
            return true;
        }
    }

    /** The origin a ledger row carries for another mod's call: {@code <modid>/<reason>}. */
    public static String origin(String modId, String reason) { return modId + '/' + reason; }

    /** Whether {@code origin} is a well-formed {@code <modid>/<reason>}; an empty or free-text one is not. */
    public static boolean validOrigin(String origin) {
        if (origin == null) return false;
        int slash = origin.indexOf('/');
        return slash > 0 && validModId(origin.substring(0, slash)) && validReason(origin.substring(slash + 1));
    }

    /** The mod id part of a valid origin; empty otherwise. */
    public static String originMod(String origin) {
        return validOrigin(origin) ? origin.substring(0, origin.indexOf('/')) : "";
    }

    /**
     * The fee a transfer of {@code amount} takes at {@code percent}: rounded up, at least 1 credit
     * whenever the percentage is above 0, never more than the amount. 0 for 0 % or a non-positive amount.
     */
    public static long fee(long amount, double percent) {
        if (amount <= 0L || !(percent > 0.0D)) return 0L;
        BigDecimal raw = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100L), 0, RoundingMode.CEILING);
        long fee = raw.compareTo(BigDecimal.valueOf(amount)) >= 0 ? amount : raw.longValue();
        return Math.max(1L, fee);
    }

    /**
     * Reads the per-caller budget overrides: a comma-separated list of {@code modid=amount}. An
     * entry that is malformed, names a reserved id, or has a negative amount is skipped; the first
     * entry for an id wins; at most {@link #MAX_OVERRIDES} are read.
     */
    public static Map<String, Long> parseBudgets(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return Collections.unmodifiableMap(out);
        for (String entry : text.split(",")) {
            if (out.size() >= MAX_OVERRIDES) break;
            int equals = entry.indexOf('=');
            if (equals <= 0) continue;
            String id = entry.substring(0, equals).trim();
            String value = entry.substring(equals + 1).trim();
            if (!validModId(id) || RESERVED.contains(id) || out.containsKey(id)) continue;
            try {
                long amount = Long.parseLong(value);
                if (amount >= 0L) out.put(id, amount);
            } catch (NumberFormatException malformed) {
                // skipped
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** The daily payout limit of one caller: its override, or the default. */
    public static long callerLimit(String modId, long defaultLimit, Map<String, Long> overrides) {
        Long override = overrides == null || modId == null ? null : overrides.get(modId);
        return Math.max(0L, override != null ? override : defaultLimit);
    }
}
