package za.co.neroland.neroeconomy.market;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Stable server result for GUI, commands, and Link actions. Constants are only ever appended: the
 * names travel to Link clients, so an existing one is never renamed, removed or reordered.
 */
public enum PurchaseResult {
    SUCCESS, STALE_REQUEST, NOT_FOUND, OWN_LISTING, INVALID_QUANTITY, OUT_OF_STOCK,
    PRICE_MOVED, INSUFFICIENT_FUNDS, INVENTORY_FULL, INTERNAL_ERROR,
    /** The listing no longer sells the item the buyer was looking at. */
    ITEM_CHANGED,
    /**
     * The buyer's vault cannot take the goods (and, for a buyer who is in game, neither can their
     * inventory). The name is from when the vault was the item-claim list; it travels to Link
     * clients, so it stays.
     */
    CLAIMS_FULL,
    /** The buyer has the credits, but part of them is a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** The economy is frozen for this session; nothing can be bought. */
    FROZEN;

    /** Lang key of the player-facing wording for this result. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        return "gui.neroeconomy.purchase_result." + name().toLowerCase(Locale.ROOT);
    }
}
