package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Invest tab's pages, from a plain view of the exchange as one viewer may see it: what can be
 * bought, the viewer's own portfolio (real or practice), the product they picked with its price
 * line, the last quote and the "what could happen in a week" outlook. Every number is the server's;
 * the client only draws them. Nothing here names a player. Pure Java.
 *
 * <p>Rows of the products view: a company or the index fund ({@link HubPages.RowRef.Kind#STOCK}),
 * or a Savings Bond term ({@link HubPages.RowRef.Kind#BOND_TERM}). Rows of the portfolio view: a
 * holding ({@link HubPages.RowRef.Kind#HOLDING}) or a Savings Bond held
 * ({@link HubPages.RowRef.Kind#BOND}).
 */
public final class InvestPages {
    /** Rows per page of each list. */
    public static final int PRODUCT_ROWS = 8;
    public static final int PORTFOLIO_ROWS = 6;
    /** At most this many recent points go into the chart; the rest of the series is the daily history. */
    public static final int CHART_RECENT = 48;
    public static final int CHART_DAILY = 30;

    // --- header names ------------------------------------------------------------------------
    public static final String PRACTICE = "practice";
    public static final String VIEW = "view";
    public static final String PLAY_BALANCE = "play_balance";
    public static final String CAN_RESET = "can_reset";
    public static final String EXCHANGE_ON = "exchange_on";
    public static final String PICKED = "picked";
    public static final String PICK_BOND = "pick_bond";
    public static final String PICK_INDEX = "pick_index";
    public static final String CHART_RECENT_COUNT = "chart_recent";
    public static final String CHART_SPLIT = "chart_split";
    public static final String CHART_HALTED = "chart_halted";
    public static final String Q_FOR = "q_for";
    public static final String Q_OK = "q_ok";
    public static final String Q_ASKED = "q_asked";
    public static final String Q_UNITS = "q_units";
    public static final String Q_UNIT = "q_unit";
    public static final String Q_TOTAL = "q_total";
    public static final String Q_FEE = "q_fee";
    public static final String Q_NET = "q_net";
    public static final String Q_COST = "q_cost";
    public static final String Q_GAIN = "q_gain";
    public static final String Q_PAYABLE = "q_payable";
    public static final String Q_FORFEIT = "q_forfeit";
    public static final String Q_LOSS = "q_loss";
    public static final String Q_SELLABLE = "q_sellable";
    public static final String Q_WAIT = "q_wait";
    public static final String Q_REDUCED = "q_reduced";
    public static final String O_SHOWN = "o_shown";
    public static final String O_BAD = "o_bad";
    public static final String O_TYPICAL = "o_typical";
    public static final String O_GOOD = "o_good";
    public static final String O_DAYS = "o_days";
    public static final String P_COST = "p_cost";
    public static final String P_VALUE = "p_value";
    public static final String P_DIFFERENCE = "p_diff";
    public static final String P_BONDS = "p_bonds";
    public static final String P_INVESTED = "p_invested";
    public static final String P_LIMIT = "p_limit";
    public static final String P_LAST_DIVIDEND = "p_last_div";
    public static final String P_DIVIDEND_PERCENT = "p_div_pct";
    public static final String P_DIVIDEND_SCALED = "p_div_scaled";
    /** Header keys. */
    public static final String BANNER = "banner";
    public static final String PICK_NAME = "pick_name";
    public static final String PICK_RISK = "pick_risk";
    public static final String PICK_STATUS = "pick_status";
    public static final String Q_REASON = "q_reason";

    /** The banner every practice page carries, whatever else it shows. */
    public static final String PRACTICE_BANNER = "gui.neroeconomy.hub.invest.practice_banner";
    public static final String BOND_ROW = "gui.neroeconomy.hub.invest.bond_row";
    public static final List<String> KEYS = List.of(PRACTICE_BANNER, BOND_ROW);

    /** Row flags. */
    public static final int FLAG_INDEX = HubPage.FLAG_A;
    public static final int FLAG_BOND = HubPage.FLAG_B;
    public static final int FLAG_STOPPED = HubPage.FLAG_C;
    public static final int FLAG_MATURED = HubPage.FLAG_D;

    private InvestPages() { }

    /**
     * A company or the index fund. Prices in hundredths of a credit.
     *
     * @param statusKey why it cannot be traded now; empty when it can
     */
    public record ProductLine(String id, String nameKey, String riskKey, String statusKey, boolean index,
            long askCents, long bidCents, int dayChangeBasisPoints, int dividendBasisPoints) { }

    /** A Savings Bond term on offer. @param rateBasisPoints the interest for the whole term */
    public record BondTermLine(String term, String nameKey, String riskKey, int days, int rateBasisPoints) { }

    /**
     * A holding. @param waitMillis how long until the next units may be sold; 0 when all may be
     * @param bidCents what one unit fetches now
     */
    public record HoldingLine(String stockId, String nameKey, String statusKey, long units, long cost, long value,
            long difference, long sellableUnits, long waitMillis, long bidCents) { }

    /** A Savings Bond held. @param leftMillis how long until it matures; 0 once it has */
    public record BondLine(UUID id, String nameKey, int days, long principal, long interest, long leftMillis,
            boolean matured) { }

    /**
     * The viewer's portfolio totals.
     *
     * @param dividendPercent what the last dividend day paid of what was due, for everyone
     * @param dividendScaled  whether that was less than all of it
     */
    public record Totals(long cost, long value, long difference, long bondPrincipal, long invested, long limit,
            long lastDividend, int dividendPercent, boolean dividendScaled) { }

    /** What was picked. @param bond a Savings Bond term rather than a share */
    public record Picked(String id, String nameKey, String riskKey, String statusKey, boolean bond, boolean index) { }

    /**
     * The price line of the picked product, in hundredths of a credit.
     *
     * @param splitDay index into {@code daily} of the day it last split; -1 for none in view
     */
    public record Chart(long[] recent, long[] daily, int splitDay, boolean halted) { }

    /**
     * The last quote: a purchase, a sale, or a Savings Bond.
     *
     * @param asked   the credits (purchase) or units (sale, 0 = all) the quote was for
     * @param reduced a sale the Reserve cannot pay the whole gain of today: it can only go ahead by
     *                accepting {@code payable}
     */
    public record Quote(HubAction action, boolean ok, String reasonKey, long asked, long units, long unitCents,
            long total, long fee, long net, long cost, long gain, long payable, long forfeit, long loss,
            long sellable, long waitMillis, boolean reduced) { }

    /** "What could happen in a week": what comes back, in credits, from the stake. */
    public record Outlook(long bad, long typical, long good, int days) { }

    /** Everything one Invest page is built from. Nothing in it names a player. */
    public record InvestView(boolean practice, HubAction.InvestView view, boolean exchangeOn,
            List<ProductLine> products, List<BondTermLine> bondTerms, List<HoldingLine> holdings,
            List<BondLine> bonds, Totals totals, long playBalance, boolean canReset, Picked picked, Chart chart,
            Quote quote, Outlook outlook) {
        public InvestView {
            products = products == null ? List.of() : List.copyOf(products);
            bondTerms = bondTerms == null ? List.of() : List.copyOf(bondTerms);
            holdings = holdings == null ? List.of() : List.copyOf(holdings);
            bonds = bonds == null ? List.of() : List.copyOf(bonds);
        }
    }

    /** The row id of a product, bond term or holding: stable for the same thing. */
    public static UUID productId(String kind, String id) {
        return HubPages.lineId(kind, id == null ? "" : id, 0, 0);
    }

    public static HubPages.Built build(InvestView view, long requestedPage) {
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        int pages;
        int page;
        if (view.view() == HubAction.InvestView.PORTFOLIO) {
            int total = view.holdings().size() + view.bonds().size();
            pages = HubPage.pages(total, PORTFOLIO_ROWS);
            page = HubPage.clampPage(requestedPage, pages);
            int from = page * PORTFOLIO_ROWS;
            for (int index = from; index < Math.min(total, from + PORTFOLIO_ROWS); index++) {
                if (index < view.holdings().size()) {
                    HoldingLine line = view.holdings().get(index);
                    UUID id = productId("holding", line.stockId());
                    List<String> args = new ArrayList<>();
                    args.add(line.statusKey());
                    rows.add(new HubPage.Row(id, "", line.nameKey(), args, new long[] { line.units(), line.cost(),
                            line.value(), line.difference(), line.sellableUnits(), line.waitMillis() },
                            line.statusKey().isEmpty() ? 0 : FLAG_STOPPED));
                    refs.put(id, new HubPages.RowRef(HubPages.RowRef.Kind.HOLDING, null, line.stockId(), 0));
                } else {
                    BondLine line = view.bonds().get(index - view.holdings().size());
                    rows.add(new HubPage.Row(line.id(), "", line.nameKey(), List.of(), new long[] { line.principal(),
                            line.interest(), line.leftMillis(), line.days() },
                            FLAG_BOND | (line.matured() ? FLAG_MATURED : 0)));
                    refs.put(line.id(), new HubPages.RowRef(HubPages.RowRef.Kind.BOND, line.id(), "", 0));
                }
            }
        } else {
            int total = view.products().size() + view.bondTerms().size();
            pages = HubPage.pages(total, PRODUCT_ROWS);
            page = HubPage.clampPage(requestedPage, pages);
            int from = page * PRODUCT_ROWS;
            for (int index = from; index < Math.min(total, from + PRODUCT_ROWS); index++) {
                if (index < view.products().size()) {
                    ProductLine line = view.products().get(index);
                    UUID id = productId("stock", line.id());
                    rows.add(new HubPage.Row(id, "", line.nameKey(), List.of(line.riskKey(), line.statusKey()),
                            new long[] { line.askCents(), line.bidCents(), Math.max(0L, line.askCents() - line.bidCents()),
                                    line.dayChangeBasisPoints(), line.dividendBasisPoints() },
                            (line.index() ? FLAG_INDEX : 0) | (line.statusKey().isEmpty() ? 0 : FLAG_STOPPED)));
                    refs.put(id, new HubPages.RowRef(HubPages.RowRef.Kind.STOCK, null, line.id(), 0));
                } else {
                    BondTermLine line = view.bondTerms().get(index - view.products().size());
                    UUID id = productId("bond", line.term());
                    rows.add(new HubPage.Row(id, "", line.nameKey(), List.of(line.riskKey()),
                            new long[] { line.rateBasisPoints(), line.days() }, FLAG_BOND));
                    refs.put(id, new HubPages.RowRef(HubPages.RowRef.Kind.BOND_TERM, null, line.term(), 0));
                }
            }
        }

        Map<String, Long> numbers = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        numbers.put(PRACTICE, view.practice() ? 1L : 0L);
        if (view.practice()) keys.put(BANNER, PRACTICE_BANNER);
        numbers.put(VIEW, (long) view.view().ordinal());
        numbers.put(EXCHANGE_ON, view.exchangeOn() ? 1L : 0L);
        numbers.put(PLAY_BALANCE, view.practice() ? view.playBalance() : 0L);
        numbers.put(CAN_RESET, view.practice() && view.canReset() ? 1L : 0L);
        long[] series = null;
        if (view.view() == HubAction.InvestView.PORTFOLIO) {
            Totals totals = view.totals();
            if (totals != null) {
                numbers.put(P_COST, totals.cost());
                numbers.put(P_VALUE, totals.value());
                numbers.put(P_DIFFERENCE, totals.difference());
                numbers.put(P_BONDS, totals.bondPrincipal());
                numbers.put(P_INVESTED, totals.invested());
                numbers.put(P_LIMIT, totals.limit());
                numbers.put(P_LAST_DIVIDEND, totals.lastDividend());
                numbers.put(P_DIVIDEND_PERCENT, (long) totals.dividendPercent());
                numbers.put(P_DIVIDEND_SCALED, totals.dividendScaled() ? 1L : 0L);
            }
        } else {
            Picked picked = view.picked();
            numbers.put(PICKED, picked == null ? 0L : 1L);
            if (picked != null) {
                numbers.put(PICK_BOND, picked.bond() ? 1L : 0L);
                numbers.put(PICK_INDEX, picked.index() ? 1L : 0L);
                keys.put(PICK_NAME, picked.nameKey());
                keys.put(PICK_RISK, picked.riskKey());
                if (!picked.statusKey().isEmpty()) keys.put(PICK_STATUS, picked.statusKey());
            }
            Chart chart = view.chart();
            if (picked != null && chart != null) {
                long[] recent = thin(chart.recent(), CHART_RECENT);
                long[] daily = thin(chart.daily(), CHART_DAILY);
                series = new long[recent.length + daily.length];
                System.arraycopy(recent, 0, series, 0, recent.length);
                System.arraycopy(daily, 0, series, recent.length, daily.length);
                numbers.put(CHART_RECENT_COUNT, (long) recent.length);
                int shift = chart.daily().length - daily.length;
                int split = chart.splitDay() - shift;
                numbers.put(CHART_SPLIT, split >= 0 && split < daily.length ? split : -1L);
                numbers.put(CHART_HALTED, chart.halted() ? 1L : 0L);
            }
            Outlook outlook = view.outlook();
            numbers.put(O_SHOWN, outlook == null ? 0L : 1L);
            if (outlook != null) {
                numbers.put(O_BAD, outlook.bad());
                numbers.put(O_TYPICAL, outlook.typical());
                numbers.put(O_GOOD, outlook.good());
                numbers.put(O_DAYS, (long) outlook.days());
            }
        }
        putQuote(view.quote(), numbers, keys);
        HubPage built = new HubPage(0, 0, HubTab.INVEST, page, pages, rows, numbers, keys, series);
        return new HubPages.Built(built, refs);
    }

    private static void putQuote(Quote quote, Map<String, Long> numbers, Map<String, String> keys) {
        if (quote == null || quote.action() == null) return;
        numbers.put(Q_FOR, (long) quote.action().ordinal());
        numbers.put(Q_OK, quote.ok() ? 1L : 0L);
        numbers.put(Q_ASKED, quote.asked());
        numbers.put(Q_UNITS, quote.units());
        numbers.put(Q_UNIT, quote.unitCents());
        numbers.put(Q_TOTAL, quote.total());
        numbers.put(Q_FEE, quote.fee());
        numbers.put(Q_NET, quote.net());
        numbers.put(Q_COST, quote.cost());
        numbers.put(Q_GAIN, quote.gain());
        numbers.put(Q_PAYABLE, quote.payable());
        numbers.put(Q_FORFEIT, quote.forfeit());
        numbers.put(Q_LOSS, quote.loss());
        numbers.put(Q_SELLABLE, quote.sellable());
        numbers.put(Q_WAIT, quote.waitMillis());
        numbers.put(Q_REDUCED, quote.reduced() ? 1L : 0L);
        if (quote.reasonKey() != null && !quote.reasonKey().isEmpty()) keys.put(Q_REASON, quote.reasonKey());
    }

    /**
     * The last {@code max} points of a series, thinned evenly when it holds more: the newest point
     * is always kept, so the line ends at the price shown beside it.
     */
    public static long[] thin(long[] all, int max) {
        if (all == null || all.length == 0 || max <= 0) return new long[0];
        if (all.length <= max) return all.clone();
        long[] out = new long[max];
        for (int index = 0; index < max; index++) {
            long at = (long) (all.length - 1) * index / (max - 1);
            out[index] = all[(int) at];
        }
        return out;
    }

    /** The index into a daily history of {@code day}, counting the newest entry as {@code today}; -1 if outside. */
    public static int dayIndex(int dailyLength, long today, long day) {
        if (dailyLength <= 0 || day < 0L || day > today) return -1;
        long index = dailyLength - 1L - (today - day);
        return index < 0L ? -1 : (int) index;
    }
}
