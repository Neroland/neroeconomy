package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.goods.GoodsSource;

/**
 * C1 and A2: a Credit Chip is never redeemed by a creative, spectator or endless-supply player,
 * and chips across every account together draw at most the server-wide daily chip budget from
 * the Reserve, on top of each account's own daily count.
 */
class CreditChipRulesTest {
    private static long budget() { return EconomyConfig.CREDIT_CHIP_DAILY_BUDGET.get(); }

    @Test void aCreativeSpectatorOrEndlessSupplyPlayerCannotRedeemAChip() {
        assertEquals(GoodsSource.REFUSED_KEY, CreditChipRules.refusal(false, true, false, false, 5));
        assertEquals(GoodsSource.REFUSED_KEY, CreditChipRules.refusal(false, false, true, false, 5));
        assertEquals(GoodsSource.REFUSED_KEY, CreditChipRules.refusal(false, false, false, true, 5));
        assertNull(CreditChipRules.refusal(false, false, false, false, 5));
        assertEquals(EconomyHealth.FROZEN_KEY, CreditChipRules.refusal(true, true, false, false, 5));
        assertEquals("item.neroeconomy.credit_chip.disabled", CreditChipRules.refusal(false, false, false, false, 0));
    }

    @Test void chipsAcrossManyAccountsDrawAtMostTheServerWideDailyBudget() {
        EconomySavedData data = Ledgers.seeded();
        long reserve = data.balance(SystemAccountId.RESERVE);
        long paid = 0L;
        int refused = 0;
        // Forty alts, each redeeming its five chips a day: 100 + 80 + 60 + 40 + 20 credits each.
        for (int number = 0; number < 40; number++) {
            UUID alt = new UUID(3L, number);
            Ledgers.open(data, alt);
            for (int chip = 0; chip < 5; chip++) {
                if (!data.reserveChipRedemption(alt, 5)) break;
                long value = CreditChipSchedule.value(EconomyConfig.CREDIT_CHIP_VALUE.get(), chip);
                LedgerResult result = data.apply(LedgerPlan.pay(CreditChipRules.BUDGET, new PlayerAccountId(alt), value,
                        TransactionType.CREDIT_CHIP_REDEMPTION));
                if (result.ok()) {
                    paid += value;
                } else {
                    assertEquals(LedgerResult.BUDGET_SPENT, result);
                    data.releaseChipRedemption(alt);
                    refused++;
                }
            }
        }
        assertTrue(paid <= budget(), "chips drew " + paid);
        assertTrue(paid > budget() - EconomyConfig.CREDIT_CHIP_VALUE.get(), "the budget is there to be used: " + paid);
        assertTrue(refused > 0);
        // Only chips came out of the Reserve besides the forty starting balances.
        assertEquals(reserve - paid - 40L * Ledgers.STARTING, data.balance(SystemAccountId.RESERVE));
        assertEquals(paid + 40L * Ledgers.STARTING, totalBalances(data));
    }

    private static long totalBalances(EconomySavedData data) {
        long total = 0L;
        for (int number = 0; number < 40; number++)
            total += data.getBalance(new UUID(3L, number), NeroEconomyCommon.CREDITS);
        return total;
    }
}
