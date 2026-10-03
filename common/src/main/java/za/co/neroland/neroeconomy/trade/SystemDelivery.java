package za.co.neroland.neroeconomy.trade;

import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.goods.Lot;

/**
 * Where goods go that the order book owes an account which is not a player: what a Merchant House
 * bought through a buy order, and the stock of a House sell order that was cancelled or expired.
 * A player's goods go to their vault; a system account has none, so whoever owns the account's
 * stock plugs this in ({@code Trading.systemDelivery}).
 *
 * <p>Until something is plugged in, the order book refuses to create an order for a system
 * account at all, so there is never anything to deliver. An implementation must always take what
 * it is handed: the book calls it after the credits have moved.
 */
public interface SystemDelivery {
    /** Whether this handler keeps the stock of {@code account}. */
    boolean handles(SystemAccountId account);

    /** Books {@code lot} into the account's stock. Must not refuse and must not throw. */
    void deliver(SystemAccountId account, Lot lot);
}
