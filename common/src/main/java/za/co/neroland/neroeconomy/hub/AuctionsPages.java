package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Auctions tab's pages: every running auction the viewer may see (a Merchant House's lots
 * badged with the House's name; interplanetary lots only once the viewer has reached orbit), the
 * viewer's own auctions, and the "Create" form. A row says what is on offer, the standing bid,
 * the least the next bid may be and the time left by the server's clock; it never says who is
 * selling (beyond a House) or who is bidding, only whether that is the viewer. Pure Java.
 */
public final class AuctionsPages {
    public static final int AUCTION_ROWS = 6;

    // --- header names ------------------------------------------------------------------------
    public static final String VIEW = "view";
    public static final String FEE_BPS = "fee_bps";
    public static final String INCREMENT = "increment";
    public static final String OPEN_MINE = "open_mine";
    public static final String MAX_AUCTIONS = "max_auctions";
    public static final String ORBIT = "orbit";
    public static final String MAX_MINUTES = "max_minutes";
    public static final String MAX_QUANTITY = "max_quantity";

    /** The longest auction a player may start, in minutes: one week, as the command allows. */
    public static final int LONGEST_MINUTES = 10_080;
    /** The most of one stack a player may auction at once, as the command allows. */
    public static final int MOST_PER_LOT = 64;

    public static final String ROW = "gui.neroeconomy.hub.auctions.row";
    public static final List<String> KEYS = List.of(ROW);

    /** Row flags. */
    public static final int FLAG_HOUSE = HubPage.FLAG_A;
    public static final int FLAG_MINE = HubPage.FLAG_B;
    public static final int FLAG_LEADING = HubPage.FLAG_C;
    public static final int FLAG_ORBIT = HubPage.FLAG_D;

    private AuctionsPages() { }

    /**
     * One auction as the viewer may see it.
     *
     * @param houseKey    the selling House's name key; empty for a player's lot
     * @param minimumNext the least the next bid may be; -1 when it has ended
     * @param leading     whether the standing bid is the viewer's own
     */
    public record AuctionLine(UUID id, String item, long count, String houseKey, long highestBid, long minimumNext,
            long leftMillis, boolean mine, boolean leading, boolean interplanetary) { }

    /**
     * @param feeBasisPoints the auction fee taken from the winning bid, in hundredths of a percent
     * @param orbit          whether the viewer may start an interplanetary auction
     */
    public record AuctionsView(HubAction.AuctionsView view, List<AuctionLine> auctions, long feeBasisPoints,
            int incrementPercent, int openMine, int maxAuctions, boolean orbit) {
        public AuctionsView {
            auctions = auctions == null ? List.of() : List.copyOf(auctions);
        }
    }

    public static HubPages.Built build(AuctionsView view, long requestedPage) {
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        int pages = 1;
        int page = 0;
        if (view.view() != HubAction.AuctionsView.CREATE) {
            List<AuctionLine> lines = view.auctions();
            pages = HubPage.pages(lines.size(), AUCTION_ROWS);
            page = HubPage.clampPage(requestedPage, pages);
            int from = page * AUCTION_ROWS;
            for (AuctionLine line : lines.subList(Math.min(lines.size(), from), Math.min(lines.size(), from + AUCTION_ROWS))) {
                int flags = (line.houseKey().isEmpty() ? 0 : FLAG_HOUSE) | (line.mine() ? FLAG_MINE : 0)
                        | (line.leading() ? FLAG_LEADING : 0) | (line.interplanetary() ? FLAG_ORBIT : 0);
                rows.add(new HubPage.Row(line.id(), line.item(), ROW, List.of(line.houseKey()),
                        new long[] { line.count(), line.highestBid(), line.minimumNext(), line.leftMillis() }, flags));
                refs.put(line.id(), new HubPages.RowRef(HubPages.RowRef.Kind.AUCTION, line.id(), line.item(), 0));
            }
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(VIEW, (long) view.view().ordinal());
        numbers.put(FEE_BPS, view.feeBasisPoints());
        numbers.put(INCREMENT, (long) view.incrementPercent());
        numbers.put(OPEN_MINE, (long) view.openMine());
        numbers.put(MAX_AUCTIONS, (long) view.maxAuctions());
        numbers.put(ORBIT, view.orbit() ? 1L : 0L);
        numbers.put(MAX_MINUTES, (long) LONGEST_MINUTES);
        numbers.put(MAX_QUANTITY, (long) MOST_PER_LOT);
        return new HubPages.Built(new HubPage(0, 0, HubTab.AUCTIONS, page, pages, rows, numbers, Map.of()), refs);
    }
}
