package za.co.neroland.neroeconomy.hub;

import java.util.List;

import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.economy.Fees;

/**
 * What the market tab tells a player before they confirm: the whole cost of a purchase, what a
 * sale pays out after its fees, and what a buy order holds in escrow. The server works every quote
 * out (the client never prices anything) and re-checks all of it when the confirm arrives. Exact
 * integer arithmetic: a product too large for a long is refused, never wrapped. Pure Java.
 */
public final class HubQuotes {
    private static final String PREFIX = "gui.neroeconomy.hub.quote.";
    public static final String BUY_INVENTORY = PREFIX + "buy_inventory";
    public static final String BUY_VAULT = PREFIX + "buy_vault";
    public static final String SELL = PREFIX + "sell";
    public static final String BID = PREFIX + "bid";
    public static final String INVALID = PREFIX + "refused.invalid";
    public static final String TOO_LARGE = PREFIX + "refused.too_large";
    public static final String OUT_OF_STOCK = PREFIX + "refused.out_of_stock";
    public static final String OWN_LISTING = PREFIX + "refused.own_listing";
    public static final String NOT_HOLDING = PREFIX + "refused.not_holding";
    public static final String UNKNOWN_ITEM = PREFIX + "refused.unknown_item";
    public static final String UNTRADEABLE = PREFIX + "refused.untradeable";
    public static final String NO_ROOM = PREFIX + "refused.no_room";

    /** Every key a quote can come back with, for the lang check. */
    public static final List<String> KEYS = List.of(BUY_INVENTORY, BUY_VAULT, SELL, BID, INVALID, TOO_LARGE,
            OUT_OF_STOCK, OWN_LISTING, NOT_HOLDING, UNKNOWN_ITEM, UNTRADEABLE, NO_ROOM);

    /**
     * A quote.
     *
     * @param key        the lang key of the line the player is shown: the quote, or why there is none
     * @param quantity   how many
     * @param unit       the price each the confirm will carry
     * @param total      what the player pays: a purchase's cost, or a buy order's escrow plus its listing fee
     * @param fee        the market fee: on a purchase it comes out of the seller's share, on a sale out of yours
     * @param listingFee the flat fee for placing an order
     * @param net        a sale: what the seller receives; a buy order: the escrow alone
     * @param toVault    a purchase: whether the goods go to the vault because the inventory has no room
     */
    public record Quote(boolean ok, String key, long quantity, long unit, long total, long fee, long listingFee,
            long net, boolean toVault) {
        static Quote refused(String key) { return new Quote(false, key, 0L, 0L, 0L, 0L, 0L, 0L, false); }
    }

    private HubQuotes() { }

    private static boolean quantityOk(long quantity) {
        return quantity >= 1L && quantity <= BookSection.MAX_ORDER_QUANTITY;
    }

    /**
     * Buying {@code quantity} of a row.
     *
     * @param buyerUnit   what this viewer is charged each, after price modifiers
     * @param sellerUnit  the seller's own price each, which the market fee is worked out on
     * @param stock       how many the row has
     * @param own         whether the row is the viewer's own
     * @param fits        whether the goods fit the inventory; otherwise they go to the vault
     * @param vaultRoom   whether the vault could take them when the inventory cannot
     */
    public static Quote buy(long buyerUnit, long sellerUnit, long stock, boolean own, long quantity, double feePercent,
            boolean fits, boolean vaultRoom) {
        if (!quantityOk(quantity) || buyerUnit < 1L || sellerUnit < 1L) return Quote.refused(INVALID);
        if (own) return Quote.refused(OWN_LISTING);
        if (quantity > stock) return Quote.refused(OUT_OF_STOCK);
        if (!fits && !vaultRoom) return Quote.refused(NO_ROOM);
        try {
            long total = Math.multiplyExact(buyerUnit, quantity);
            long fee = Fees.fee(Math.multiplyExact(sellerUnit, quantity), feePercent);
            return new Quote(true, fits ? BUY_INVENTORY : BUY_VAULT, quantity, buyerUnit, total, fee, 0L, total, !fits);
        } catch (ArithmeticException overflow) {
            return Quote.refused(TOO_LARGE);
        }
    }

    /**
     * Selling {@code quantity} at {@code unit} each.
     *
     * @param available  how many the player has where the goods come from
     * @param tradeable  whether the goods may be traded at all
     * @param listingFee the flat fee for placing the order
     */
    public static Quote sell(long quantity, long unit, long available, boolean tradeable, long listingFee,
            double feePercent) {
        if (!quantityOk(quantity) || unit < 1L) return Quote.refused(INVALID);
        if (!tradeable) return Quote.refused(UNTRADEABLE);
        if (available < quantity) return Quote.refused(NOT_HOLDING);
        try {
            long gross = Math.multiplyExact(unit, quantity);
            long fee = Fees.fee(gross, feePercent);
            long listing = Fees.flat(listingFee);
            return new Quote(true, SELL, quantity, unit, gross, fee, listing, gross - fee, false);
        } catch (ArithmeticException overflow) {
            return Quote.refused(TOO_LARGE);
        }
    }

    /**
     * Placing a buy order for {@code quantity} at {@code unit} each: the escrow, and the listing fee on top.
     *
     * @param known whether the item id names an item this server has and may trade
     */
    public static Quote bid(long quantity, long unit, boolean known, boolean tradeable, long listingFee) {
        if (!quantityOk(quantity) || unit < 1L) return Quote.refused(INVALID);
        if (!known) return Quote.refused(UNKNOWN_ITEM);
        if (!tradeable) return Quote.refused(UNTRADEABLE);
        try {
            long escrow = Math.multiplyExact(unit, quantity);
            long listing = Fees.flat(listingFee);
            return new Quote(true, BID, quantity, unit, Math.addExact(escrow, listing), 0L, listing, escrow, false);
        } catch (ArithmeticException overflow) {
            return Quote.refused(TOO_LARGE);
        }
    }
}
