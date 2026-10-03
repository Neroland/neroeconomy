package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/** A Credit Chip is worth less with every one an account redeems in a day, and the Reserve pays it. */
class CreditChipScheduleTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void theValueStepsDownAcrossTheDay() {
        assertEquals(100L, CreditChipSchedule.value(100L, 0));
        assertEquals(80L, CreditChipSchedule.value(100L, 1));
        assertEquals(60L, CreditChipSchedule.value(100L, 2));
        assertEquals(40L, CreditChipSchedule.value(100L, 3));
        assertEquals(20L, CreditChipSchedule.value(100L, 4));
        // A server that allows more than five a day keeps paying the lowest step.
        assertEquals(20L, CreditChipSchedule.value(100L, 5));
        assertEquals(20L, CreditChipSchedule.value(100L, 999));
        assertEquals(100, CreditChipSchedule.percent(-1));
        assertEquals(20, CreditChipSchedule.percent(Integer.MAX_VALUE));
    }

    @Test void theValueIsRoundedDownButNeverToNothing() {
        assertEquals(7L, CreditChipSchedule.value(9L, 1));
        assertEquals(1L, CreditChipSchedule.value(1L, 4));
        assertEquals(1L, CreditChipSchedule.value(4L, 4));
        assertEquals(0L, CreditChipSchedule.value(0L, 0));
        assertEquals(Long.MAX_VALUE, CreditChipSchedule.value(Long.MAX_VALUE, 0));
        assertTrue(CreditChipSchedule.value(Long.MAX_VALUE, 4) > 0L);
        assertEquals(Long.MAX_VALUE / 100L * 20L + Long.MAX_VALUE % 100L * 20L / 100L,
                CreditChipSchedule.value(Long.MAX_VALUE, 4));
    }

    @Test void aDaysChipsArePaidByTheReserveAtTheSteppedValue() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.open(data, ALICE);
        long reserve = data.balance(SystemAccountId.RESERVE);
        long paid = 0L;
        for (int chip = 0; chip < 5; chip++) {
            int redeemed = data.chipRedemptionsToday(ALICE);
            assertEquals(chip, redeemed);
            long value = CreditChipSchedule.value(100L, redeemed);
            assertTrue(data.reserveChipRedemption(ALICE, 5));
            assertEquals(LedgerResult.APPLIED, data.payFromReserve(BudgetKind.UNBUDGETED, new PlayerAccountId(ALICE),
                    value, TransactionType.CREDIT_CHIP_REDEMPTION));
            paid += value;
        }
        assertEquals(300L, paid);
        assertEquals(Ledgers.STARTING + 300L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(reserve - 300L, data.balance(SystemAccountId.RESERVE));
        // The sixth is over the daily limit, and nothing more leaves the Reserve.
        assertEquals(5, data.chipRedemptionsToday(ALICE));
        assertTrue(!data.reserveChipRedemption(ALICE, 5));
        assertTrue(data.supplyBalanced());
    }

    @Test void aChipIsRefusedWhenTheReserveCannotPayAndItsReservationIsHandedBack() {
        EconomySavedData data = new EconomySavedData();
        // No Reserve at all: the account opens with nothing and the chip cannot be paid.
        assertTrue(data.reserveChipRedemption(ALICE, 5));
        assertEquals(LedgerResult.RESERVE_SHORT, data.payFromReserve(BudgetKind.UNBUDGETED,
                new PlayerAccountId(ALICE), 100L, TransactionType.CREDIT_CHIP_REDEMPTION));
        data.releaseChipRedemption(ALICE);
        assertEquals(0, data.chipRedemptionsToday(ALICE));
        assertEquals(0L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.totalBalancesForTest());
    }
}
