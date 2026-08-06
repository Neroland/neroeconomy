package za.co.neroland.neroeconomy.obligation;

import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** Timed lot with one currently escrowed high bid. */
public record AuctionRecord(UUID id, UUID seller, ItemStack lot, long minimumBid, long highestBid,
        String highestBidder, long endsAt, boolean interplanetary, JobState state) {
    public AuctionRecord {
        if (id == null || seller == null || lot == null || lot.isEmpty() || minimumBid < 1L
                || highestBid < 0L || highestBidder == null || endsAt < 1L || state == null)
            throw new IllegalArgumentException("Invalid auction");
        lot = lot.copy();
    }
    public UUID bidderId() {
        try { return highestBidder.isBlank() ? null : UUID.fromString(highestBidder); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
