package za.co.neroland.neroeconomy.market;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Outcome of a player cancelling one of their own market rows, jobs or auctions. A row that is not
 * the caller's is reported as {@link #NOT_FOUND}, so a cancel attempt reveals nothing about rows
 * that belong to somebody else.
 */
public enum CancelResult {
    CANCELLED, NOT_FOUND, NOT_CANCELLABLE, CLAIMS_FULL, REFUND_BLOCKED,
    /** The economy is frozen for this session; nothing can be cancelled or refunded. */
    FROZEN;

    /** Lang key of the message shown to the caller. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        return "command.neroeconomy.cancel." + name().toLowerCase(Locale.ROOT);
    }
}
