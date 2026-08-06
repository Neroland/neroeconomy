package za.co.neroland.neroeconomy.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;

/** Authoritative fixed-price engine with deterministic extension points. */
public final class PricingEngine {
    private PricingEngine() { }

    public static PriceQuote quote(MarketListingView listing, UUID buyer) {
        if (listing == null || buyer == null || listing.baseUnitPrice() < 1L)
            throw new IllegalArgumentException("Invalid listing quote");
        long now = System.currentTimeMillis();
        PriceContext context = new PriceContext(listing, buyer, now);
        BigDecimal price = BigDecimal.valueOf(listing.baseUnitPrice())
                .multiply(BigDecimal.valueOf(MarketShocks.multiplier(listing, now)));
        for (var entry : PriceModifierRegistry.ordered()) {
            try {
                double multiplier = entry.getValue().multiplier(context);
                if (Double.isFinite(multiplier) && multiplier > 0D)
                    price = price.multiply(BigDecimal.valueOf(multiplier));
            } catch (RuntimeException failure) {
                NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Price modifier '{}' failed; ignored.", entry.getKey());
            }
        }
        long value;
        try { value = price.setScale(0, RoundingMode.HALF_UP).longValueExact(); }
        catch (ArithmeticException overflow) { value = Long.MAX_VALUE; }
        return new PriceQuote(listing.listingId(), Math.max(1L, value), now);
    }

    public static boolean executionPriceAccepted(long quotedUnitPrice, long executionUnitPrice) {
        if (quotedUnitPrice < 1L || executionUnitPrice < 1L) return false;
        double movement = Math.abs((double) executionUnitPrice - quotedUnitPrice) / quotedUnitPrice;
        return movement <= EconomyConfig.EXECUTION_PRICE_TOLERANCE.get();
    }
}
