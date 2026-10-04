package za.co.neroland.neroeconomy.exchange;

/**
 * The three Savings Bond terms. They are fixed in code rather than data: a bond has no price to
 * model, only a length and a rate, and the rate is a configuration key per term
 * ({@code bondRate1DayPercent}, {@code bondRate3DayPercent}, {@code bondRate7DayPercent}).
 *
 * <p>A bond's interest is for its whole term, not per day, and is rounded <em>down</em> to a whole
 * credit ({@link #interest}), so the Reserve never pays a fraction it was not asked for.
 */
public enum BondTerm {
    ONE_DAY("1d", 1),
    THREE_DAYS("3d", 3),
    SEVEN_DAYS("7d", 7);

    private final String key;
    private final int days;

    BondTerm(String key, int days) {
        this.key = key;
        this.days = days;
    }

    /** The stable id of the term: {@code 1d}, {@code 3d}, {@code 7d}. */
    public String key() { return key; }

    /** The term's length in economy-days. */
    public int days() { return days; }

    /** The term's length in milliseconds of economy time. */
    public long millis() { return days * PriceModel.DAY_MILLIS; }

    /** The lang key of the bond's name. */
    public String translationKey() { return "bond.neroeconomy." + key; }

    /** The lang key of the bond's one-line description. */
    public String descriptionKey() { return translationKey() + ".description"; }

    /** The term a stored key names, or null. */
    public static BondTerm parse(String key) {
        for (BondTerm term : values()) if (term.key.equals(key)) return term;
        return null;
    }

    /**
     * The interest {@code principal} earns over a whole term at {@code rateBps} hundredths of a
     * percent: {@code floor(principal × rateBps / 10000)}, exact, and 0 for anything that is not a
     * positive principal and rate.
     */
    public static long interest(long principal, int rateBps) {
        if (principal <= 0L || rateBps <= 0) return 0L;
        // Whole and remainder are multiplied separately, so a large principal does not overflow.
        long whole = principal / 10_000L;
        long rest = principal % 10_000L;
        try {
            return Math.addExact(Math.multiplyExact(whole, (long) rateBps), rest * rateBps / 10_000L);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
