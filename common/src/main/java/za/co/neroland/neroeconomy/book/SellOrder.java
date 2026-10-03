package za.co.neroland.neroeconomy.book;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.goods.Lot;

/**
 * A resting sell order: one lot, held by the book, at a price per unit.
 *
 * @param id        stable id; a listing migrated from the Market Board keeps the id it had
 * @param owner     whose goods these are and who is paid when they sell
 * @param lot       what is left to sell; the book holds these goods
 * @param unitPrice the seller's price per unit, at least 1
 * @param sequence  the order's place in the queue among orders at the same price; lower is older
 * @param expiresAt economy time at which the order lapses and the goods go back
 * @param source    where the order came from
 */
public record SellOrder(UUID id, AccountId owner, Lot lot, long unitPrice, long sequence, long expiresAt,
        OrderSource source) {
    public SellOrder {
        if (id == null || owner == null || lot == null || unitPrice < 1L || sequence < 0L || source == null)
            throw new IllegalArgumentException("Invalid sell order");
    }

    public String item() { return lot.item(); }

    /** Whether the order can be matched with a buy order: only unmodified goods can. */
    public boolean matchable() { return lot.pristine(); }

    /** The same order with fewer goods left. */
    SellOrder withCount(int count) {
        return new SellOrder(id, owner, lot.withCount(count), unitPrice, sequence, expiresAt, source);
    }
}
