package za.co.neroland.neroeconomy.book;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * Where a seller's goods come from: their vault, their hand, a rule's inventory, a Merchant
 * House's stock. The book asks {@link #holds} before anything moves, {@link #take}s the goods
 * before the sale is settled, and {@link #restore}s them if it then cannot be.
 */
public interface GoodsOrigin {
    /** Whether the origin holds all of {@code lot} right now. Changes nothing. */
    boolean holds(Lot lot);

    /** Takes exactly {@code lot}. @return false, with nothing taken, when the origin holds fewer */
    boolean take(Lot lot);

    /** Puts back goods that were taken for a sale that then did not happen. Must not lose them. */
    void restore(Lot lot);
}
