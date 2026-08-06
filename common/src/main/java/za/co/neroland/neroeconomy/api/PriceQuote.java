package za.co.neroland.neroeconomy.api;

import java.util.UUID;

/** Indicative server quote; execution always quotes again. */
public record PriceQuote(UUID listingId, long unitPrice, long quotedAt) {
    public PriceQuote {
        if (listingId == null || unitPrice < 1L || quotedAt < 0L) throw new IllegalArgumentException("Invalid quote");
    }

    public long total(int quantity) {
        if (quantity < 1 || unitPrice > Long.MAX_VALUE / quantity) throw new ArithmeticException("price overflow");
        return unitPrice * quantity;
    }
}
