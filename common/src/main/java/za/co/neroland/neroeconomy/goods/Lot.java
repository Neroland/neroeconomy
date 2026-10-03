package za.co.neroland.neroeconomy.goods;

/**
 * Goods as the pure rules see them: so many of one item, in one of its forms.
 *
 * <p>{@code payload} 0 is an unmodified ("pristine") stack, fungible with every other unmodified
 * stack of the same item. Any other payload names one stored prototype stack (a custom name,
 * enchantments, contents) and is fungible only with itself. Two lots are the same goods exactly
 * when their item and payload are equal; the count is how many.
 *
 * <p>Nothing here touches the game: the item is its id as a string, so the vault, the order book
 * and every other rule that moves goods runs in plain JUnit. The edge that turns item stacks into
 * lots and back is {@link GoodsSection}.
 *
 * @param item    the item id, such as {@code minecraft:diamond}
 * @param count   how many; always at least 1
 * @param payload 0 for an unmodified stack, otherwise the id of a stored prototype
 */
public record Lot(String item, int count, int payload) {
    /** Payload id of an unmodified stack. */
    public static final int PRISTINE = 0;
    /** Longest item id a lot may carry; a real id is far shorter. */
    public static final int MAX_ITEM_LENGTH = 256;

    /** What a lot is, without how many: the unit goods are counted and matched in. */
    public record Key(String item, int payload) {
        public Key {
            if (!validItem(item) || payload < 0) throw new IllegalArgumentException("Invalid goods key");
        }
        public boolean pristine() { return payload == PRISTINE; }
    }

    public Lot {
        if (!valid(item, count, payload)) throw new IllegalArgumentException("Invalid lot");
    }

    /** An unmodified lot. */
    public static Lot of(String item, int count) { return new Lot(item, count, PRISTINE); }

    /** Whether these three values make a lot; lets a decoder skip a bad row instead of throwing. */
    public static boolean valid(String item, int count, int payload) {
        return validItem(item) && count >= 1 && payload >= 0;
    }

    private static boolean validItem(String item) {
        return item != null && !item.isBlank() && item.length() <= MAX_ITEM_LENGTH;
    }

    public boolean pristine() { return payload == PRISTINE; }

    public Key key() { return new Key(item, payload); }

    /** Whether {@code other} is the same goods: same item, same payload. */
    public boolean sameGoods(Lot other) {
        return other != null && payload == other.payload && item.equals(other.item);
    }

    public boolean is(String otherItem, int otherPayload) {
        return payload == otherPayload && item.equals(otherItem);
    }

    /** The same goods in another quantity. */
    public Lot withCount(int newCount) { return new Lot(item, newCount, payload); }
}
