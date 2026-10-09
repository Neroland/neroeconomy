package za.co.neroland.neroeconomy.commodity;

import java.util.regex.Pattern;

import za.co.neroland.neroeconomy.api.CommodityClass;

/**
 * One commodity, as its data file describes it: the goods the Merchant Houses trade, what they are
 * worth and how much of them the Houses will sell and buy in a day. Pure data, so the rules that
 * read it run in plain JUnit and a test can hand them definitions directly. The file format is
 * documented on {@link CommodityData}.
 *
 * <p>A commodity is named by what it covers: an item id ({@code minecraft:diamond}) or an item tag
 * with a leading {@code #} ({@code #minecraft:logs}). That is its {@link #key}: stable however the
 * data files are split or reordered, and unique, because an item belongs to one commodity only.
 * A commodity only ever covers <b>unmodified</b> stacks of its items.
 *
 * <p>All prices are whole credits per unit. The Merchant Houses sell at the {@link #ask} and buy
 * at the {@link #bid}: the reference price plus and minus the spread, rounded <em>away</em> from
 * the reference, so the ask is always above it and the bid always below.
 *
 * @param key                 the item id, or the tag id with a leading {@code #}
 * @param file                the id of the data file it came from ({@code <namespace>:<path>})
 * @param referencePrice      whole credits per unit, at least 1
 * @param npcDailySupply      units the Houses will sell per economy-day in total; 0 = they never sell it
 * @param npcDailyDemand      units the Houses will buy per economy-day in total; 0 = they never buy it
 * @param perAccountDailyBuy  units one account may buy from the Houses per economy-day
 * @param perAccountDailySell units one account may sell to the Houses per economy-day
 * @param elasticity          how hard the price reacts to trade with the Houses: 0.5 moves it by
 *                            50 % across a full day's cap
 * @param recovery            the fraction of that pressure that decays per simulation step
 * @param spreadBps           the spread in hundredths of a percent: the ask is this far above the
 *                            reference and the bid this far below
 * @param commodityClass      staple, farmable, crafted or rare
 * @param house               the id of the Merchant House that trades it
 * @param sector              the exchange sector its trade counts toward; empty for none
 * @param required            false when the file expects the item or tag to be missing in some
 *                            worlds, so its absence is not worth a warning
 * @param enabled             false keeps the entry in its file but takes the commodity off the market
 */
