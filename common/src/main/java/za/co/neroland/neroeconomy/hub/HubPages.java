package za.co.neroland.neroeconomy.hub;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the page of each built tab from a plain view of what the viewer may see. A view is filled
 * in by the server ({@link HubSession}) and holds nothing but the viewer's own figures and the ids of
 * orders, rules and listings: no player name or id goes into one, so none can come out on a page.
 * Every builder also returns what each row stands for on the server ({@link RowRef}), which is how
 * an intent naming a row is checked against the page the player was actually sent. Pure Java.
 */
public final class HubPages {
    /** Rows per page of each list. */
    public static final int WALLET_ROWS = 7;
    public static final int VAULT_ROWS = 8;
    public static final int MARKET_ROWS = 5;
    public static final int ORDERS_ROWS = 6;

    // --- header names ------------------------------------------------------------------------
    public static final String ADMIN = "admin";
    public static final String AT_BANK = "at_bank";
    public static final String STALL = "stall";
    public static final String HOUSE = "house";
    public static final String BALANCE = "balance";
    public static final String LOCKED = "locked";
    public static final String UNLOCK_MINUTES = "unlock_minutes";
    public static final String CLAIM = "claim";
    public static final String OPT_OUT = "opt_out";
    public static final String USED = "used";
    public static final String CAP = "cap";
    public static final String MODE = "mode";
    public static final String QUOTE = "quote";
    public static final String QUOTE_ACTION = "q_action";
    public static final String QUOTE_OK = "q_ok";
    public static final String QUOTE_QTY = "q_qty";
    public static final String QUOTE_UNIT = "q_unit";
    public static final String QUOTE_TOTAL = "q_total";
    public static final String QUOTE_FEE = "q_fee";
    public static final String QUOTE_LISTING = "q_listing";
    public static final String QUOTE_NET = "q_net";
    public static final String QUOTE_VAULT = "q_vault";
    public static final String RULES = "rules";
    public static final String MAX_RULES = "max_rules";
    public static final String VALUE_TODAY = "value_today";
    public static final String VALUE_CAP = "value_cap";
    public static final String PAUSED_ALL = "paused_all";

    // --- row title keys ----------------------------------------------------------------------
    public static final String SELLER_PLAYER = "gui.neroeconomy.hub.market.seller.player";
    public static final String SELLER_SHOP = "gui.neroeconomy.hub.market.seller.shop";
    public static final String VAULT_ROW = "gui.neroeconomy.hub.vault.row";
    public static final String MARKET_VAULT_ROW = "gui.neroeconomy.hub.market.vault_row";
    public static final String ORDER_SELL = "gui.neroeconomy.hub.orders.kind.sell";
    public static final String ORDER_BUY = "gui.neroeconomy.hub.orders.kind.buy";
    public static final String ORDER_SHOP = "gui.neroeconomy.hub.orders.kind.shop";
    public static final String RULE_BUY = "gui.neroeconomy.hub.orders.kind.rule_buy";
    public static final String RULE_SELL = "gui.neroeconomy.hub.orders.kind.rule_sell";
    public static final String COMING_SOON = "gui.neroeconomy.hub.coming_soon";
    public static final String CREDIT_ICON = "neroeconomy:credit_chip";

    /** Every row and header key a builder can put on a page, for the lang check. */
    public static final List<String> KEYS = List.of(SELLER_PLAYER, SELLER_SHOP, VAULT_ROW, MARKET_VAULT_ROW,
            ORDER_SELL, ORDER_BUY, ORDER_SHOP, RULE_BUY, RULE_SELL, COMING_SOON);

    private HubPages() { }

    /** What a page is, and what each of its rows stands for on the server. */
    public record Built(HubPage page, Map<UUID, RowRef> refs) {
        public Built {
            refs = Map.copyOf(refs);
        }
    }

