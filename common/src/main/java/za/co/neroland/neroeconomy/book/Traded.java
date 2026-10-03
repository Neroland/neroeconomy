package za.co.neroland.neroeconomy.book;

/**
 * What a direct trade against one named order did: buying from a sell order, or filling a buy
 * order.
 *
 * @param result  why the trade did or did not happen
 * @param fill    the fill; null unless the result is {@link BookResult#OK}
 * @param toVault whether the goods bought went to the vault rather than the inventory
 */
public record Traded(BookResult result, Fill fill, boolean toVault) {
    static Traded refused(BookResult result) { return new Traded(result, null, false); }

    public boolean ok() { return result.ok(); }
}
