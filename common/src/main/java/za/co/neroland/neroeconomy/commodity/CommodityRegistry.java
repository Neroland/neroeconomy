package za.co.neroland.neroeconomy.commodity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The commodities of a world, as the pure rules see them: which goods the Merchant Houses trade,
 * and for any item id which commodity — if any — it belongs to. The game answers from the data
 * packs ({@link CommodityData}); a test answers with definitions of its own ({@link #of}).
 *
 * <p>Tags are resolved to item ids when the registry is built, so {@link #forItem} is one map
 * lookup. A registry never changes: a data-pack reload builds a new one.
 */
public interface CommodityRegistry {
    /** The most commodities a world may have; further ones, in file order, are skipped with a warning. */
    int MAX_COMMODITIES = 4096;

    /** Every commodity, in key order. Disabled entries and entries that covered nothing are not in it. */
    List<CommodityDefinition> commodities();

    /** The commodity with this key (an item id, or a tag id with a leading {@code #}), or null. */
    CommodityDefinition get(String key);

    /** The commodity that covers unmodified stacks of this item, or null. One map lookup. */
    CommodityDefinition forItem(String item);

    /** The item ids a commodity covers, in id order; empty for a key that is not a commodity. */
    List<String> itemsOf(String key);

    /**
     * The commodity a lot belongs to, or null. Only an unmodified lot (payload 0) is ever a
     * commodity: a stack with a custom name, enchantments or contents is not, whatever its item.
     */
    default CommodityDefinition forLot(Lot lot) {
        return lot == null || !lot.pristine() ? null : forItem(lot.item());
    }

    /**
     * Whether the Merchant Houses may SELL this commodity now: its data says they do, and the
     * arbitrage guard has not switched that side off.
     */
    default boolean npcSells(CommodityDefinition commodity) {
        return commodity != null && commodity.npcSells() && !sellOff().contains(commodity.key());
    }

    /**
     * Whether the Merchant Houses may BUY this commodity now: its data says they do, and the
     * arbitrage guard has not switched that side off.
     */
    default boolean npcBuys(CommodityDefinition commodity) {
        return commodity != null && commodity.npcBuys() && !buyOff().contains(commodity.key());
    }

    /** The keys of the commodities whose NPC buy side the arbitrage guard has switched off. */
    default Set<String> buyOff() { return Set.of(); }

    /** The keys of the commodities whose NPC sell side the arbitrage guard has switched off. */
    default Set<String> sellOff() { return Set.of(); }

    /** What happened while the files were read: entries that were skipped, clashes, counts. */
    LoadReport report();

    /**
     * False when the data could not be read at all, which is not the same as there being no
     * commodities: the Merchant Houses then leave everything as it is.
     */
    default boolean available() { return true; }

    /** The same commodities with these NPC sides switched off. */
    default CommodityRegistry guarded(Set<String> buyOffKeys, Set<String> sellOffKeys) {
        CommodityRegistry base = this;
        Set<String> buys = Set.copyOf(buyOffKeys);
        Set<String> sells = Set.copyOf(sellOffKeys);
        return new CommodityRegistry() {
            @Override public List<CommodityDefinition> commodities() { return base.commodities(); }
            @Override public CommodityDefinition get(String key) { return base.get(key); }
            @Override public CommodityDefinition forItem(String item) { return base.forItem(item); }
            @Override public List<String> itemsOf(String key) { return base.itemsOf(key); }
            @Override public Set<String> buyOff() { return buys; }
            @Override public Set<String> sellOff() { return sells; }
            @Override public LoadReport report() { return base.report(); }
            @Override public boolean available() { return base.available(); }
        };
    }

    /**
     * One entry that was skipped.
     *
     * @param file  the id of its data file
     * @param entry its place in the file's list, from 0; -1 when the whole file was refused
     * @param word  why, as a fixed word; nothing from the file itself
     */
    record Problem(String file, int entry, String word) { }

    /**
     * An item two commodities both claimed.
     *
     * @param item        the item id
     * @param kept        the key of the commodity that keeps it
     * @param keptFile    that commodity's file
     * @param dropped     the key of the commodity that lost it
     * @param droppedFile that commodity's file
     */
    record Clash(String item, String kept, String keptFile, String dropped, String droppedFile) { }

    /**
     * What a load came to.
     *
     * @param files      data files that were read
     * @param gatedFiles {@code compat/<modid>} files left out because the mod is not loaded
     * @param absent     entries skipped without a warning because their item or tag is not in this world
     * @param disabled   entries whose file says {@code "enabled": false}
     * @param problems   entries skipped with a warning
     * @param clashes    items two commodities both claimed
     */
    record LoadReport(int files, int gatedFiles, int absent, int disabled, List<Problem> problems, List<Clash> clashes) {
        public static final LoadReport NONE = new LoadReport(0, 0, 0, 0, List.of(), List.of());

        public LoadReport {
            problems = List.copyOf(problems);
            clashes = List.copyOf(clashes);
        }
    }

    /** No commodities. */
    CommodityRegistry EMPTY = CommodityResolver.resolve(List.of(), CommodityCatalog.of(Set.of(), Set.of(),
            java.util.Map.of(), Set.of()), LoadReport.NONE, null);

    /** The data could not be read: nothing is known. */
    CommodityRegistry UNAVAILABLE = new CommodityRegistry() {
        @Override public List<CommodityDefinition> commodities() { return List.of(); }
        @Override public CommodityDefinition get(String key) { return null; }
        @Override public CommodityDefinition forItem(String item) { return null; }
        @Override public List<String> itemsOf(String key) { return List.of(); }
        @Override public LoadReport report() { return LoadReport.NONE; }
        @Override public boolean available() { return false; }
    };

    /**
     * A fixed registry of {@code definitions}, given in the order their files would be read:
     * resolved against {@code catalog} exactly as the game resolves the data files.
     */
    static CommodityRegistry of(Collection<CommodityDefinition> definitions, CommodityCatalog catalog) {
        return CommodityResolver.resolve(definitions == null ? List.of() : List.copyOf(definitions), catalog,
                LoadReport.NONE, null);
    }
}
