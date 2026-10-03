package za.co.neroland.neroeconomy.trade;

import java.util.Set;

/**
 * What the pure trading rules may ask about items, by id: whether an id names a real item, whether
 * it may be traded, and which item tags it carries. The game answers from its registries; a test
 * answers from a table. Ids are plain strings such as {@code minecraft:diamond}; a tag id is given
 * without the leading {@code #}.
 */
public interface ItemCatalog {
    /** Whether the id names a registered item. */
    boolean known(String item);

    /** Whether the item may enter the economy at all: known, and not in {@code neroeconomy:untradeable}. */
    boolean tradeable(String item);

    /** The ids of every item tag the item is in; empty for an unknown item. */
    Set<String> tagsOf(String item);

    /** Whether the tag exists and has at least one item in it. */
    boolean tagKnown(String tag);

    /** Whether the item is in the tag. */
    default boolean inTag(String item, String tag) { return tag != null && tagsOf(item).contains(tag); }

    /** Every id is a tradeable item and nothing has a tag: for rules that never ask. */
    ItemCatalog OPEN = new ItemCatalog() {
        @Override public boolean known(String item) { return item != null && !item.isBlank(); }
        @Override public boolean tradeable(String item) { return known(item); }
        @Override public Set<String> tagsOf(String item) { return Set.of(); }
        @Override public boolean tagKnown(String tag) { return false; }
    };
}