    /**
     * What a row stands for on the server. Never sent: the client only ever has the row's id.
     *
     * @param id      the order, rule or listing id; null for a vault line
     * @param item    the item of a vault line
     * @param payload the payload of a vault line
     */
    public record RowRef(Kind kind, UUID id, String item, int payload) {
        public enum Kind {
            HISTORY, VAULT_LINE, LISTING, SELL_ORDER, BUY_ORDER, SHOP, RULE,
            /** A company or the index fund ({@code item} is its stock id). */
            STOCK,
            /** A Savings Bond term on offer ({@code item} is the term's key). */
            BOND_TERM,
            /** A holding of the viewer's ({@code item} is its stock id). */
            HOLDING,
            /** A Savings Bond the viewer holds. */
            BOND,
            /** An offer sent to the viewer. */
            OFFER_IN,
            /** An offer the viewer sent. */
            OFFER_OUT,
            /** A job, a delivery or a bounty. */
            JOB,
            /** An auction, a player's or a Merchant House's. */
            AUCTION,
            /** One of the viewer's Player Shops ({@code item} is its location key). */
            PLAYER_SHOP,
            /** A line of the viewer's "My data" summary: nothing can be done to it. */
            DATA,
            /** The account an operator looked up on the Admin tab ({@code id} is its UUID; never sent). */
            ADMIN_TARGET,
            /** A read-only line of an Admin tab table. */
            ADMIN_LINE
        }
    }