public record CommodityDefinition(String key, String file, long referencePrice, long npcDailySupply,
        long npcDailyDemand, long perAccountDailyBuy, long perAccountDailySell, double elasticity, double recovery,
        int spreadBps, CommodityClass commodityClass, String house, String sector, boolean required, boolean enabled) {
    public static final long MIN_REFERENCE_PRICE = 1L;
    public static final long MAX_REFERENCE_PRICE = 1_000_000L;
    /** The most units a daily cap may name. */
    public static final long MAX_DAILY_UNITS = 1_000_000L;
    public static final double MAX_ELASTICITY = 4.0D;
    /** 2 %: below this an ask and a bid could meet after rounding at a low price. */
    public static final int MIN_SPREAD_BPS = 200;
    /** 75 %. */
    public static final int MAX_SPREAD_BPS = 7_500;
    public static final int BPS = 10_000;
    /** The Merchant House a commodity belongs to when its file names none. */
    public static final String DEFAULT_HOUSE = "general";
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_HOUSE_LENGTH = 64;
    public static final int MAX_SECTOR_LENGTH = 32;
    /** Where files that load only beside another mod live: {@code compat/<modid>.json}. */
    public static final String COMPAT_PREFIX = "compat/";
    /** The file id of a definition that was built in code rather than read from a file. */
    public static final String DIRECT_FILE = "neroeconomy:direct";

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern HOUSE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern SECTOR = Pattern.compile("[a-z0-9_]+");
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]+");

    public CommodityDefinition {
        if (!validKey(key) || !validId(file) || referencePrice < MIN_REFERENCE_PRICE
                || referencePrice > MAX_REFERENCE_PRICE || !validUnits(npcDailySupply) || !validUnits(npcDailyDemand)
                || perAccountDailyBuy < 0L || perAccountDailyBuy > npcDailySupply || perAccountDailySell < 0L
                || perAccountDailySell > npcDailyDemand || !validElasticity(elasticity) || !validRecovery(recovery)
                || !validSpreadBps(spreadBps) || commodityClass == null || !validHouse(house) || sector == null
                || (!sector.isEmpty() && !validSector(sector)))
            throw new IllegalArgumentException("commodity definition");
    }

    /**
     * A commodity with the defaults of its class and the default House: for tests and for rules
     * that build definitions themselves.
     *
     * @param key       an item id, or a tag id with a leading {@code #}
     * @param spreadBps the spread in hundredths of a percent
     */
    public static CommodityDefinition of(String key, long referencePrice, long npcDailySupply, long npcDailyDemand,
            int spreadBps, CommodityClass commodityClass) {
        return new CommodityDefinition(key, DIRECT_FILE, referencePrice, npcDailySupply, npcDailyDemand,
                commodityClass.defaultAccountCap(npcDailySupply), commodityClass.defaultAccountCap(npcDailyDemand),
                commodityClass.defaultElasticity(), commodityClass.defaultRecovery(), spreadBps, commodityClass,
                DEFAULT_HOUSE, "", true, true);
    }

    // --- what it covers --------------------------------------------------------------------------

    /** Whether the commodity is a whole item tag rather than one item. */
    public boolean tag() { return key.charAt(0) == '#'; }

    /** The item id or the tag id, without the leading {@code #}. */
    public String target() { return tag() ? key.substring(1) : key; }

    /**
     * The mod this commodity is only loaded beside: the {@code <modid>} of a file under
     * {@code commodities/compat/<modid>.json} (or {@code compat/<modid>/...}); null for any other file.
     */
    public String compatMod() { return compatMod(file); }

    /** {@link #compatMod()} for a file id. */
    public static String compatMod(String file) {
        if (file == null) return null;
        int colon = file.indexOf(':');
        String path = colon < 0 ? file : file.substring(colon + 1);
        if (!path.startsWith(COMPAT_PREFIX)) return null;
        String rest = path.substring(COMPAT_PREFIX.length());
        int slash = rest.indexOf('/');
        String mod = slash < 0 ? rest : rest.substring(0, slash);
        return MOD_ID.matcher(mod).matches() ? mod : null;
    }

    // --- prices ----------------------------------------------------------------------------------

    /** What the Houses sell one unit for at the reference price. Always above the reference. */
    public long ask() { return askAt(referencePrice, spreadBps); }

    /** What the Houses pay for one unit at the reference price. Always below the reference; 0 = nothing. */
    public long bid() { return bidAt(referencePrice, spreadBps); }

    /**
     * The House's selling price around {@code price}: {@code price} plus the spread, rounded up,
     * and at least 1 credit above {@code price}.
     */
    public static long askAt(long price, int spreadBps) {
        if (price < 1L) price = 1L;
        long ask = Math.ceilDiv(Math.multiplyExact(price, (long) BPS + spreadBps), (long) BPS);
        return Math.max(ask, price + 1L);
    }

    /**
     * The House's buying price around {@code price}: {@code price} less the spread, rounded down,
     * and at least 1 credit below {@code price}. 0 means the goods are worth too little to buy.
     */
    public static long bidAt(long price, int spreadBps) {
        if (price < 1L) return 0L;
        long bid = Math.floorDiv(Math.multiplyExact(price, (long) BPS - spreadBps), (long) BPS);
        return Math.max(0L, Math.min(bid, price - 1L));
    }

    /** Whether the Houses ever sell it: it has a daily supply and an account may buy some of it. */
    public boolean npcSells() { return npcDailySupply > 0L && perAccountDailyBuy > 0L; }

    /**
     * Whether the Houses ever buy it: it has a daily demand, an account may sell some of it, and
     * its bid is at least 1 credit.
     */
    public boolean npcBuys() { return npcDailyDemand > 0L && perAccountDailySell > 0L && bid() >= 1L; }

    // --- what a field may hold -------------------------------------------------------------------

    /** {@code <namespace>:<path>}. */
    public static boolean validId(String id) {
        return id != null && id.length() <= MAX_ID_LENGTH && ID.matcher(id).matches();
    }

    /** An item id, or a tag id with a leading {@code #}. */
    public static boolean validKey(String key) {
        if (key == null || key.isEmpty()) return false;
        return validId(key.charAt(0) == '#' ? key.substring(1) : key);
    }

    public static boolean validUnits(long units) { return units >= 0L && units <= MAX_DAILY_UNITS; }

    public static boolean validElasticity(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= MAX_ELASTICITY;
    }

    public static boolean validRecovery(double value) {
        return Double.isFinite(value) && value > 0.0D && value <= 1.0D;
    }

    public static boolean validSpreadBps(int bps) { return bps >= MIN_SPREAD_BPS && bps <= MAX_SPREAD_BPS; }

    /** Lower-case letters, digits, {@code _ . -}: what a Merchant House's ledger account allows. */
    public static boolean validHouse(String house) {
        return house != null && house.length() <= MAX_HOUSE_LENGTH && HOUSE.matcher(house).matches();
    }

    public static boolean validSector(String sector) {
        return sector != null && sector.length() <= MAX_SECTOR_LENGTH && SECTOR.matcher(sector).matches();
    }
}
