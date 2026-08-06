package za.co.neroland.neroeconomy.market;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
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
    /**
     * Item tags resolved from the registry holder, so tag-scoped market shocks can match. Holders
     * only carry tags once the registry has bound them; before that (or for a direct holder) the
     * listing simply has no tags and pricing falls back to the item-scoped shocks.
     */
    @Override public Set<Identifier> tags() {
        try {
            return stock.typeHolder().tags().map(TagKey::location).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException unbound) {
            return Set.of();
        }
    }
    @Override public int availableQuantity() { return stock.getCount(); }
    public MarketListing withStock(ItemStack next) {
        return new MarketListing(listingId, seller, source, next, baseUnitPrice, expiresAt, location);
    }
}
