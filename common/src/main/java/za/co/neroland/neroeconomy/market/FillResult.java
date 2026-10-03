package za.co.neroland.neroeconomy.market;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/** Why a buy offer was or was not filled, so the seller is told the actual reason. */
public enum FillResult {
    SUCCESS, INVALID, STALE_REQUEST, NOT_FOUND, OWN_OFFER, WRONG_ITEM, NOT_ENOUGH, MODIFIED_ITEM,
    UNTRADEABLE, CLAIMS_FULL, PAYOUT_BLOCKED,
    /** The economy is frozen for this session; nothing can be sold. */
    FROZEN,
    /** Goods may not leave a creative-mode or spectator inventory into the economy. */
    CREATIVE;

    /** Lang key of the message shown to the seller. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        return "gui.neroeconomy.fill_result." + name().toLowerCase(Locale.ROOT);
    }
}