    /** A row id made up for a line that has none of its own; stable for the same line. */
    public static UUID lineId(String kind, String item, int payload, int index) {
        return UUID.nameUUIDFromBytes((kind + "|" + item + "|" + payload + "|" + index)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static <T> List<T> slice(List<T> all, int page, int perPage) {
        int from = Math.min(all.size(), page * perPage);
        return all.subList(from, Math.min(all.size(), from + perPage));
    }

    // --- placeholder -------------------------------------------------------------------------

    /** A tab this build does not have yet: no rows, and the page says what to do instead. */
    public static Built comingSoon(HubTab tab) {
        return new Built(new HubPage(0, 0, tab, 0, 1, List.of(), Map.of(), Map.of(QUOTE, COMING_SOON)), Map.of());
    }

    // --- wallet ------------------------------------------------------------------------------

    /**
     * One line of the viewer's own history. The counterparty is never part of it.
     *
     * @param typeKey   the lang key of the plain-language kind
     * @param wallMillis when, in wall-clock milliseconds
     */
    public record HistoryLine(String typeKey, long amount, boolean incoming, long wallMillis) { }

    /**
     * @param locked        starting credits that cannot be paid to other players yet
     * @param unlockMinutes minutes of play until they unlock; 0 when none are locked
     * @param claim         credits waiting to be collected
     * @param optOut        whether the viewer opted out of the activity log
     */
    public record WalletView(long balance, long locked, long unlockMinutes, long claim, boolean optOut,
            List<HistoryLine> history) { }

    public static Built wallet(WalletView view, long requestedPage) {
        int pages = HubPage.pages(view.history().size(), WALLET_ROWS);
        int page = HubPage.clampPage(requestedPage, pages);
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, RowRef> refs = new LinkedHashMap<>();
        int index = page * WALLET_ROWS;
        for (HistoryLine line : slice(view.history(), page, WALLET_ROWS)) {
            UUID id = new UUID(0L, ++index);
            rows.add(new HubPage.Row(id, CREDIT_ICON, line.typeKey(), List.of(),
                    new long[] { line.wallMillis(), line.amount() }, line.incoming() ? HubPage.FLAG_A : 0));
            refs.put(id, new RowRef(RowRef.Kind.HISTORY, null, "", 0));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(BALANCE, view.balance());
        numbers.put(LOCKED, view.locked());
        numbers.put(UNLOCK_MINUTES, view.unlockMinutes());
        numbers.put(CLAIM, view.claim());
        numbers.put(OPT_OUT, view.optOut() ? 1L : 0L);
        return new Built(new HubPage(0, 0, HubTab.WALLET, page, pages, rows, numbers, Map.of()), refs);
    }

    // --- vault -------------------------------------------------------------------------------

    /** One stack of the viewer's vault. @param modified whether it is not an unmodified stack */
    public record VaultLine(String item, long count, int payload) {
        public boolean modified() { return payload != 0; }
    }

    public record VaultView(int used, int cap, List<VaultLine> lines) { }

    public static Built vault(VaultView view, long requestedPage) {
        int pages = HubPage.pages(view.lines().size(), VAULT_ROWS);
        int page = HubPage.clampPage(requestedPage, pages);
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, RowRef> refs = new LinkedHashMap<>();
        int index = page * VAULT_ROWS;
        for (VaultLine line : slice(view.lines(), page, VAULT_ROWS)) {
            UUID id = lineId("vault", line.item(), line.payload(), index++);
            rows.add(new HubPage.Row(id, line.item(), VAULT_ROW, List.of(), new long[] { line.count() },
                    line.modified() ? HubPage.FLAG_C : 0));
            refs.put(id, new RowRef(RowRef.Kind.VAULT_LINE, null, line.item(), line.payload()));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(USED, (long) view.used());
        numbers.put(CAP, (long) view.cap());
        return new Built(new HubPage(0, 0, HubTab.VAULT, page, pages, rows, numbers, Map.of()), refs);
    }

    // --- market ------------------------------------------------------------------------------

    /** Who is selling a market row. A player is only ever "a player", never named. */
    public enum SellerKind { PLAYER, SHOP, HOUSE }

    /**
     * One thing on offer, as this viewer sees it.
     *
     * @param id       the listing or sell order id
     * @param houseKey the lang key of the Merchant House's name for a {@link SellerKind#HOUSE} row
     * @param unit     what this viewer is charged each
     * @param bestBid  the best buy order price for the item; 0 when there is none
     * @param bestAsk  the best sell order price for the item; 0 when there is none
     * @param mayBuy   for a commodity, how many the viewer may still buy from the Houses today; -1 otherwise
     * @param maySell  for a commodity, how many the viewer may still sell to the Houses today; -1 otherwise
     */
    public record MarketLine(UUID id, String item, SellerKind kind, String houseKey, long unit, long stock,
            long bestBid, long bestAsk, long mayBuy, long maySell, boolean own, boolean modified) { }

    /** The seller badge a row shows: the House's own name, or "player" / "shop". */
    public static String badge(SellerKind kind, String houseKey) {
        return switch (kind) {
            case HOUSE -> houseKey == null || houseKey.isEmpty() ? SELLER_SHOP : houseKey;
            case SHOP -> SELLER_SHOP;
            default -> SELLER_PLAYER;
        };
    }

    /**
     * @param total how many rows the whole search found
     * @param quote the last quote asked for on this tab, or null
     */
    public record MarketView(HubAction.MarketMode mode, List<MarketLine> lines, long total, List<VaultLine> vault,
            HubQuotes.Quote quote, HubAction quoteFor) { }

    public static Built market(MarketView view, long requestedPage) {
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, RowRef> refs = new LinkedHashMap<>();
        int pages;
        int page;
        if (view.mode() == HubAction.MarketMode.VAULT) {
            pages = HubPage.pages(view.vault().size(), MARKET_ROWS);
            page = HubPage.clampPage(requestedPage, pages);
            int index = page * MARKET_ROWS;
            for (VaultLine line : slice(view.vault(), page, MARKET_ROWS)) {
                UUID id = lineId("pick", line.item(), line.payload(), index++);
                rows.add(new HubPage.Row(id, line.item(), MARKET_VAULT_ROW, List.of(), new long[] { line.count() },
                        line.modified() ? HubPage.FLAG_C : 0));
                refs.put(id, new RowRef(RowRef.Kind.VAULT_LINE, null, line.item(), line.payload()));
            }
        } else {
            // The lines are already the requested page of the search; the total sets the page count.
            pages = HubPage.pages(view.total(), MARKET_ROWS);
            page = HubPage.clampPage(requestedPage, pages);
            for (MarketLine line : view.lines()) {
                if (rows.size() >= MARKET_ROWS) break;
                int flags = (line.own() ? HubPage.FLAG_A : 0) | (line.mayBuy() >= 0L ? HubPage.FLAG_B : 0)
                        | (line.modified() ? HubPage.FLAG_C : 0);
                rows.add(new HubPage.Row(line.id(), line.item(), badge(line.kind(), line.houseKey()), List.of(),
                        new long[] { line.unit(), line.stock(), line.bestBid(), line.bestAsk(), line.mayBuy(),
                                line.maySell() }, flags));
                refs.put(line.id(), new RowRef(RowRef.Kind.LISTING, line.id(), line.item(), 0));
            }
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        numbers.put(MODE, (long) view.mode().ordinal());
        putQuote(view.quote(), view.quoteFor(), numbers, keys);
        return new Built(new HubPage(0, 0, HubTab.MARKET, page, pages, rows, numbers, keys), refs);
    }

    private static void putQuote(HubQuotes.Quote quote, HubAction quoteFor, Map<String, Long> numbers,
            Map<String, String> keys) {
        if (quote == null || quoteFor == null) return;
        keys.put(QUOTE, quote.key());
        numbers.put(QUOTE_ACTION, (long) quoteFor.ordinal());
        numbers.put(QUOTE_OK, quote.ok() ? 1L : 0L);
        numbers.put(QUOTE_QTY, quote.quantity());
        numbers.put(QUOTE_UNIT, quote.unit());
        numbers.put(QUOTE_TOTAL, quote.total());
        numbers.put(QUOTE_FEE, quote.fee());
        numbers.put(QUOTE_LISTING, quote.listingFee());
        numbers.put(QUOTE_NET, quote.net());
        numbers.put(QUOTE_VAULT, quote.toVault() ? 1L : 0L);
    }

    // --- my orders ---------------------------------------------------------------------------

    /** What kind of thing a line of "My orders" is. */
    public enum OrderKind { SELL, BUY, SHOP, RULE }

    /**
     * One of the viewer's own orders, shop listings or rules.
     *
     * <ul>
     *   <li>SELL: {@code a} count left, {@code b} price each, {@code c} time left in ms;</li>
     *   <li>BUY: {@code a} still wanted, {@code b} price each, {@code c} escrow held, {@code d} time left in ms;</li>
     *   <li>SHOP: {@code a} stock, {@code b} price each;</li>
     *   <li>RULE: {@code a} limit price, {@code b} filled today, {@code c} per day, {@code d} done so far,
     *       {@code e} total, {@code f} budget still held.</li>
     * </ul>
     *
     * @param target    a rule's item or tag id (tag without {@code #}); the item otherwise
     * @param statusKey a rule's status lang key; empty otherwise
     */
    public record OrderLine(OrderKind kind, UUID id, String icon, String target, boolean buys, boolean tag,
            boolean paused, String statusKey, long a, long b, long c, long d, long e, long f) { }

    public record OrdersView(List<OrderLine> lines, int rules, int maxRules, long valueToday, long valueCap,
            boolean pausedAll) { }

    public static Built orders(OrdersView view, long requestedPage) {
        int pages = HubPage.pages(view.lines().size(), ORDERS_ROWS);
        int page = HubPage.clampPage(requestedPage, pages);
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, RowRef> refs = new LinkedHashMap<>();
        for (OrderLine line : slice(view.lines(), page, ORDERS_ROWS)) {
            String title;
            RowRef.Kind kind;
            List<String> args = new ArrayList<>();
            int flags = 0;
            switch (line.kind()) {
                case SELL -> { title = ORDER_SELL; kind = RowRef.Kind.SELL_ORDER; flags |= HubPage.FLAG_D; }
                case BUY -> { title = ORDER_BUY; kind = RowRef.Kind.BUY_ORDER; flags |= HubPage.FLAG_D; }
                case SHOP -> { title = ORDER_SHOP; kind = RowRef.Kind.SHOP; }
                default -> {
                    title = line.buys() ? RULE_BUY : RULE_SELL;
                    kind = RowRef.Kind.RULE;
                    flags |= HubPage.FLAG_A;
                    args.add(line.statusKey());
                    if (line.tag()) args.add("#" + line.target());
                }
            }
            if (line.paused()) flags |= HubPage.FLAG_B;
            if (line.tag()) flags |= HubPage.FLAG_C;
            rows.add(new HubPage.Row(line.id(), line.icon(), title, args,
                    new long[] { line.a(), line.b(), line.c(), line.d(), line.e(), line.f() }, flags));
            refs.put(line.id(), new RowRef(kind, line.id(), line.target(), 0));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(RULES, (long) view.rules());
        numbers.put(MAX_RULES, (long) view.maxRules());
        numbers.put(VALUE_TODAY, view.valueToday());
        numbers.put(VALUE_CAP, view.valueCap());
        numbers.put(PAUSED_ALL, view.pausedAll() ? 1L : 0L);
        return new Built(new HubPage(0, 0, HubTab.ORDERS, page, pages, rows, numbers, Map.of()), refs);
    }
}
