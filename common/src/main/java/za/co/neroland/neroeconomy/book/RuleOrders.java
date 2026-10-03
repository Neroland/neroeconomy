package za.co.neroland.neroeconomy.book;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * What the order book asks of the auto-trade rules about an order one of them placed
 * ({@link OrderSource#RULE}). The rules section implements it; the book reaches it through the
 * store, so the two are saved together and can never disagree after a restart.
 *
 * <p>None of these may place, cancel or change an order: they only record. A rule reacts to what
 * it was told the next time it is evaluated. An "order id" is the id of the rule's order, or the
 * rule's own id for a purchase it makes without an order (a sweep).
 */
public interface RuleOrders {
    /**
     * How many units of a rule's order may fill right now at {@code unitPrice}: limited by the
     * owner's daily value cap. 0 leaves the order alone.
     */
    int allowance(TradeEnv env, UUID orderId, long unitPrice, int wanted, long now);

    /**
     * A rule's order filled.
     *
     * @param buy    whether the order is the buying side
     * @param base   the fill's value at the seller's price
     * @param paid   what the buyer paid for the fill, after any surcharge
     * @param refund for a buy order, escrow the fill did not use; it stays in the escrow account
     *               and is the rule's budget again
     */
    void filled(UUID orderId, boolean buy, int quantity, long base, long paid, long refund, long now);

    /**
     * Goods a rule's buy order bought, for the rule's inventory.
     *
     * @return false when no rule knows the order, so the book delivers to the owner's vault instead
     */
    boolean deliver(TradeEnv env, UUID orderId, Lot lot);

    /**
     * A rule's order left the book: completely filled, expired, or removed.
     *
     * @param escrowBack for a buy order, the escrow it still held; it stays in the escrow account
     *                   and is the rule's budget again
     * @param goodsBack  for a sell order, the goods it still held, for the rule's inventory
     * @return false when no rule knows the order, so the book returns both to the owner instead
     */
    boolean closed(TradeEnv env, UUID orderId, long escrowBack, Lot goodsBack);

    /** Something about this item changed on the book; rules that trade it want another look. */
    void changed(String item);

    /**
     * Whether {@code orderId} is the order a rule of {@code owner} is placing or keeps (or, for a
     * sweep, the rule itself). The book refuses to place anything as a rule's that no rule owns:
     * a rule's buy order is paid for out of escrow the rule already holds, so nothing else may
     * claim to be one.
     */
    boolean owns(UUID orderId, AccountId owner);
}
