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
        String key = normalize(tagOrItem);
        SHOCKS.put(key, new Shock(capped, expires));
        // Other mods hear of it through Core, as neroeconomy:market_shock (multiplier x 1000, rising).
        za.co.neroland.neroeconomy.sibling.EconomyChannels.shockStarted(key, capped);
    }

    /** How many shocks are active now. */
    public static int activeCount() {
        purge(NeroEconomyCommon.CURRENCY_PROVIDER.now());
        return SHOCKS.size();
    }

    /** Whether a shock on {@code tagOrItem} is active now. */
    public static boolean active(String tagOrItem) {
        if (tagOrItem == null || tagOrItem.isBlank()) return false;
        Shock shock = SHOCKS.get(normalize(tagOrItem));
        return shock != null && shock.expiresAt() > NeroEconomyCommon.CURRENCY_PROVIDER.now();
    }

    /** Ends the shock on {@code tagOrItem} early. @return whether there was one */
    public static boolean clearShock(String tagOrItem) {
        if (tagOrItem == null || tagOrItem.isBlank()) return false;
        String key = normalize(tagOrItem);
        boolean removed = SHOCKS.remove(key) != null;
        if (removed) za.co.neroland.neroeconomy.sibling.EconomyChannels.shockEnded(key);
        return removed;
    }

    static double multiplier(MarketListingView listing, long now) {
        purge(now);
        double result = multiplierFor(listing.itemId().toString(), now);
        for (Identifier tag : listing.tags()) result *= multiplierFor('#' + tag.toString(), now);
        return Math.max(EconomyConfig.MIN_PRICE_MULTIPLIER.get(),
                Math.min(EconomyConfig.MAX_PRICE_MULTIPLIER.get(), result));
    }

    /**
     * The combined multiplier of every shock that is active at {@code now} (economy time) for any
     * of {@code keys}: item ids, tag ids with a leading {@code #}, or any other id a shock was
     * applied under, such as a listed company's. 1 when none is active. The result is clamped to
     * {@code minPriceMultiplier}…{@code maxPriceMultiplier}, exactly as a market quote is.
     */
    public static double multiplier(java.util.Collection<String> keys, long now) {
        if (keys == null || keys.isEmpty() || SHOCKS.isEmpty()) return 1.0D;
        double result = 1.0D;
        for (String key : keys) if (key != null && !key.isBlank()) result *= multiplierFor(key, now);
        return Math.max(EconomyConfig.MIN_PRICE_MULTIPLIER.get(),
                Math.min(EconomyConfig.MAX_PRICE_MULTIPLIER.get(), result));
    }

    /** Drops every shock; call this when a server unbinds so shocks cannot leak across worlds. */
    public static void clear() { SHOCKS.clear(); }

    /** Drops shocks whose window has closed; safe to call from a server tick. */
    public static void purgeExpired() { purge(NeroEconomyCommon.CURRENCY_PROVIDER.now()); }
    private static void purge(long now) {
        for (Map.Entry<String, Shock> entry : SHOCKS.entrySet())
            if (entry.getValue().expiresAt() <= now && SHOCKS.remove(entry.getKey(), entry.getValue()))
                za.co.neroland.neroeconomy.sibling.EconomyChannels.shockEnded(entry.getKey());
    }
    private static double multiplierFor(String key, long now) {
        Shock shock = SHOCKS.get(normalize(key));
        return shock == null || shock.expiresAt() <= now ? 1.0D : shock.multiplier();
    }
    private static String normalize(String key) { return key.trim().toLowerCase(java.util.Locale.ROOT); }

    /** @deprecated retained for existing callers; use {@link #clear()}. */
    @Deprecated static void clearForTest() { clear(); }
}
