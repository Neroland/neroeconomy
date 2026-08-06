package za.co.neroland.neroeconomy.api;

import java.util.UUID;

/** Immutable input passed to price modifiers. */
public record PriceContext(MarketListingView listing, UUID buyer, long quotedAt) {
    public PriceContext {
        if (listing == null || buyer == null || quotedAt < 0L) throw new IllegalArgumentException("Invalid price context");
    }
}
