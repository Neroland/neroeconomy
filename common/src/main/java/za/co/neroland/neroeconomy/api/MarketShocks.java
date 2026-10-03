package za.co.neroland.neroeconomy.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * Restart-local, automatically expiring item/tag price shocks.
 *
 * <p>The table is static, so it must never outlive the world that created it: the currency provider
 * clears it when a server unbinds and sweeps expired entries while one is running. Shock windows
 * are measured on the economy clock, the same one a quote is stamped with.
 */
public final class MarketShocks {
    private record Shock(double multiplier, long expiresAt) { }
    private static final Map<String, Shock> SHOCKS = new ConcurrentHashMap<>();

    private MarketShocks() { }

    public static void applyShock(String tagOrItem, double multiplier, Duration duration) {
        if (tagOrItem == null || tagOrItem.isBlank() || !Double.isFinite(multiplier)
                || duration == null || duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException("Invalid market shock");
        double capped = Math.max(EconomyConfig.MIN_PRICE_MULTIPLIER.get(),
                Math.min(EconomyConfig.MAX_PRICE_MULTIPLIER.get(), multiplier));
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        long millis;
        try { millis = duration.toMillis(); }
        catch (ArithmeticException overflow) { millis = Long.MAX_VALUE - now; }
        long expires = millis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + millis;
        SHOCKS.put(normalize(tagOrItem), new Shock(capped, expires));
    }

    static double multiplier(MarketListingView listing, long now) {
        purge(now);
        double result = multiplierFor(listing.itemId().toString(), now);
        for (Identifier tag : listing.tags()) result *= multiplierFor('#' + tag.toString(), now);
        return Math.max(EconomyConfig.MIN_PRICE_MULTIPLIER.get(),
                Math.min(EconomyConfig.MAX_PRICE_MULTIPLIER.get(), result));
    }

    /** Drops every shock; call this when a server unbinds so shocks cannot leak across worlds. */
    public static void clear() { SHOCKS.clear(); }

    /** Drops shocks whose window has closed; safe to call from a server tick. */
    public static void purgeExpired() { purge(NeroEconomyCommon.CURRENCY_PROVIDER.now()); }
    private static void purge(long now) { SHOCKS.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now); }
    private static double multiplierFor(String key, long now) {
        Shock shock = SHOCKS.get(normalize(key));
        return shock == null || shock.expiresAt() <= now ? 1.0D : shock.multiplier();
    }
    private static String normalize(String key) { return key.trim().toLowerCase(java.util.Locale.ROOT); }

    /** @deprecated retained for existing callers; use {@link #clear()}. */
    @Deprecated static void clearForTest() { clear(); }
}
