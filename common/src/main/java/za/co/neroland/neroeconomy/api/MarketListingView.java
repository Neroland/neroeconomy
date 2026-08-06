package za.co.neroland.neroeconomy.api;

import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;

/** Read-only listing shape consumed by pricing, terminals, sibling mods, and NeroLink. */
public interface MarketListingView {
    UUID listingId();
    Identifier itemId();
    Set<Identifier> tags();
    long baseUnitPrice();
    int availableQuantity();
}
