package za.co.neroland.neroeconomy.economy;

import za.co.neroland.neroeconomy.goods.GoodsSource;

/**
 * The rules a Credit Chip is redeemed under, free of the game so they are unit tested directly.
 * {@code CreditChipItem} asks {@link #refusal} before it touches the chip, then pays the chip out of
 * the Reserve against {@link #BUDGET}, the server-wide {@code creditChipDailyBudget}, on top of each
 * account's own {@code creditChipDailyLimit}.
 */
public final class CreditChipRules {
    /** What chip payouts are counted against. */
    public static final BudgetKind BUDGET = BudgetKind.CREDIT_CHIP;

    private CreditChipRules() { }

    /**
     * Why a chip may not be redeemed right now, as a lang key; null when it may.
     *
     * @param frozen            the economy is frozen
     * @param creative          the player is in creative mode
     * @param spectator         the player is a spectator
     * @param infiniteMaterials the player has an endless supply of items
     * @param dailyLimit        {@code creditChipDailyLimit}; 0 or less switches redemption off
     */
    public static String refusal(boolean frozen, boolean creative, boolean spectator, boolean infiniteMaterials,
            int dailyLimit) {
        if (frozen) return EconomyHealth.FROZEN_KEY;
        if (dailyLimit <= 0) return "item.neroeconomy.credit_chip.disabled";
        // The chip is value from nowhere: nothing a creative or spectator player holds may become credits.
        if (creative || spectator || infiniteMaterials) return GoodsSource.REFUSED_KEY;
        return null;
    }
}
