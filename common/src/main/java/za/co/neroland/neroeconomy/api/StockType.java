package za.co.neroland.neroeconomy.api;

import java.util.Locale;

/**
 * The two kinds of listed company on the exchange.
 *
 * <ul>
 *   <li>{@link #STEADY}: small moves, pays dividends.</li>
 *   <li>{@link #RISKY}: large moves either way; a losing week is a real possibility.</li>
 * </ul>
 */
public enum StockType {
    STEADY,
    RISKY;

    /** The word a data file uses: {@code steady} or {@code risky}. */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** The lang key of the type's name. */
    public String translationKey() { return "stock_type.neroeconomy." + key(); }

    /** The type a data file names, or null when it names neither. */
    public static StockType parse(String key) {
        if (key == null) return null;
        for (StockType type : values()) if (type.key().equals(key)) return type;
        return null;
    }
}
