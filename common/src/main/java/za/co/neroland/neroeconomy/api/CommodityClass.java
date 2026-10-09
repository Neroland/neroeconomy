package za.co.neroland.neroeconomy.api;

import java.util.Locale;

/**
 * What kind of goods a commodity is: a hint its data file gives, used for the defaults of the
 * numbers the file leaves out and by the Merchant Houses when they decide how hard to defend a
 * price.
 *
 * <ul>
 *   <li>{@link #STAPLE}: everyday bulk goods with a steady price.</li>
 *   <li>{@link #FARMABLE}: goods an unattended farm or machine turns out without limit; the price
 *       gives way quickly and one account may sell only a small share of the day's demand.</li>
 *   <li>{@link #CRAFTED}: goods made from other goods; worth about what went into them.</li>
 *   <li>{@link #RARE}: valuable goods the Merchant Houses take only a few of per day.</li>
 * </ul>
 */
public enum CommodityClass {
    STAPLE(0.4D, 0.04D, 4),
    FARMABLE(0.9D, 0.02D, 8),
    CRAFTED(0.6D, 0.03D, 4),
    RARE(0.8D, 0.01D, 4);

    private final double defaultElasticity;
    private final double defaultRecovery;
    private final int accountShare;

    CommodityClass(double defaultElasticity, double defaultRecovery, int accountShare) {
        this.defaultElasticity = defaultElasticity;
        this.defaultRecovery = defaultRecovery;
        this.accountShare = accountShare;
    }

    /** The word a data file uses: {@code staple}, {@code farmable}, {@code crafted} or {@code rare}. */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** The lang key of the class's name. */
    public String translationKey() { return "commodity_class.neroeconomy." + key(); }

    /** The {@code elasticity} of a commodity of this class whose file gives none. */
    public double defaultElasticity() { return defaultElasticity; }

    /** The {@code recovery} of a commodity of this class whose file gives none. */
    public double defaultRecovery() { return defaultRecovery; }

    /**
     * The per-account daily cap of a commodity of this class whose file gives none: this share of
     * the day's total ({@code total / share}), at least 1 when the total is above zero.
     */
    public long defaultAccountCap(long dailyTotal) {
        if (dailyTotal <= 0L) return 0L;
        return Math.max(1L, dailyTotal / accountShare);
    }

    /** The class a data file names, or null when it names none of them. */
    public static CommodityClass parse(String key) {
        if (key == null) return null;
        for (CommodityClass value : values()) if (value.key().equals(key)) return value;
        return null;
    }
}
