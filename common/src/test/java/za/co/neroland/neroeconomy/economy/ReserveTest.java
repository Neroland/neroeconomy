package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/** The Reserve's rules against a few lines of fake ledger: no game, no config, no clock. */
class ReserveTest {
    private static final AccountId ALICE = new PlayerAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId BOB = new PlayerAccountId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final AccountId CAROL = new PlayerAccountId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
    private static final long DAY = 20_000L;
    /** NPC 1000 a day, sibling 50, everything else unlimited. */
    private static final Reserve.Limits LIMITS = kind -> switch (kind) {
        case NPC -> 1_000L;
        case SIBLING -> 50L;
        default -> Long.MAX_VALUE;
    };

    /** A ledger of one Reserve balance and what was paid out of it. */
    private static final class Book implements Reserve.Book {
        private long balance;
        private long minted;
        private int payments;
        private AccountId full;
        private final Map<AccountId, Long> paid = new LinkedHashMap<>();

        private Book(long balance) { this.balance = balance; }

        @Override public long reserveBalance() { return balance; }
        @Override public boolean canCredit(AccountId account, long amount) { return !account.equals(full); }
        @Override public void payOut(AccountId account, long amount, TransactionType reason) {
            if (amount > balance) throw new AssertionError("the Reserve was asked to pay what it does not hold");
            balance -= amount;
            payments++;
            paid.merge(account, amount, Long::sum);
        }
        @Override public boolean mintReserve(long amount) {
            balance += amount;
            minted += amount;
            return true;
        }
    }

