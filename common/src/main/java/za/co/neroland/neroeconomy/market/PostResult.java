package za.co.neroland.neroeconomy.market;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Why a Market Board sell offer or buy offer was or was not posted, so the player is told the
 * actual reason instead of a generic failure. The checks and their order are the store's own; this
 * only names which one refused.
 */
public enum PostResult {
    SUCCESS,
    /** A quantity, price or expiry the store does not accept, or a total that overflows. */
    INVALID,
    /** The request id was already acted on. */
    STALE_REQUEST,
    /** A sell offer needs the stock in the main hand, in at least the quantity offered. */
    NOT_HOLDING,
    /** The player already has as many open rows of this kind as the server allows. */
    LIMIT_REACHED,
    /** The item is in {@code neroeconomy:untradeable}. */
    UNTRADEABLE,
    /** The stack carries more data than {@code maxStackDataBytes} allows in escrow. */
    OVERSIZED,
    /** A buy offer named an item id that is not registered. */
    UNKNOWN_ITEM,
    /** A buy offer's escrow could not be debited. */
    INSUFFICIENT_FUNDS,
    /** The listing fee could not be paid. */
    FEE_UNPAID,
    /** A buy offer's escrow would have to come out of a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** The economy is frozen for this session; nothing can be posted. */
    FROZEN,
    /** A buy order would be filled at once, and neither the inventory nor the vault can take the goods. */
    STORAGE_FULL,
    /** A sell order would be filled at once, and the proceeds could not be booked. */
    PAYOUT_BLOCKED,
    /** Goods may not leave a creative-mode or spectator inventory into the economy. */
    CREATIVE,
    /** An operator has closed the market for now; reads, collecting and refunds still work. */
    MARKET_CLOSED;

    /** Lang key of the message shown to the player. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        if (this == MARKET_CLOSED) return EconomyHealth.MARKET_CLOSED_KEY;
        return "gui.neroeconomy.post_result." + name().toLowerCase(Locale.ROOT);
    }
}
