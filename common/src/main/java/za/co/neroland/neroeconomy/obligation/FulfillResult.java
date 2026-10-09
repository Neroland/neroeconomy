package za.co.neroland.neroeconomy.obligation;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/** Why a contract or delivery was or was not fulfilled, so the worker is told the actual reason. */
public enum FulfillResult {
    SUCCESS, NOT_CLAIMED, NOT_AT_DESTINATION, MISSING_ITEMS, MODIFIED_ITEMS, ISSUER_CLAIMS_FULL,
    PAYOUT_BLOCKED,
    /** The economy is frozen for this session; nothing can be settled. */
    FROZEN,
    /** Goods may not leave a creative-mode or spectator inventory into the economy. */
    CREATIVE,
    /** An operator has closed the market; jobs cannot be fulfilled until it opens again. */
    MARKET_CLOSED;

    /** Lang key of the message shown to the worker. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        if (this == MARKET_CLOSED) return EconomyHealth.MARKET_CLOSED_KEY;
        return "command.neroeconomy.fulfill." + name().toLowerCase(Locale.ROOT);
    }
}
