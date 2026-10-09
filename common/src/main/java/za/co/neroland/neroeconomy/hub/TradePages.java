package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Trade tab's pages: offers sent to the viewer, offers the viewer sent, the "Send offer" form
 * with the vault to give from, and the direct-trade starter. An offer row shows what it gives and
 * what it asks, never who sent it or whom it is for: the offer model never tells either side who
 * the other is, so incoming offers are from "a player" and outgoing ones to "a player" (the
 * player addressed them when sending; the hub does not repeat the name back). The players near
 * enough for a direct trade are never on a page: the client lists the ones it can see itself. Pure Java.
 */
public final class TradePages {
    public static final int OFFER_ROWS = 6;
    public static final int VAULT_ROWS = 6;

    // --- header names ------------------------------------------------------------------------
    public static final String VIEW = "view";
    public static final String MUTED = "muted";
    public static final String INCOMING = "incoming";
    public static final String OUTGOING = "outgoing";
    public static final String MAX_OFFERS = "max_offers";
    public static final String RANGE = "range";
    public static final String TAX_BPS = "tax_bps";
    public static final String HOURS = "hours";

    public static final String OFFER_IN = "gui.neroeconomy.hub.trade.offer_in";
    public static final String OFFER_OUT = "gui.neroeconomy.hub.trade.offer_out";
    public static final String VAULT_ROW = "gui.neroeconomy.hub.trade.vault_row";
    public static final List<String> KEYS = List.of(OFFER_IN, OFFER_OUT, VAULT_ROW);

    /** Row flag: the offer asks for items in return. */
    public static final int FLAG_ASKS_ITEMS = HubPage.FLAG_A;
    /** Row flag: a vault line that is not an unmodified stack. */
    public static final int FLAG_MODIFIED = HubPage.FLAG_C;

    private TradePages() { }

    /**
     * One offer as the viewer sees it.
     *
     * @param icon      the item of its first lot; empty when it gives credits only
     * @param items     how many items it gives, of every kind together
     * @param kinds     how many different lots it gives
     * @param askItem   the item id asked in return; empty for none
     * @param leftMillis how long until it lapses
     */
    public record OfferLine(UUID id, String icon, long items, int kinds, long credits, String askItem, long askCount,
            long askCredits, long leftMillis) { }

    /**
     * @param taxBasisPoints the trade tax on the credits side, in hundredths of a percent
     * @param hours          how long an offer waits for an answer
     */
    public record TradeView(HubAction.TradeView view, List<OfferLine> incoming, List<OfferLine> outgoing,
            List<HubPages.VaultLine> vault, boolean muted, int maxOffers, int rangeBlocks, long taxBasisPoints,
            int hours) {
        public TradeView {
            incoming = incoming == null ? List.of() : List.copyOf(incoming);
            outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
            vault = vault == null ? List.of() : List.copyOf(vault);
        }
    }

    public static HubPages.Built build(TradeView view, long requestedPage) {
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        int pages = 1;
        int page = 0;
        switch (view.view()) {
            case INBOX, OUTBOX -> {
                boolean in = view.view() == HubAction.TradeView.INBOX;
                List<OfferLine> lines = in ? view.incoming() : view.outgoing();
                pages = HubPage.pages(lines.size(), OFFER_ROWS);
                page = HubPage.clampPage(requestedPage, pages);
                int from = page * OFFER_ROWS;
                for (OfferLine line : lines.subList(Math.min(lines.size(), from), Math.min(lines.size(), from + OFFER_ROWS))) {
                    List<String> args = new ArrayList<>();
                    args.add(line.askItem() == null ? "" : line.askItem());
                    rows.add(new HubPage.Row(line.id(), line.icon(), in ? OFFER_IN : OFFER_OUT, args,
                            new long[] { line.items(), line.kinds(), line.credits(), line.askCount(), line.askCredits(),
                                    line.leftMillis() }, line.askCount() > 0L ? FLAG_ASKS_ITEMS : 0));
                    refs.put(line.id(), new HubPages.RowRef(in ? HubPages.RowRef.Kind.OFFER_IN
                            : HubPages.RowRef.Kind.OFFER_OUT, line.id(), "", 0));
                }
            }
            case SEND -> {
                pages = HubPage.pages(view.vault().size(), VAULT_ROWS);
                page = HubPage.clampPage(requestedPage, pages);
                int index = page * VAULT_ROWS;
                List<HubPages.VaultLine> lines = view.vault();
                for (HubPages.VaultLine line : lines.subList(Math.min(lines.size(), index),
                        Math.min(lines.size(), index + VAULT_ROWS))) {
                    UUID id = HubPages.lineId("give", line.item(), line.payload(), index++);
                    rows.add(new HubPage.Row(id, line.item(), VAULT_ROW, List.of(), new long[] { line.count() },
                            line.modified() ? FLAG_MODIFIED : 0));
                    refs.put(id, new HubPages.RowRef(HubPages.RowRef.Kind.VAULT_LINE, null, line.item(), line.payload()));
                }
            }
            default -> { }
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(VIEW, (long) view.view().ordinal());
        numbers.put(MUTED, view.muted() ? 1L : 0L);
        numbers.put(INCOMING, (long) view.incoming().size());
        numbers.put(OUTGOING, (long) view.outgoing().size());
        numbers.put(MAX_OFFERS, (long) view.maxOffers());
        numbers.put(RANGE, (long) view.rangeBlocks());
        numbers.put(TAX_BPS, view.taxBasisPoints());
        numbers.put(HOURS, (long) view.hours());
        return new HubPages.Built(new HubPage(0, 0, HubTab.TRADE, page, pages, rows, numbers, Map.of()), refs);
    }
}
