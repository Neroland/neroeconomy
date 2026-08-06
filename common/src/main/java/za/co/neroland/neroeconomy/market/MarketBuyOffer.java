package za.co.neroland.neroeconomy.market;

import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Durable credits-backed request to buy a quantity of one item id. */
public record MarketBuyOffer(UUID offerId, UUID buyer, Identifier itemId, int remaining,
        long unitPrice, long escrow, long expiresAt) { }
