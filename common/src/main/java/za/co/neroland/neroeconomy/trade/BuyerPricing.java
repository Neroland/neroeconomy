package za.co.neroland.neroeconomy.trade;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * What one buyer pays per unit for goods offered at a seller's price: the price after market
 * shocks and the price modifiers other mods registered. It changes the buyer's price only — the
 * seller always receives their own price less the fee, a discount is paid by the Reserve and a
 * surcharge goes to it. The game answers through {@code PricingEngine}; a test passes a function.
 *
 * <p>An implementation must be deterministic and must never return less than 1. The order book
 * never asks it about an order a system account owns, or on behalf of a system account.
 */
@FunctionalInterface
public interface BuyerPricing {
    /** No shocks and no modifiers: the buyer pays the seller's price. */
    BuyerPricing NONE = (buyer, listing, item, baseUnitPrice, available) -> baseUnitPrice;

    /**
     * @param buyer         who is buying
     * @param listing       the id of the sell order being bought from (or being placed)
     * @param item          the item id
     * @param baseUnitPrice the seller's own price per unit
     * @param available     how many are on offer
     * @return the buyer's price per unit, at least 1
     */
    long unitPrice(AccountId buyer, UUID listing, String item, long baseUnitPrice, int available);
}
