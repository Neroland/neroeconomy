package za.co.neroland.neroeconomy.market;

/** Stable server result for GUI, commands, and Link actions. */
public enum PurchaseResult {
    SUCCESS, STALE_REQUEST, NOT_FOUND, OWN_LISTING, INVALID_QUANTITY, OUT_OF_STOCK,
    PRICE_MOVED, INSUFFICIENT_FUNDS, INVENTORY_FULL, INTERNAL_ERROR
}
