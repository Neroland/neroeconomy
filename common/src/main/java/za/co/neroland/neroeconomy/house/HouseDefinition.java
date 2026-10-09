package za.co.neroland.neroeconomy.house;

import java.util.List;
import java.util.regex.Pattern;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;

/**
 * One Merchant House, as its data file describes it: the name players see, which commodities it
 * trades, how much working capital it keeps and how fast it restocks. Pure data, so the rules that
 * read it run in plain JUnit. The file format is documented on {@link HouseData}.
 *
 * <p>A House trades every commodity whose {@code house} field names it, every commodity whose
 * {@code house} field names one of its {@link #trades}, and every commodity whose sector is one of
 * its {@link #sectors} and that no other House claimed by name. A commodity no House claims goes to
 * {@link CommodityDefinition#DEFAULT_HOUSE}.
 *
 * @param id             the House's id: the path of its data file ({@code general} for
 *                       {@code data/<namespace>/neroeconomy/houses/general.json})
 * @param file           the id of the data file it came from ({@code <namespace>:<path>})
 * @param nameKey        the lang key of its display name
 * @param trades         further commodity {@code house} ids it trades, beside its own id
 * @param sectors        exchange sectors whose otherwise unclaimed commodities it trades
 * @param workingCapital the credits it keeps to buy with: topped up from the Reserve toward this,
 *                       and anything above it is swept back
 * @param restockPerDay  how much of a commodity's daily supply it restocks per economy-day: 1.0
 *                       restocks a whole day's supply over one economy-day
 */
public record HouseDefinition(String id, String file, String nameKey, List<String> trades, List<String> sectors,
        long workingCapital, double restockPerDay) {
    /** The most credits a House may keep as working capital. */
    public static final long MAX_WORKING_CAPITAL = 1_000_000_000L;
    /** The working capital of a House whose file gives none. */
    public static final long DEFAULT_WORKING_CAPITAL = 5_000L;
    /** The most of a day's supply a House may restock per day. */
    public static final double MAX_RESTOCK_PER_DAY = 24.0D;
    /** The restock rate of a House whose file gives none: one day's supply per economy-day. */
    public static final double DEFAULT_RESTOCK_PER_DAY = 1.0D;
    /** The most ids one list may name. */
    public static final int MAX_LIST = 64;
    /** The file id of the House built in code, used when no data pack defines {@code general}. */
    public static final String BUILT_IN_FILE = "neroeconomy:built_in";
    /** Lang keys: letters, digits and {@code _ . -}. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]{1,128}");

    public HouseDefinition {
        if (!CommodityDefinition.validHouse(id) || !CommodityDefinition.validId(file) || nameKey == null
                || !KEY.matcher(nameKey).matches() || trades == null || sectors == null || trades.size() > MAX_LIST
                || sectors.size() > MAX_LIST || workingCapital < 0L || workingCapital > MAX_WORKING_CAPITAL
                || !validRestock(restockPerDay))
            throw new IllegalArgumentException("house definition");
        trades = List.copyOf(trades);
        sectors = List.copyOf(sectors);
        for (String house : trades) if (!CommodityDefinition.validHouse(house)) throw new IllegalArgumentException("house trades");
        for (String sector : sectors)
            if (!CommodityDefinition.validSector(sector)) throw new IllegalArgumentException("house sectors");
    }

    /** The House a world has when no data pack names a {@code general} one: modest, and always there. */
    public static HouseDefinition builtInGeneral() {
        return new HouseDefinition(CommodityDefinition.DEFAULT_HOUSE, BUILT_IN_FILE,
                "house.neroeconomy." + CommodityDefinition.DEFAULT_HOUSE, List.of(), List.of(), DEFAULT_WORKING_CAPITAL,
                DEFAULT_RESTOCK_PER_DAY);
    }

    /** A House with the defaults, for tests and for rules that build definitions themselves. */
    public static HouseDefinition of(String id, long workingCapital) {
        return new HouseDefinition(id, BUILT_IN_FILE, "house.neroeconomy." + id, List.of(), List.of(), workingCapital,
                DEFAULT_RESTOCK_PER_DAY);
    }

    public static boolean validRestock(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= MAX_RESTOCK_PER_DAY;
    }
}
