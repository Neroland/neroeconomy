package za.co.neroland.neroeconomy.goods;

/**
 * How many of one kind of goods fit in a single stack. The game answers this from the item (and,
 * for a modified stack, its prototype); a test passes a constant.
 */
@FunctionalInterface
public interface StackLimit {
    /** The limit used for goods the game cannot name right now, such as an item from a removed mod. */
    int DEFAULT = 64;

    /** @return the largest stack of these goods; values below 1 are treated as 1 */
    int limit(String item, int payload);

    /** The same limit for everything. */
    static StackLimit of(int limit) { return (item, payload) -> limit; }

    /** The limit for a lot's goods, never below 1. */
    default int limitOf(Lot lot) { return Math.max(1, limit(lot.item(), lot.payload())); }
}
