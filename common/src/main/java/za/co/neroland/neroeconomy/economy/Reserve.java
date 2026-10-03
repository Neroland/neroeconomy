package za.co.neroland.neroeconomy.economy;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * The rules of the Reserve: the one pool every system payout comes from. It decides whether a
 * payment may be made, keeps the day's use of each budget, and works out the daily top-up. The
 * credits themselves live in the ledger, which this class reaches only through {@link Book}, so
 * every rule here runs in plain JUnit against a few lines of fake ledger.
 *
 * <ul>
 *   <li>A payment the Reserve's balance or the day's budget cannot cover is refused <b>before
 *       anything moves</b>.</li>
 *   <li>A batch is scaled down pro rata instead, and the factor applied is reported.</li>
 *   <li>The Reserve can never go negative, and nothing is paid that was not in it.</li>
 *   <li>The daily top-up runs once per economy day, catches up at most
 *       {@link #MAX_CATCH_UP_DAYS} missed days and stops at the cap.</li>
 * </ul>
 */
public final class Reserve {
    /** The most missed economy days one top-up pass makes up for. */
    public static final int MAX_CATCH_UP_DAYS = 3;

    /** As much of the ledger as the Reserve needs. */
    public interface Book {
        long reserveBalance();
        /** Whether {@code account} exists as a destination and can hold {@code amount} more. */
        boolean canCredit(AccountId account, long amount);
        /** Moves credits out of the Reserve; only called after every check has passed. */
        void payOut(AccountId account, long amount, TransactionType reason);
        /** Mints into the Reserve. @return false when the supply cannot grow by that much */
        boolean mintReserve(long amount);
    }

    /** The daily limit of each budget, in credits; {@link Long#MAX_VALUE} is no limit. */
    @FunctionalInterface
    public interface Limits {
        long dailyLimit(BudgetKind kind);
    }

    /**
     * The outcome of a pro-rata batch.
     *
     * @param requested   what the batch asked for in total
     * @param paid        what was actually paid in total
     * @param numerator   with {@code denominator}, the factor every amount was scaled by (rounded down)
     * @param shares      what each account was paid, in the order asked; accounts paid nothing are 0
     */
    public record ProRata(long requested, long paid, long numerator, long denominator,
            Map<AccountId, Long> shares) {
        public ProRata { shares = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(shares)); }
        /** The factor applied, from 0 to 1, for display. */
        public double factor() { return denominator <= 0L ? 0.0D : (double) numerator / (double) denominator; }
        public boolean scaled() { return numerator < denominator; }
    }

    private final EnumMap<BudgetKind, Long> used = new EnumMap<>(BudgetKind.class);
    private long budgetDay;
    private long lastMintDay;
    private boolean seeded;

    // --- budgets -----------------------------------------------------------------------------

    /** What this kind has drawn from the Reserve in economy day {@code today}. */
    public long used(BudgetKind kind, long today) {
        return kind == null || today != budgetDay ? 0L : used.getOrDefault(kind, 0L);
    }

    /** What this kind may still draw today; {@link Long#MAX_VALUE} when it has no limit. */
    public long remaining(BudgetKind kind, Limits limits, long today) {
        if (kind == null || kind == BudgetKind.UNBUDGETED) return Long.MAX_VALUE;
        long limit = limits == null ? 0L : Math.max(0L, limits.dailyLimit(kind));
        if (limit == Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0L, limit - used(kind, today));
    }

    /**
     * The refuse-before-move decision for one payment of {@code amount}, given the Reserve's
     * balance. Changes nothing.
     */
    public LedgerResult check(BudgetKind kind, long amount, long reserveBalance, Limits limits, long today) {
        if (kind == null || amount < 0L) return LedgerResult.INVALID;
        if (amount > reserveBalance) return LedgerResult.RESERVE_SHORT;
        if (amount > remaining(kind, limits, today)) return LedgerResult.BUDGET_SPENT;
        return LedgerResult.APPLIED;
    }

    /** Counts a payment that has been made against its budget; a new day starts every count at zero. */
    public void spend(BudgetKind kind, long amount, long today) {
        if (kind == null || amount <= 0L) return;
        roll(today);
        if (kind == BudgetKind.UNBUDGETED) return;
        long current = used.getOrDefault(kind, 0L);
        used.put(kind, Long.MAX_VALUE - current < amount ? Long.MAX_VALUE : current + amount);
    }

    private void roll(long today) {
        if (today == budgetDay) return;
        budgetDay = today;
        used.clear();
    }

    // --- payments ----------------------------------------------------------------------------

    /**
     * Pays {@code amount} from the Reserve to {@code account}, or refuses and moves nothing.
     *
     * @return {@link LedgerResult#APPLIED}, or the reason: {@code RESERVE_SHORT}, {@code BUDGET_SPENT},
     *         {@code OVERFLOW} (the account cannot hold it) or {@code INVALID}
     */
    public LedgerResult tryPay(Book book, Limits limits, long today, BudgetKind kind, AccountId account,
            long amount, TransactionType reason) {
        if (book == null || account == null || reason == null || reason.mints() || reason.burns())
            return LedgerResult.INVALID;
        LedgerResult verdict = check(kind, amount, book.reserveBalance(), limits, today);
        if (verdict != LedgerResult.APPLIED) return verdict;
        if (!book.canCredit(account, amount)) return LedgerResult.OVERFLOW;
        if (amount == 0L) return LedgerResult.APPLIED;
        book.payOut(account, amount, reason);
        spend(kind, amount, today);
        return LedgerResult.APPLIED;
    }

    /**
     * Pays a batch. When the Reserve and the day's budget cover the whole batch every account is
     * paid in full; otherwise every amount is scaled by the same factor, rounded down, so the total
     * never passes what is available. An account that cannot hold its share is paid nothing.
     */
    public ProRata payProRata(Book book, Limits limits, long today, BudgetKind kind,
            Map<AccountId, Long> amounts, TransactionType reason) {
        Map<AccountId, Long> paid = new LinkedHashMap<>();
        if (book == null || kind == null || amounts == null || reason == null || reason.mints() || reason.burns())
            return new ProRata(0L, 0L, 0L, 1L, paid);
        long available = Math.min(book.reserveBalance(), remaining(kind, limits, today));
        ProRata planned = shares(amounts, available);
        long total = 0L;
        for (Map.Entry<AccountId, Long> entry : planned.shares().entrySet()) {
            long share = entry.getValue();
            boolean pay = share > 0L && share <= book.reserveBalance() && book.canCredit(entry.getKey(), share);
            if (pay) {
                book.payOut(entry.getKey(), share, reason);
                spend(kind, share, today);
                total += share;
            }
            paid.put(entry.getKey(), pay ? share : 0L);
        }
        return new ProRata(planned.requested(), total, planned.numerator(), planned.denominator(), paid);
    }

    /**
     * The pure split behind {@link #payProRata}: what each account gets when {@code available}
     * credits are shared among the amounts asked for. Negative amounts count as nothing.
     */
    public static ProRata shares(Map<AccountId, Long> amounts, long available) {
        Map<AccountId, Long> out = new LinkedHashMap<>();
        BigInteger requested = BigInteger.ZERO;
        if (amounts != null) for (long value : amounts.values())
            if (value > 0L) requested = requested.add(BigInteger.valueOf(value));
        long cap = Math.max(0L, available);
        boolean full = requested.compareTo(BigInteger.valueOf(cap)) <= 0;
        long asked = requested.bitLength() > 62 ? Long.MAX_VALUE : requested.longValue();
        long total = 0L;
        if (amounts != null) for (Map.Entry<AccountId, Long> entry : amounts.entrySet()) {
            long value = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
            long share = full ? value : BigInteger.valueOf(value).multiply(BigInteger.valueOf(cap))
                    .divide(requested).longValue();
            out.put(entry.getKey(), share);
            total += share;
        }
        return full ? new ProRata(asked, total, 1L, 1L, out) : new ProRata(asked, total, cap, asked, out);
    }

    // --- minting -----------------------------------------------------------------------------

    public boolean seeded() { return seeded; }

    /**
     * The Reserve's initial fill, minted once in the life of a world: when its economy is first
     * created or migrated. @return the amount minted; 0 when it was already seeded
     */
    public long seed(Book book, long today, long initial) {
        if (book == null || seeded) return 0L;
        long amount = Math.max(0L, initial);
        if (amount > 0L && !book.mintReserve(amount)) amount = 0L;
        seeded = true;
        lastMintDay = today;
        return amount;
    }

    /**
     * The daily top-up. One mint per economy day that has started since the last top-up, at most
     * {@link #MAX_CATCH_UP_DAYS} of them; each is skipped while the Reserve is at or above
     * {@code cap} and never takes it past the cap. @return the total minted now
     */
    public long topUp(Book book, long today, long dailyMint, long cap) {
        if (book == null || !seeded || today <= lastMintDay) return 0L;
        long days = Math.min(MAX_CATCH_UP_DAYS, today - lastMintDay);
        lastMintDay = today;
        long minted = 0L;
        for (long day = 0L; day < days; day++) {
            long amount = topUpAmount(book.reserveBalance(), dailyMint, cap);
            if (amount <= 0L || !book.mintReserve(amount)) break;
            minted += amount;
        }
        return minted;
    }

    /** One day's top-up for a Reserve holding {@code balance}: the daily mint, cut off at the cap. */
    public static long topUpAmount(long balance, long dailyMint, long cap) {
        if (dailyMint <= 0L || balance >= cap) return 0L;
        return Math.min(dailyMint, cap - Math.max(0L, balance));
    }

    // --- persistence -------------------------------------------------------------------------

    long budgetDay() { return budgetDay; }
    long lastMintDay() { return lastMintDay; }
    Map<BudgetKind, Long> usedByKind() { return new EnumMap<>(used); }

    void restore(boolean wasSeeded, long savedMintDay, long savedBudgetDay, Map<BudgetKind, Long> savedUse) {
        seeded = wasSeeded;
        lastMintDay = savedMintDay;
        budgetDay = savedBudgetDay;
        used.clear();
        if (savedUse != null) savedUse.forEach((kind, amount) -> {
            if (kind != null && kind != BudgetKind.UNBUDGETED && amount != null && amount > 0L) used.put(kind, amount);
        });
    }
}
