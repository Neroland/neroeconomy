package za.co.neroland.neroeconomy.market;

import java.util.UUID;
import net.minecraft.resources.Identifier;

/**
 * Durable credits-backed request to buy a quantity of one item id. A {@code demo} offer is a
 * showcase row seeded by the admin gallery: it holds no escrow, can never be filled, refunds nothing
 * when it expires, and is hidden from every search that does not ask for it.
 */
public record MarketBuyOffer(UUID offerId, UUID buyer, Identifier itemId, int remaining,
        long unitPrice, long escrow, long expiresAt, boolean demo) {
    /** An ordinary, escrow-backed offer. */
    public MarketBuyOffer(UUID offerId, UUID buyer, Identifier itemId, int remaining,
            long unitPrice, long escrow, long expiresAt) {
        this(offerId, buyer, itemId, remaining, unitPrice, escrow, expiresAt, false);
    }
}
