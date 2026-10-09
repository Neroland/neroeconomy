package za.co.neroland.neroeconomy.book;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Why an order-book operation did or did not happen, so the player is told the actual reason.
 * Nothing has moved unless the result is {@link #OK}.
 */
public enum BookResult {
    OK,
    /** A quantity, price or owner the book does not accept, or a total that overflows. */
    INVALID,
    /** The request id was already acted on. */
    STALE_REQUEST,
    /** The account already has as many open orders of this kind as the server allows. */
    LIMIT_REACHED,
    /** The seller does not hold the goods, in the hand or in the vault, in the quantity offered. */
    NOT_HOLDING,
    /** The item id names nothing. */
    UNKNOWN_ITEM,
    /** The item is in {@code neroeconomy:untradeable}. */
    UNTRADEABLE,
    /** The stack carries more data than {@code maxStackDataBytes} allows in escrow. */
    OVERSIZED,
    /** The buyer does not have the credits. */
    INSUFFICIENT_FUNDS,
    /** The buyer has the credits, but part of them is a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** The listing fee could not be paid. */
    FEE_UNPAID,
    /** The goods the order would receive at once fit neither the inventory nor the vault. */
    STORAGE_FULL,
    /** No open order has that id. */
    NOT_FOUND,
    /** The order is the caller's own. */
    OWN_ORDER,
    /** The sell order holds fewer than asked for. */
    OUT_OF_STOCK,
    /** The price to the buyer is now above what they agreed to. */
    PRICE_MOVED,
    /** A buy order names an item id, so only unmodified goods fill it. */
    MODIFIED_ITEM,
    /** The goods offered are not the item the buy order wants. */
    WRONG_ITEM,
    /** The buy order wants fewer than offered. */
    NOT_ENOUGH,
    /** The seller's proceeds could not be booked. */
    PAYOUT_BLOCKED,
    /** Goods may not leave a creative-mode or spectator inventory into the economy. */
    CREATIVE,
    /** The economy is frozen for this session. */
    FROZEN,
    /** The sale could not be settled; nothing moved. */
    INTERNAL,
    /** The account has traded as much of this commodity with the Merchant Houses today as it may. */
    DAILY_LIMIT;

    public boolean ok() { return this == OK; }

    /** Lang key of the message shown to the player. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        return "message.neroeconomy.order." + name().toLowerCase(Locale.ROOT);
    }
}