    @Test void aPaymentTheReserveCannotCoverIsRefusedBeforeAnythingMoves() {
        Reserve reserve = new Reserve();
        Book book = new Book(100L);
        assertEquals(LedgerResult.RESERVE_SHORT, reserve.tryPay(book, LIMITS, DAY, BudgetKind.UNBUDGETED, ALICE,
                101L, TransactionType.CREDIT_CHIP_REDEMPTION));
        assertEquals(100L, book.balance);
        assertEquals(0, book.payments);
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.UNBUDGETED, ALICE, 100L,
                TransactionType.CREDIT_CHIP_REDEMPTION));
        assertEquals(0L, book.balance);
        assertEquals(100L, book.paid.get(ALICE));
        // Empty now: even one credit is refused, and the balance never goes below zero.
        assertEquals(LedgerResult.RESERVE_SHORT, reserve.tryPay(book, LIMITS, DAY, BudgetKind.UNBUDGETED, ALICE,
                1L, TransactionType.CREDIT_CHIP_REDEMPTION));
        assertEquals(0L, book.balance);
        assertEquals(1, book.payments);
    }

    @Test void aPaymentPastTheDaysBudgetIsRefusedBeforeAnythingMoves() {
        Reserve reserve = new Reserve();
        Book book = new Book(1_000_000L);
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.SIBLING, ALICE, 30L,
                TransactionType.SIBLING_PAYOUT));
        assertEquals(LedgerResult.BUDGET_SPENT, reserve.tryPay(book, LIMITS, DAY, BudgetKind.SIBLING, BOB, 21L,
                TransactionType.SIBLING_PAYOUT));
        assertEquals(999_970L, book.balance);
        assertEquals(1, book.payments);
        assertEquals(30L, reserve.used(BudgetKind.SIBLING, DAY));
        assertEquals(20L, reserve.remaining(BudgetKind.SIBLING, LIMITS, DAY));
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.SIBLING, BOB, 20L,
                TransactionType.SIBLING_PAYOUT));
        assertEquals(0L, reserve.remaining(BudgetKind.SIBLING, LIMITS, DAY));
        // One budget being spent leaves the others alone.
        assertEquals(1_000L, reserve.remaining(BudgetKind.NPC, LIMITS, DAY));
        assertEquals(Long.MAX_VALUE, reserve.remaining(BudgetKind.UNBUDGETED, LIMITS, DAY));
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.UNBUDGETED, BOB, 500L,
                TransactionType.CREDIT_CHIP_REDEMPTION));
        assertEquals(0L, reserve.used(BudgetKind.UNBUDGETED, DAY));
    }

    @Test void aNewEconomyDayStartsEveryBudgetAgain() {
        Reserve reserve = new Reserve();
        Book book = new Book(1_000_000L);
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.SIBLING, ALICE, 50L,
                TransactionType.SIBLING_PAYOUT));
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, ALICE, 400L,
                TransactionType.MARKET_PAYOUT));
        assertEquals(LedgerResult.BUDGET_SPENT, reserve.tryPay(book, LIMITS, DAY, BudgetKind.SIBLING, ALICE, 1L,
                TransactionType.SIBLING_PAYOUT));
        // Yesterday's use is not carried over, and is not reported for today.
        assertEquals(0L, reserve.used(BudgetKind.SIBLING, DAY + 1L));
        assertEquals(50L, reserve.remaining(BudgetKind.SIBLING, LIMITS, DAY + 1L));
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY + 1L, BudgetKind.SIBLING, ALICE, 50L,
                TransactionType.SIBLING_PAYOUT));
        assertEquals(50L, reserve.used(BudgetKind.SIBLING, DAY + 1L));
        assertEquals(0L, reserve.used(BudgetKind.NPC, DAY + 1L));
        assertEquals(1_000L, reserve.remaining(BudgetKind.NPC, LIMITS, DAY + 1L));
    }

    @Test void invalidPaymentsAreRefusedAndAnAccountThatCannotHoldItIsNotPaid() {
        Reserve reserve = new Reserve();
        Book book = new Book(1_000L);
        assertEquals(LedgerResult.INVALID, reserve.tryPay(book, LIMITS, DAY, null, ALICE, 1L, TransactionType.SIBLING_PAYOUT));
        assertEquals(LedgerResult.INVALID, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, ALICE, -1L, TransactionType.SIBLING_PAYOUT));
        assertEquals(LedgerResult.INVALID, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, null, 1L, TransactionType.SIBLING_PAYOUT));
        assertEquals(LedgerResult.INVALID, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, ALICE, 1L, null));
        // A payout is a transfer: it can never carry a minting or burning reason.
        assertEquals(LedgerResult.INVALID, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, ALICE, 1L, TransactionType.ADMIN_GRANT));
        assertEquals(LedgerResult.INVALID, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, ALICE, 1L, TransactionType.ADMIN_TAKE));
        book.full = BOB;
        assertEquals(LedgerResult.OVERFLOW, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, BOB, 1L, TransactionType.MARKET_PAYOUT));
        assertEquals(1_000L, book.balance);
        assertEquals(0, book.payments);
        assertEquals(0L, reserve.used(BudgetKind.NPC, DAY));
        // Paying nothing succeeds and counts for nothing.
        assertEquals(LedgerResult.APPLIED, reserve.tryPay(book, LIMITS, DAY, BudgetKind.NPC, ALICE, 0L, TransactionType.MARKET_PAYOUT));
        assertEquals(0, book.payments);
    }

    @Test void aBatchTheReserveCoversIsPaidInFull() {
        Reserve reserve = new Reserve();
        Book book = new Book(10_000L);
        Map<AccountId, Long> wanted = new LinkedHashMap<>();
        wanted.put(ALICE, 300L);
        wanted.put(BOB, 200L);
        Reserve.ProRata result = reserve.payProRata(book, LIMITS, DAY, BudgetKind.NPC, wanted, TransactionType.MARKET_PAYOUT);
        assertEquals(500L, result.requested());
        assertEquals(500L, result.paid());
        assertFalse(result.scaled());
        assertEquals(1.0D, result.factor());
        assertEquals(300L, result.shares().get(ALICE));
        assertEquals(200L, result.shares().get(BOB));
        assertEquals(9_500L, book.balance);
        assertEquals(500L, reserve.used(BudgetKind.NPC, DAY));
    }

    @Test void aBatchPastTheBudgetIsScaledDownByOneFactor() {
        Reserve reserve = new Reserve();
        Book book = new Book(10_000L);
        Map<AccountId, Long> wanted = new LinkedHashMap<>();
        wanted.put(ALICE, 3_000L);
        wanted.put(BOB, 1_000L);
        wanted.put(CAROL, 1L);
        // 4001 asked, 1000 left in the NPC budget: everybody gets 1000/4001 of what they asked.
        Reserve.ProRata result = reserve.payProRata(book, LIMITS, DAY, BudgetKind.NPC, wanted, TransactionType.MARKET_PAYOUT);
        assertTrue(result.scaled());
        assertEquals(4_001L, result.requested());
        assertEquals(1_000L, result.numerator());
        assertEquals(4_001L, result.denominator());
        assertEquals(1_000.0D / 4_001.0D, result.factor(), 1.0e-12D);
        assertEquals(749L, result.shares().get(ALICE));
        assertEquals(249L, result.shares().get(BOB));
        assertEquals(0L, result.shares().get(CAROL));
        assertEquals(998L, result.paid());
        assertEquals(10_000L - 998L, book.balance);
        assertEquals(998L, reserve.used(BudgetKind.NPC, DAY));
        // What rounding left of the budget is still there; a second batch shares that.
        Reserve.ProRata second = reserve.payProRata(book, LIMITS, DAY, BudgetKind.NPC, wanted, TransactionType.MARKET_PAYOUT);
        assertEquals(2L, second.numerator());
        assertTrue(second.paid() <= 2L);
        assertTrue(reserve.used(BudgetKind.NPC, DAY) <= 1_000L);
    }

    @Test void aBatchPastTheReserveBalanceIsScaledToWhatItHolds() {
        Reserve reserve = new Reserve();
        Book book = new Book(90L);
        Map<AccountId, Long> wanted = new LinkedHashMap<>();
        wanted.put(ALICE, 100L);
        wanted.put(BOB, 200L);
        Reserve.ProRata result = reserve.payProRata(book, LIMITS, DAY, BudgetKind.UNBUDGETED, wanted,
                TransactionType.MARKET_PAYOUT);
        assertEquals(30L, result.shares().get(ALICE));
        assertEquals(60L, result.shares().get(BOB));
        assertEquals(90L, result.paid());
        assertEquals(0L, book.balance);
        assertEquals(0.3D, result.factor(), 1.0e-12D);
        // Nothing left: the next batch pays nobody and the Reserve stays at zero.
        Reserve.ProRata empty = reserve.payProRata(book, LIMITS, DAY, BudgetKind.UNBUDGETED, wanted,
                TransactionType.MARKET_PAYOUT);
        assertEquals(0L, empty.paid());
        assertEquals(0.0D, empty.factor());
        assertEquals(0L, book.balance);
    }

    @Test void proRataSharesNeverPassWhatIsAvailable() {
        Random random = new Random(11L);
        for (int round = 0; round < 3_000; round++) {
            Map<AccountId, Long> wanted = new LinkedHashMap<>();
            int accounts = 1 + random.nextInt(8);
            for (int index = 0; index < accounts; index++)
                wanted.put(new PlayerAccountId(new UUID(0L, index + 1L)),
                        random.nextInt(5) == 0 ? Long.MAX_VALUE / 2L : (long) random.nextInt(10_000));
            long available = random.nextInt(4) == 0 ? 0L : (long) random.nextInt(20_000);
            Reserve.ProRata shares = Reserve.shares(wanted, available);
            long total = 0L;
            for (Map.Entry<AccountId, Long> share : shares.shares().entrySet()) {
                assertTrue(share.getValue() >= 0L);
                assertTrue(share.getValue() <= wanted.get(share.getKey()));
                total += share.getValue();
            }
            assertEquals(total, shares.paid());
            if (shares.scaled()) assertTrue(total <= available, total + " of " + available);
            assertTrue(shares.factor() >= 0.0D && shares.factor() <= 1.0D);
        }
        // An empty or missing batch asks for nothing.
        assertEquals(0L, Reserve.shares(Map.of(), 100L).paid());
        assertEquals(0L, Reserve.shares(null, 100L).paid());
        Map<AccountId, Long> negative = new LinkedHashMap<>();
        negative.put(ALICE, -5L);
        assertEquals(0L, Reserve.shares(negative, 100L).shares().get(ALICE));
    }

    @Test void theInitialFillHappensOnce() {
        Reserve reserve = new Reserve();
        Book book = new Book(0L);
        assertFalse(reserve.seeded());
        assertEquals(250_000L, reserve.seed(book, DAY, 250_000L));
        assertTrue(reserve.seeded());
        assertEquals(0L, reserve.seed(book, DAY, 250_000L));
        assertEquals(0L, reserve.seed(book, DAY + 5L, 250_000L));
        assertEquals(250_000L, book.balance);
        assertEquals(250_000L, book.minted);
    }

    @Test void theDailyTopUpRunsOncePerEconomyDay() {
        Reserve reserve = new Reserve();
        Book book = new Book(0L);
        // Nothing is topped up before the Reserve has had its initial fill.
        assertEquals(0L, reserve.topUp(book, DAY, 5_000L, 2_000_000L));
        reserve.seed(book, DAY, 100_000L);
        // The day of the fill is not topped up as well.
        assertEquals(0L, reserve.topUp(book, DAY, 5_000L, 2_000_000L));
        assertEquals(5_000L, reserve.topUp(book, DAY + 1L, 5_000L, 2_000_000L));
        assertEquals(0L, reserve.topUp(book, DAY + 1L, 5_000L, 2_000_000L));
        assertEquals(105_000L, book.balance);
        // A clock that reports an earlier day mints nothing.
        assertEquals(0L, reserve.topUp(book, DAY, 5_000L, 2_000_000L));
        assertEquals(105_000L, book.balance);
    }

    @Test void theDailyTopUpCatchesUpAtMostThreeMissedDays() {
        Reserve reserve = new Reserve();
        Book book = new Book(0L);
        reserve.seed(book, DAY, 0L);
        assertEquals(10_000L, reserve.topUp(book, DAY + 2L, 5_000L, 2_000_000L));
        // Thirty days away: three top-ups, not thirty, and the days beyond are gone for good.
        assertEquals(15_000L, reserve.topUp(book, DAY + 32L, 5_000L, 2_000_000L));
        assertEquals(0L, reserve.topUp(book, DAY + 32L, 5_000L, 2_000_000L));
        assertEquals(25_000L, book.balance);
        assertEquals(Reserve.MAX_CATCH_UP_DAYS, 3);
    }

    @Test void theDailyTopUpStopsAtTheCap() {
        Reserve reserve = new Reserve();
        Book book = new Book(0L);
        reserve.seed(book, DAY, 9_000L);
        // 1000 short of the cap: the top-up brings it to the cap, never past it.
        assertEquals(1_000L, reserve.topUp(book, DAY + 1L, 5_000L, 10_000L));
        assertEquals(10_000L, book.balance);
        assertEquals(0L, reserve.topUp(book, DAY + 2L, 5_000L, 10_000L));
        // Fees may take the Reserve above the cap; the top-up stays off while it is there.
        book.balance = 50_000L;
        assertEquals(0L, reserve.topUp(book, DAY + 3L, 5_000L, 10_000L));
        assertEquals(50_000L, book.balance);
        // Three missed days with room for one and a half: it stops when the cap is reached.
        book.balance = 2_500L;
        assertEquals(7_500L, reserve.topUp(book, DAY + 10L, 5_000L, 10_000L));
        assertEquals(10_000L, book.balance);
        assertEquals(0L, Reserve.topUpAmount(10_000L, 5_000L, 10_000L));
        assertEquals(0L, Reserve.topUpAmount(0L, 0L, 10_000L));
        assertEquals(5_000L, Reserve.topUpAmount(0L, 5_000L, 10_000L));
        assertEquals(1L, Reserve.topUpAmount(9_999L, 5_000L, 10_000L));
    }

    @Test void theReserveNeverGoesNegativeUnderAnyRunOfPayments() {
        Random random = new Random(20_261_003L);
        Reserve reserve = new Reserve();
        Book book = new Book(5_000L);
        long day = DAY;
        for (int round = 0; round < 20_000; round++) {
            if (random.nextInt(200) == 0) day++;
            BudgetKind kind = BudgetKind.values()[random.nextInt(BudgetKind.values().length)];
            long amount = random.nextInt(400);
            if (random.nextInt(10) == 0) {
                Map<AccountId, Long> wanted = new LinkedHashMap<>();
                wanted.put(ALICE, amount);
                wanted.put(BOB, (long) random.nextInt(400));
                reserve.payProRata(book, LIMITS, day, kind, wanted, TransactionType.MARKET_PAYOUT);
            } else {
                long before = book.balance;
                LedgerResult result = reserve.tryPay(book, LIMITS, day, kind, ALICE, amount, TransactionType.MARKET_PAYOUT);
                if (result != LedgerResult.APPLIED) assertEquals(before, book.balance);
                else assertEquals(before - amount, book.balance);
            }
            assertTrue(book.balance >= 0L);
            assertTrue(reserve.used(BudgetKind.NPC, day) <= 1_000L);
            assertTrue(reserve.used(BudgetKind.SIBLING, day) <= 50L);
            if (random.nextInt(50) == 0) book.balance += random.nextInt(2_000);
        }
    }

    @Test void theReserveAccountIsNamedLikeEverySystemAccount() {
        assertEquals("system:reserve", SystemAccountId.RESERVE.stableKey());
    }
}
