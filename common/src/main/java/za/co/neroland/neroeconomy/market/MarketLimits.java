package za.co.neroland.neroeconomy.market;

/**
 * The pure decisions behind the per-account caps, the listed-stack size cap, result paging and
 * claim delivery. Nothing here touches the game, so every rule is unit tested directly; the stores
 * supply the live counts and the configured limits.
 */
public final class MarketLimits {
    /** The largest page a search will return, whatever the caller asks for. */
    public static final int MAX_PAGE_SIZE = 50;

    private MarketLimits() { }

    /**
     * Whether {@code adding} more rows fit under {@code cap} when {@code current} already exist. A
     * negative input is a caller bug and never fits; the sum is taken in 64 bits so it cannot wrap.
     */
    public static boolean withinCap(int current, int adding, int cap) {
        if (current < 0 || adding < 0 || cap < 0) return false;
        return (long) current + adding <= cap;
    }

    /**
     * Whether a stack may be listed, auctioned or escrowed at all: it must not carry the
     * {@code neroeconomy:untradeable} tag, and its encoded form must fit the configured size. A
     * stack that could not be encoded reports a negative or oversized length and is refused.
     */
    public static boolean stackAllowed(int encodedBytes, int maxBytes, boolean untradeable) {
        return !untradeable && encodedBytes >= 0 && encodedBytes <= maxBytes;
    }

    /** The page size actually used: at least one row, at most {@link #MAX_PAGE_SIZE}. */
    public static int pageSize(int requested) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, requested));
    }

    /** Rows to skip for a page, exact in 64 bits; a negative page is the first page. */
    public static long pageOffset(int page, int pageSize) {
        return Math.multiplyExact((long) Math.max(0, page), (long) pageSize(pageSize));
    }

    /** A stream count narrowed to an int without wrapping. */
    public static int clampCount(long count) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, count));
    }

    /**
     * How many items of a claimed stack an inventory can take, given what a survey of it found.
     * Delivery hands the inventory exactly this many, so nothing is ever offered that cannot be
     * placed — which is the only way to stop a creative-mode inventory voiding the overflow.
     *
     * @param requested    items in the claim
     * @param freeSlots    completely empty slots
     * @param partialSpace room left in slots already holding the same item with the same components
     * @param perSlot      items one slot holds for this stack
     * @param stackable    false for a stack that can never merge into another (a damaged tool)
     */
    public static int deliverable(int requested, int freeSlots, int partialSpace, int perSlot, boolean stackable) {
        if (requested <= 0 || freeSlots < 0 || partialSpace < 0 || perSlot < 1) return 0;
        // An unstackable stack moves whole into one empty slot or not at all.
        if (!stackable) return freeSlots > 0 ? requested : 0;
        long room = (long) freeSlots * perSlot + partialSpace;
        return (int) Math.min(requested, room);
    }
}
