package za.co.neroland.neroeconomy.obligation;

import java.util.UUID;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * An auction a Merchant House runs from its own stock. Pure data: the lot is a {@link Lot} (always
 * unmodified goods), the seller is the House's id, and the standing bid is held in the ledger's
 * escrow account like any other bid. Players bid on it through the ordinary auction flow.
 *
 * @param highestBidder the standing bidder's id as text; empty while nobody has bid, and after the
 *                      row was settled for an erased player
 */
public record HouseAuction(UUID id, String house, Lot lot, long minimumBid, long highestBid, String highestBidder,
        long endsAt, boolean interplanetary, JobState state) {
    public HouseAuction {
        if (id == null || house == null || house.isBlank() || lot == null || !lot.pristine() || minimumBid < 1L
                || highestBid < 0L || highestBidder == null || endsAt < 1L || state == null)
            throw new IllegalArgumentException("Invalid house auction");
    }

    public UUID bidderId() {
        try { return highestBidder.isBlank() ? null : UUID.fromString(highestBidder); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public boolean open() { return state == JobState.OPEN; }

    HouseAuction withBid(long amount, UUID bidder, long end) {
        return new HouseAuction(id, house, lot, minimumBid, amount, bidder.toString(), end, interplanetary, state);
    }

    HouseAuction withoutBid() {
        return new HouseAuction(id, house, lot, minimumBid, 0L, "", endsAt, interplanetary, state);
    }

    HouseAuction settled() {
        return new HouseAuction(id, house, lot, minimumBid, highestBid, highestBidder, endsAt, interplanetary,
                JobState.FULFILLED);
    }

    HouseAuction forgetBidder() {
        return new HouseAuction(id, house, lot, minimumBid, highestBid, "", endsAt, interplanetary, state);
    }
}
