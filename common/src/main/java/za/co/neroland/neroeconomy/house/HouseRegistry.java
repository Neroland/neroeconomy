package za.co.neroland.neroeconomy.house;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;

/**
 * The Merchant Houses of a world, and which of them trades each commodity. The game reads them
 * from the data packs ({@link HouseData}); a test builds one with {@link #of}. A registry never
 * changes: a data-pack reload builds a new one.
 *
 * <p>There is always a {@code general} House: when no data pack defines one, a built-in one with
 * modest working capital stands in, so every commodity has somewhere to be traded.
 */
public final class HouseRegistry {
    /** The most Houses a world may have; further ones, in file order, are skipped with a warning. */
    public static final int MAX_HOUSES = 64;

    /**
     * One file that was skipped, or one House another file had already defined.
     *
     * @param file the id of the data file
     * @param word why, as a fixed word; nothing from the file itself
     */
    public record Problem(String file, String word) { }

    private final Map<String, HouseDefinition> houses;
    private final List<Problem> problems;

    private HouseRegistry(Map<String, HouseDefinition> houses, List<Problem> problems) {
        this.houses = houses;
        this.problems = List.copyOf(problems);
    }

    /** A registry of these Houses, in the order their files would be read; the first of an id wins. */
    public static HouseRegistry of(Collection<HouseDefinition> definitions) { return build(definitions, List.of()); }

    static HouseRegistry build(Collection<HouseDefinition> definitions, List<Problem> found) {
        Map<String, HouseDefinition> byId = new TreeMap<>();
        List<Problem> problems = new ArrayList<>(found);
        if (definitions != null) for (HouseDefinition house : definitions) {
            if (house == null) continue;
            if (byId.containsKey(house.id())) problems.add(new Problem(house.file(), "duplicate_house"));
            else if (byId.size() >= MAX_HOUSES) problems.add(new Problem(house.file(), "too_many_houses"));
            else byId.put(house.id(), house);
        }
        byId.putIfAbsent(CommodityDefinition.DEFAULT_HOUSE, HouseDefinition.builtInGeneral());
        return new HouseRegistry(java.util.Collections.unmodifiableMap(byId), problems);
    }

    /** Only the built-in {@code general} House. */
    public static final HouseRegistry DEFAULT = of(List.of());

    /** Every House, in id order. */
    public List<HouseDefinition> houses() { return List.copyOf(houses.values()); }

    /** The House with this id, or null. */
    public HouseDefinition get(String id) { return id == null ? null : houses.get(id); }

    /** Files and Houses that were skipped. */
    public List<Problem> problems() { return problems; }

    /**
     * The House that trades {@code commodity}: the one its {@code house} field names; else the
     * first, in id order, that lists that id among its {@code trades}; else the first that lists
     * its sector; else {@code general}. Never null.
     */
    public HouseDefinition houseFor(CommodityDefinition commodity) {
        if (commodity == null) return houses.get(CommodityDefinition.DEFAULT_HOUSE);
        HouseDefinition named = houses.get(commodity.house());
        if (named != null) return named;
        for (HouseDefinition house : houses.values()) if (house.trades().contains(commodity.house())) return house;
        if (!commodity.sector().isEmpty())
            for (HouseDefinition house : houses.values()) if (house.sectors().contains(commodity.sector())) return house;
        return houses.get(CommodityDefinition.DEFAULT_HOUSE);
    }
}
