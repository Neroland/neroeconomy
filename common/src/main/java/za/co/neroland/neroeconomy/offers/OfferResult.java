package za.co.neroland.neroeconomy.offers;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Why an offer operation did or did not happen, so the player is told the actual reason. Nothing
 * has moved unless the result is {@link #OK}.
 */
public enum OfferResult {
    OK,
    /** An amount, a quantity or a lot the offer does not accept. */
    INVALID,
    /** The request id was already acted on. */
    STALE_REQUEST,
    /** The offer gives nothing at all. */
    EMPTY,
    /** An offer cannot be sent to oneself. */
    SELF,
    /** The recipient is nobody this world knows. */
    UNKNOWN_RECIPIENT,
    /**
     * The recipient has muted offers, or already has as many waiting as an account may. The sender
     * is told the same either way, so the message says nothing about the recipient.
     */
    NOT_ACCEPTING,
    /** The sender already has as many open offers as the server allows. */
    LIMIT_REACHED,
    /** The sender does not hold the goods, in the hand or in the vault. */
    NOT_HOLDING,
    /** An item given or asked is in {@code neroeconomy:untradeable}. */
    UNTRADEABLE,
    /** A stack carries more data than {@code maxStackDataBytes} allows in escrow. */
    OVERSIZED,
    /** The item asked for is not an item. */
    UNKNOWN_ITEM,
    /** The payer does not have the credits. */
    INSUFFICIENT_FUNDS,
    /** The payer has the credits, but part of them is a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** Goods may not leave a creative-mode or spectator inventory into the economy. */
    CREATIVE,
    /** No open offer of the caller's has that id. */
    NOT_FOUND,
    /** The recipient's vault does not hold the unmodified items the offer asks for. */
    MISSING_ITEMS,
    /** The caller's vault cannot take the goods. */
    VAULT_FULL,
    /** The other side's vault cannot take the goods it would receive. */
    OTHER_VAULT_FULL,
    /** Credits owed could not be booked. */
    PAYOUT_BLOCKED,
    /** The economy is frozen for this session. */
    FROZEN,
    /** The swap could not be settled; nothing moved. */
    INTERNAL,
    /** An operator has closed the market for now; reads, collecting and refunds still work. */
    MARKET_CLOSED,
    /** The sender already has as many open offers waiting with this recipient as one account may. */
    PAIR_LIMIT;

    public boolean ok() { return this == OK; }

    /** Lang key of the message shown to the player. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        if (this == MARKET_CLOSED) return EconomyHealth.MARKET_CLOSED_KEY;
        return "message.neroeconomy.offer." + name().toLowerCase(Locale.ROOT);
    }
}
