package za.co.neroland.neroeconomy.offers;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.goods.Lot;

/**
 * An escrowed offer from one account to another: goods and credits given, and optionally an item
 * and credits asked in return. Everything given left the sender when the offer was sent — the lots
 * are held by the offers section and the credits are in the ledger's escrow account — so accepting
 * it can never fail for want of what was promised.
 *
 * @param lots       the goods given, at most {@link OffersSection#MAX_LOTS}
 * @param credits    the credits given, held in escrow; 0 for none
 * @param askItem    the item asked in return, unmodified; empty for none
 * @param askCount   how many of it; 0 for none
 * @param askCredits the credits asked in return; 0 for none
 * @param expiresAt  economy time at which an unanswered offer lapses and everything goes back
 */
public record Offer(UUID id, AccountId sender, AccountId recipient, List<Lot> lots, long credits, String askItem,
        int askCount, long askCredits, long createdAt, long expiresAt) {
    public Offer {
        if (id == null || sender == null || recipient == null || lots == null || credits < 0L || askItem == null
                || askCount < 0 || askCredits < 0L || (askCount > 0) == askItem.isEmpty())
            throw new IllegalArgumentException("Invalid offer");
        lots = List.copyOf(lots);
    }

    public boolean asksItems() { return askCount > 0; }

    /** How many items the offer gives, of every kind together. */
    public long itemCount() {
        long total = 0L;
        for (Lot lot : lots) total += lot.count();
        return total;
    }
}
