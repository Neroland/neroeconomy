package za.co.neroland.neroeconomy.exchange;

import java.util.List;
import java.util.regex.Pattern;

import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.trade.ItemCatalog;

/**
 * One listed company, as its data file describes it. Pure data: the price model reads these and
 * nothing else about a company, so a test can hand it definitions directly.
 *
 * <p>The units are chosen so that a definition means the same whatever the simulation's step
 * length is; {@link PriceModel} converts them to one step.
 *
 * @param id             {@code <namespace>:<path>}, from the file's location
 * @param nameKey        lang key of the company's name
 * @param descriptionKey lang key of a one-line description
 * @param sector         a short lower-case word ({@code mining}); its name is the lang key
 *                       {@code sector.neroeconomy.<sector>}
 * @param type           steady or risky
 * @param startPrice     the price it lists at, in whole credits
 * @param volatility     how much the random part of the price moves in one economy-day, as a
 *                       percentage (one standard deviation)
 * @param drift          how much the anchor grows in one economy-week, as a percentage
 * @param dividendYield  dividend per economy-week, as a percentage of the price; 0 pays none
 * @param linked         the commodities whose trade moves it: item ids and {@code #tag} ids
 * @param enabled        false keeps the file but takes the company off the exchange
 */
public record StockDefinition(String id, String nameKey, String descriptionKey, String sector, StockType type,
        long startPrice, double volatility, double drift, double dividendYield, List<String> linked, boolean enabled) {
    public static final long MIN_START_PRICE = 1L;
    public static final long MAX_START_PRICE = 1_000_000L;
    /** Percent per economy-day. */
    public static final double MAX_VOLATILITY = 50.0D;
    /** Percent per economy-week. */
    public static final double MIN_DRIFT = -50.0D;
    public static final double MAX_DRIFT = 100.0D;
    /** Percent per economy-week. */
    public static final double MAX_DIVIDEND_YIELD = 25.0D;
    public static final int MAX_LINKED = 32;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_SECTOR_LENGTH = 32;

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern SECTOR = Pattern.compile("[a-z0-9_]+");

    public StockDefinition {
        if (!validId(id) || !validKey(nameKey) || !validKey(descriptionKey) || !validSector(sector) || type == null
                || startPrice < MIN_START_PRICE || startPrice > MAX_START_PRICE || !validVolatility(volatility)
                || !validDrift(drift) || !validDividendYield(dividendYield) || linked == null
                || linked.size() > MAX_LINKED) throw new IllegalArgumentException("stock definition");
        for (String link : linked) if (!validLink(link)) throw new IllegalArgumentException("stock definition");
        linked = List.copyOf(linked);
    }

    /** The price it lists at, in hundredths of a credit. */
    public long startCents() { return startPrice * PriceModel.CENTS_PER_CREDIT; }

    public boolean steady() { return type == StockType.STEADY; }

    /** Whether trade in {@code item} counts toward this company: the item is linked, or is in a linked tag. */
    public boolean linksItem(String item, ItemCatalog items) {
        if (item == null) return false;
        for (String link : linked) {
            if (link.charAt(0) != '#') {
                if (link.equals(item)) return true;
            } else if (items != null && items.inTag(item, link.substring(1))) return true;
        }
        return false;
    }

    // --- what a field may hold ---------------------------------------------------------------

    public static boolean validId(String id) {
        return id != null && id.length() <= MAX_KEY_LENGTH && ID.matcher(id).matches();
    }

    public static boolean validKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && KEY.matcher(key).matches();
    }

    public static boolean validSector(String sector) {
        return sector != null && sector.length() <= MAX_SECTOR_LENGTH && SECTOR.matcher(sector).matches();
    }

    /** An item id, or a tag id with a leading {@code #}. */
    public static boolean validLink(String link) {
        if (link == null || link.isEmpty()) return false;
        return validId(link.charAt(0) == '#' ? link.substring(1) : link);
    }

    public static boolean validVolatility(double value) {
        return Double.isFinite(value) && value > 0.0D && value <= MAX_VOLATILITY;
    }

    public static boolean validDrift(double value) {
        return Double.isFinite(value) && value >= MIN_DRIFT && value <= MAX_DRIFT;
    }

    public static boolean validDividendYield(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= MAX_DIVIDEND_YIELD;
    }
}
