package za.co.neroland.neroeconomy.book;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * What the order book asks the Merchant Houses about their orders while it matches, and tells them
 * after every fill. The store's House section answers ({@code EconomyStore.houseOrders()}); without
 * one the book never consults it.
 *
 * <p>Both calls are made from inside the book's matching and settlement, so a House's daily caps —
 * global and per account — are enforced on every fill, whichever side placed it and however it
 * was placed: a buy order, a sell order, a direct purchase, a filled buy order or a rule's sweep.
 */
public interface HouseOrders {
    /**
     * How many of {@code wanted} units may fill now between the House order {@code houseOrder} and
     * {@code counterparty}: what is left of the commodity's daily cap and of that account's own
     * daily cap with the Houses. 0 for an account that is not a player's, and for an order the
     * Houses do not know.
     *
     * @param now economy time, which decides the economy-day the caps count in
     */
    int allowance(UUID houseOrder, AccountId counterparty, int wanted, long now);

    /**
     * One fill was settled. A fill against a House order counts toward its caps and its price
     * pressure; a fill between two players can count toward the drift of a reference price.
     */
    void filled(Fill fill, long now);
}
