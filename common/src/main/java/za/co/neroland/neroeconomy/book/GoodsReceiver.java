package za.co.neroland.neroeconomy.book;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * Where goods go that an order buys at once: the buyer's inventory when they are present and it
 * fits, otherwise their vault; a rule's inventory; a Merchant House's stock. The book asks
 * {@link #canTake} before any credit moves and refuses the order when the answer is no.
 */
public interface GoodsReceiver {
    /** Whether {@code lot} could be stored right now. Changes nothing. */
    boolean canTake(Lot lot);

    /**
     * Hands the goods over. Must never lose them: what does not fit where it was meant to go is
     * booked into a vault.
     *
     * @return true when the goods went to a vault rather than a live inventory
     */
    boolean give(Lot lot);
}
