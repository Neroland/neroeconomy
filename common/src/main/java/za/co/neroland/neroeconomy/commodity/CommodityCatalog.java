package za.co.neroland.neroeconomy.commodity;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * What resolving the commodity files needs to know about the world: which mods are loaded, which
 * item ids exist, what is in an item tag and which items may never be a commodity. The game
 * answers from its registries ({@link CommodityData}); a test answers from a table.
 *
 * <p>Ids are plain strings such as {@code minecraft:diamond}; a tag id is given without the
 * leading {@code #}.
 */
public interface CommodityCatalog {
    /** Why an item is refused: it is this mod's Credit Chip, which is money, not goods. */
    String CREDIT_CHIP = "credit_chip";
    /** Why an item is refused: it is in {@code neroeconomy:untradeable}. */
    String UNTRADEABLE = "untradeable";
    /** Why an item is refused: it is in {@code neroeconomy:never_commodity}. */
    String NEVER_COMMODITY = "never_commodity";

    /** Whether the mod is loaded; decides whether a {@code compat/<modid>} file is read at all. */
    boolean modLoaded(String modId);

    /** Whether the id names a registered item. */
    boolean itemKnown(String item);

    /** The ids of every item in the tag; empty when the tag does not exist or has no members. */
    Collection<String> tagItems(String tag);

    /**
     * Why the item can never be a commodity — {@link #CREDIT_CHIP}, {@link #UNTRADEABLE} or
     * {@link #NEVER_COMMODITY} — or null when it can be one.
     */
    String refusal(String item);

    /**
     * A catalogue over fixed tables, for tests and tools: every mod in {@code mods} is loaded,
     * every item in {@code items} or in a tag exists, and the items in {@code never} are refused
     * as {@link #NEVER_COMMODITY}.
     */
    static CommodityCatalog of(Set<String> mods, Set<String> items, Map<String, ? extends Collection<String>> tags,
            Set<String> never) {
        return new CommodityCatalog() {
            @Override public boolean modLoaded(String modId) { return mods.contains(modId); }

            @Override public boolean itemKnown(String item) {
                if (items.contains(item)) return true;
                for (Collection<String> members : tags.values()) if (members.contains(item)) return true;
                return false;
            }

            @Override public Collection<String> tagItems(String tag) {
                Collection<String> members = tags.get(tag);
                return members == null ? Set.of() : members;
            }

            @Override public String refusal(String item) { return never.contains(item) ? NEVER_COMMODITY : null; }
        };
    }
}
