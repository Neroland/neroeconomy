package za.co.neroland.neroeconomy.market;

import java.util.Set;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.api.MarketListingView;

/** Persistent goods escrow for one fixed-price listing. */
public record MarketListing(UUID listingId, UUID seller, ListingSource source, ItemStack stock,
        long baseUnitPrice, long expiresAt, String location) implements MarketListingView {
    public MarketListing {
        if (listingId == null || seller == null || source == null || stock == null || stock.isEmpty()
                || baseUnitPrice < 1L || expiresAt < 1L || location == null)
            throw new IllegalArgumentException("Invalid market listing");
        stock = stock.copy();
    }
    @Override public Identifier itemId() { return BuiltInRegistries.ITEM.getKey(stock.getItem()); }
    @Override public Set<Identifier> tags() { return Set.of(); }
    @Override public int availableQuantity() { return stock.getCount(); }
    public MarketListing withStock(ItemStack next) {
        return new MarketListing(listingId, seller, source, next, baseUnitPrice, expiresAt, location);
    }
}
