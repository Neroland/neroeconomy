package za.co.neroland.neroeconomy.economy;

/**
 * What a Credit Chip is worth. The value steps down with every chip an account redeems in one
 * economy day: 100 %, 80 %, 60 %, 40 %, then 20 % of the configured value for the fifth and every
 * later one. Pure Java, so it is unit tested directly.
 */
public final class CreditChipSchedule {
    private static final int[] PERCENT = {100, 80, 60, 40, 20};

    private CreditChipSchedule() { }

    /** The percentage of the full value the next chip pays, given how many were redeemed today. */
    public static int percent(int redeemedToday) {
        return PERCENT[Math.max(0, Math.min(PERCENT.length - 1, redeemedToday))];
    }

    /**
     * @param fullValue     the configured {@code creditChipValue}
     * @param redeemedToday chips this account has already redeemed in the current economy day
     * @return the credits the next chip pays: rounded down, but never less than one
     */
    public static long value(long fullValue, int redeemedToday) {
        if (fullValue <= 0L) return 0L;
        int percent = percent(redeemedToday);
        long value = fullValue / 100L * percent + fullValue % 100L * percent / 100L;
        return Math.max(1L, value);
    }
}
