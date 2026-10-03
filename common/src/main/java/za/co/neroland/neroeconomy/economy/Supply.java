package za.co.neroland.neroeconomy.economy;

import java.util.EnumMap;
import java.util.Map;

/**
 * How many credits have ever been created and destroyed, by reason, for all time and for the
 * current economy day. The ledger records every mint and burn here, so the total of all balances
 * can be checked against {@code minted − burned} at any moment (Invariant A). Aggregates only: no
 * account is named. Pure Java, so it is unit tested directly.
 */
public final class Supply {
    private final EnumMap<TransactionType, Long> minted = new EnumMap<>(TransactionType.class);
    private final EnumMap<TransactionType, Long> burned = new EnumMap<>(TransactionType.class);
    private final EnumMap<TransactionType, Long> mintedToday = new EnumMap<>(TransactionType.class);
    private final EnumMap<TransactionType, Long> burnedToday = new EnumMap<>(TransactionType.class);
    private long day;

    /** Credits in existence according to the counters: everything minted less everything burned. */
    public long net() { return mintedTotal() - burnedTotal(); }

    public long mintedTotal() { return sum(minted); }
    public long burnedTotal() { return sum(burned); }

    public long mintedToday(long today) { return today == day ? sum(mintedToday) : 0L; }
    public long burnedToday(long today) { return today == day ? sum(burnedToday) : 0L; }

    public Map<TransactionType, Long> mintedByReason() { return new EnumMap<>(minted); }
    public Map<TransactionType, Long> burnedByReason() { return new EnumMap<>(burned); }

    /**
     * Whether {@code amount} more credits can be minted: the reason has to be a minting one, and
     * neither the all-time total nor the net supply may pass the largest storable amount.
     */
    public boolean canMint(TransactionType reason, long amount) {
        if (reason == null || !reason.mints() || amount < 0L) return false;
        long total = mintedTotal();
        return Long.MAX_VALUE - total >= amount && Long.MAX_VALUE - (total - burnedTotal()) >= amount;
    }

    /** Whether {@code amount} credits can be burned under {@code reason}. */
    public boolean canBurn(TransactionType reason, long amount) {
        return reason != null && reason.burns() && amount >= 0L && Long.MAX_VALUE - burnedTotal() >= amount
                && net() >= amount;
    }

    /** Records a mint. Callers check {@link #canMint} first; a refused one changes nothing. */
    public boolean mint(TransactionType reason, long amount, long today) {
        if (!canMint(reason, amount)) return false;
        if (amount == 0L) return true;
        roll(today);
        minted.merge(reason, amount, Long::sum);
        mintedToday.merge(reason, amount, Long::sum);
        return true;
    }

    /** Records a burn. Callers check {@link #canBurn} first; a refused one changes nothing. */
    public boolean burn(TransactionType reason, long amount, long today) {
        if (!canBurn(reason, amount)) return false;
        if (amount == 0L) return true;
        roll(today);
        burned.merge(reason, amount, Long::sum);
        burnedToday.merge(reason, amount, Long::sum);
        return true;
    }

    /** Starts a new day's counters when the economy day has changed. */
    public void roll(long today) {
        if (today == day) return;
        day = today;
        mintedToday.clear();
        burnedToday.clear();
    }

    long day() { return day; }
    Map<TransactionType, Long> mintedTodayByReason() { return new EnumMap<>(mintedToday); }
    Map<TransactionType, Long> burnedTodayByReason() { return new EnumMap<>(burnedToday); }

    /** Load path: restores one counter as saved. A negative or overflowing value is dropped. */
    void restore(boolean mint, boolean today, TransactionType reason, long amount) {
        if (reason == null || amount <= 0L || (mint ? !reason.mints() : !reason.burns())) return;
        EnumMap<TransactionType, Long> target = mint ? (today ? mintedToday : minted) : (today ? burnedToday : burned);
        if (Long.MAX_VALUE - sum(target) < amount) return;
        target.merge(reason, amount, Long::sum);
    }

    void restoreDay(long savedDay) { day = savedDay; }

    private static long sum(Map<TransactionType, Long> values) {
        long total = 0L;
        for (long value : values.values()) total += value;
        return total;
    }
}
