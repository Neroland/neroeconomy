package za.co.neroland.neroeconomy.book;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * A resting buy order: so many of one item, unmodified, at up to a price per unit, with the
 * credits to pay for all of it held in the escrow account.
 *
 * @param id        stable id; a buy offer migrated from the Market Board keeps the id it had
 * @param owner     who is buying
 * @param item      the item id wanted
 * @param remaining how many are still wanted, at least 1
 * @param unitPrice the most the buyer pays per unit, at least 1
 * @param escrow    the credits held in escrow for this order: {@code unitPrice × remaining}
 * @param sequence  the order's place in the queue among orders at the same price; lower is older
 * @param expiresAt economy time at which the order lapses and the escrow goes back
 * @param source    where the order came from
 */
public record BuyOrder(UUID id, AccountId owner, String item, int remaining, long unitPrice, long escrow,
        long sequence, long expiresAt, OrderSource source) {
    public BuyOrder {
        if (id == null || owner == null || item == null || item.isBlank() || remaining < 1 || unitPrice < 1L
                || escrow < 0L || sequence < 0L || source == null)
            throw new IllegalArgumentException("Invalid buy order");
    }

    /** The same order after a fill: fewer wanted, less held. */
    BuyOrder after(int left, long escrowLeft) {
        return new BuyOrder(id, owner, item, left, unitPrice, escrowLeft, sequence, expiresAt, source);
    }
}
