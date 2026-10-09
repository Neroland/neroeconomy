package za.co.neroland.neroeconomy.link;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The JSON shapes of the schema-2 sections and action results: {@code summary}, {@code orders},
 * {@code vault}, {@code stocks}, {@code portfolio}, {@code offers}, {@code jobs} and
 * {@code auctions}. The schema-1 sections ({@code balance}, {@code transactions}, {@code market})
 * and the {@code trade} result keep their shapes in {@link LinkPayloads}.
 *
 * <p>Pure: the module reads the stores into the plain records below and this class turns them into
 * JSON, so the whole wire contract is pinned by unit tests and golden samples.
 *
 * <p><b>Conventions.</b> Keys are camelCase, ids are the row's own id as a string (an order, a
 * rule, an offer, a bond: never an account), amounts are whole credits unless the key ends in
 * {@code Cents}, a duration is in milliseconds and ends in {@code In}, and every section carries
 * {@code asOf} in wall-clock milliseconds. Every list is cut to a fixed length.
 *
 * <p><b>Privacy.</b> No record here can carry a player name, a player's UUID, an account reference
 * or a place. The other side of an offer is the fixed words "a player", as in game.
 */
public final class LinkViews {
    /** Longest list any section sends. */
    public static final int MAX_ROWS = 50;
    /** Vault lines sent: more than any vault holds. */
    public static final int MAX_VAULT_ROWS = 128;
    /** Exchange products sent. */
    public static final int MAX_STOCKS = 64;
    /** Recent price samples per product. */
    public static final int RECENT_POINTS = 24;
    /** Daily closing prices per product. */
    public static final int DAILY_POINTS = 30;
    /** Lines of goods shown per offer. */
    public static final int MAX_OFFER_LINES = 9;

    private LinkViews() { }

    // --- records -----------------------------------------------------------------------------

    /** A line of goods. @param modified a renamed, enchanted, filled or worn stack */
    public record Goods(String item, String displayName, long qty, boolean modified) { }

    /** What the daily remote limit allows. */
    public record Remote(boolean enabled, long spentToday, long dailyLimit) {
        public long leftToday() { return Math.max(0L, dailyLimit - spentToday); }
    }

    public record Summary(String currency, long balance, long waitingCredits, int vaultStacks, int vaultCap,
            int sellOrders, int buyOrders, int rules, int rulesMax, int offersIn, int offersOut, long portfolioValue,
            long portfolioCost, int bonds, Remote remote, boolean marketOpen, boolean frozen, long asOf) { }

    public record SellOrderRow(String orderId, Goods goods, long unitPrice, long expiresIn) { }

    public record BuyOrderRow(String orderId, Goods goods, long unitPrice, long escrow, long expiresIn) { }

    public record ShopRow(String listingId, Goods goods, long unitPrice) { }

    /**
     * @param vault      the rule works from its owner's vault, so the app may change it
     * @param statusText the status as one plain sentence
     */
    public record RuleRow(String ruleId, String side, boolean tag, String target, String displayName,
            long limitPrice, int perDay, int filledToday, long total, long done, long budget, boolean paused,
            boolean vault, String status, String statusText) { }

    public record Orders(List<SellOrderRow> sell, List<BuyOrderRow> buy, List<ShopRow> shops, List<RuleRow> rules,
            int rulesMax, long ruleValueToday, long ruleValueCap, boolean rulesPausedAll, long asOf) { }

    /** @param payload 0 for an unmodified stack; otherwise the id of the stored stack it stands for */
    public record VaultRow(int index, Goods goods, int payload) { }

    /**
     * @param inGame      the player is in game now
     * @param liveActions {@code vault_deposit} and {@code vault_withdraw} can run now: remote actions
     *                    are on, the economy is not frozen and the player is in game, alive and not a
     *                    spectator. Only ever a yes or no: never where the player is.
     */
    public record VaultView(List<VaultRow> stacks, int used, int cap, boolean inGame, boolean liveActions, long asOf) {
        /** A vault seen while the player is away. */
        public VaultView(List<VaultRow> stacks, int used, int cap, long asOf) { this(stacks, used, cap, false, false, asOf); }
    }

    public record Stock(String id, String name, boolean index, String type, String sector, long priceCents,
            long askCents, long bidCents, int dayChangeBps, String status, String risk, int dividendBps,
            List<Long> recentCents, List<Long> dailyCents) {
        public boolean tradeable() { return status.isEmpty(); }
    }

    public record BondOffer(String term, String name, int days, int rateBps) { }

    /**
     * @param feeBps      the exchange fee on every share purchase and sale, in hundredths of a percent
     *                    ({@code exchangeFeePercent}; rounded up, at least 1 credit; bonds carry none)
     * @param holdMinutes economy minutes before shares just bought can be sold ({@code shareHoldMinutes}; 0: none)
     */
    public record Stocks(boolean exchangeOpen, List<Stock> stocks, List<BondOffer> bonds, int feeBps, long holdMinutes,
            long asOf) { }

    public record Holding(String stockId, String name, long units, long cost, long value, long difference,
            long sellableUnits, long sellableIn, String status) { }

    public record BondRow(String bondId, String term, String name, int days, long principal, long interest,
            long maturesIn, boolean matured) { }

    /**
     * @param dividendPercent what the last dividend day paid, as a percent of what was due: below 100
     *                        when the Reserve was short
     */
    public record Book(List<Holding> holdings, List<BondRow> bonds, long cost, long value, long difference,
            long bondPrincipal, long bondInterest, long invested, long portfolioLimit, long positionLimit,
            int bondLimit, long lastDividend, int dividendPercent, boolean dividendScaled, long playBalance,
            boolean canReset) { }

    public record Portfolio(Book real, Book practice, long asOf) { }

    public record OfferRow(String offerId, List<Goods> goods, long credits, Goods ask, long askCredits,
            long expiresIn) { }

    public record Offers(List<OfferRow> inbox, List<OfferRow> outbox, boolean muted, int maxOffers, long asOf) { }

    /** @param targetName a readable target: an item, a creature, or "a player" */
    public record JobRow(String jobId, String type, String target, String targetName, long qty, long payout,
            long expiresIn, String state, boolean mine, boolean claimedByMe, boolean house) { }

    public record Jobs(String view, List<JobRow> jobs, int mineOpen, int maxJobs, long asOf) { }

    public record AuctionRow(String auctionId, Goods goods, long highestBid, long nextBid, long endsIn, boolean mine,
            boolean leading, boolean house, boolean interplanetary) { }

    public record Auctions(String view, List<AuctionRow> auctions, long asOf) { }

    /** One open order of an item's book, as the book lists it: its price and the units still open. */
    public record BookOrder(long unitPrice, long qty) { }

    /** One price level of an item's book: the price, the units at it and how many orders make them up. */
    public record Level(long unitPrice, long qty, int orders) { }

    /**
     * One item's order book, best price first on each side. Counts only: no order id, no owner, no
     * account, so nobody learns whose order a level holds.
     */
    public record ItemBook(String item, String displayName, List<Level> bids, List<Level> asks, long asOf) { }

    // --- sections ----------------------------------------------------------------------------

    /** The overview: the balance object's keys the app reads, plus one count per area. */
    public static JsonObject summary(Summary s) {
        JsonObject out = new JsonObject();
        out.addProperty("amount", s.balance());
        out.addProperty("balance", s.balance());
        out.addProperty("symbol", LinkPayloads.CURRENCY_SYMBOL);
        out.addProperty("currency", s.currency());
        out.addProperty("waitingCredits", s.waitingCredits());
        JsonObject vault = new JsonObject();
        vault.addProperty("stacks", s.vaultStacks());
        vault.addProperty("cap", s.vaultCap());
        vault.addProperty("full", s.vaultStacks() >= s.vaultCap());
        out.add("vault", vault);
        JsonObject orders = new JsonObject();
        orders.addProperty("sell", s.sellOrders());
        orders.addProperty("buy", s.buyOrders());
        out.add("orders", orders);
        JsonObject rules = new JsonObject();
        rules.addProperty("count", s.rules());
        rules.addProperty("max", s.rulesMax());
        out.add("rules", rules);
        JsonObject offers = new JsonObject();
        offers.addProperty("incoming", s.offersIn());
        offers.addProperty("outgoing", s.offersOut());
        out.add("offers", offers);
        JsonObject portfolio = new JsonObject();
        portfolio.addProperty("value", s.portfolioValue());
        portfolio.addProperty("cost", s.portfolioCost());
        portfolio.addProperty("difference", s.portfolioValue() - s.portfolioCost());
        portfolio.addProperty("bonds", s.bonds());
        out.add("portfolio", portfolio);
        out.add("remote", remote(s.remote()));
        out.addProperty("marketOpen", s.marketOpen());
        out.addProperty("frozen", s.frozen());
        out.addProperty("asOf", s.asOf());
        return out;
    }

    public static JsonObject remote(Remote remote) {
        JsonObject out = new JsonObject();
        out.addProperty("enabled", remote.enabled());
        out.addProperty("spentToday", remote.spentToday());
        out.addProperty("dailyLimit", remote.dailyLimit());
        out.addProperty("leftToday", remote.leftToday());
        return out;
    }

    public static JsonObject orders(Orders o) {
        JsonArray sell = new JsonArray();
        o.sell().stream().limit(MAX_ROWS).forEach(row -> {
            JsonObject line = goods(row.goods());
            line.addProperty("orderId", row.orderId());
            line.addProperty("unitPrice", row.unitPrice());
            line.addProperty("expiresIn", row.expiresIn());
            sell.add(line);
        });
        JsonArray buy = new JsonArray();
        o.buy().stream().limit(MAX_ROWS).forEach(row -> {
            JsonObject line = goods(row.goods());
            line.addProperty("orderId", row.orderId());
            line.addProperty("unitPrice", row.unitPrice());
            line.addProperty("escrow", row.escrow());
            line.addProperty("expiresIn", row.expiresIn());
            buy.add(line);
        });
        JsonArray shops = new JsonArray();
        o.shops().stream().limit(MAX_ROWS).forEach(row -> {
            JsonObject line = goods(row.goods());
            line.addProperty("listingId", row.listingId());
            line.addProperty("unitPrice", row.unitPrice());
            shops.add(line);
        });
        JsonArray rules = new JsonArray();
        o.rules().stream().limit(MAX_ROWS).forEach(rule -> rules.add(ruleJson(rule)));
        JsonObject out = new JsonObject();
        out.add("sell", sell);
        out.add("buy", buy);
        out.add("shops", shops);
        out.add("rules", rules);
        out.addProperty("rulesMax", o.rulesMax());
        out.addProperty("ruleValueToday", o.ruleValueToday());
        out.addProperty("ruleValueCap", o.ruleValueCap());
        out.addProperty("rulesPausedAll", o.rulesPausedAll());
        out.addProperty("asOf", o.asOf());
        return out;
    }

    /** One rule; also the {@code rule} of a {@code rule_set} result. */
    public static JsonObject ruleJson(RuleRow rule) {
        JsonObject line = new JsonObject();
        line.addProperty("ruleId", rule.ruleId());
        line.addProperty("side", rule.side());
        line.addProperty("target", rule.target());
        line.addProperty("tag", rule.tag());
        line.addProperty("displayName", rule.displayName());
        line.addProperty("limitPrice", rule.limitPrice());
        line.addProperty("perDay", rule.perDay());
        line.addProperty("filledToday", rule.filledToday());
        line.addProperty("total", rule.total());
        line.addProperty("done", rule.done());
        line.addProperty("budget", rule.budget());
        line.addProperty("paused", rule.paused());
        line.addProperty("vault", rule.vault());
        line.addProperty("status", rule.status());
        line.addProperty("statusText", rule.statusText());
        return line;
    }

    public static JsonObject vault(VaultView v) {
        JsonArray stacks = new JsonArray();
        v.stacks().stream().limit(MAX_VAULT_ROWS).forEach(row -> {
            JsonObject line = goods(row.goods());
            line.addProperty("index", row.index());
            line.addProperty("payload", row.payload());
            stacks.add(line);
        });
        JsonObject out = new JsonObject();
        out.add("stacks", stacks);
        out.addProperty("used", v.used());
        out.addProperty("cap", v.cap());
        out.addProperty("full", v.used() >= v.cap());
        out.addProperty("inGame", v.inGame());
        out.addProperty("liveActions", v.liveActions());
        out.addProperty("asOf", v.asOf());
        return out;
    }

    public static JsonObject stocks(Stocks s) {
        JsonArray stocks = new JsonArray();
        s.stocks().stream().limit(MAX_STOCKS).forEach(stock -> {
            JsonObject line = new JsonObject();
            line.addProperty("id", stock.id());
            line.addProperty("displayName", stock.name());
            line.addProperty("index", stock.index());
            line.addProperty("type", stock.type());
            line.addProperty("sector", stock.sector());
            line.addProperty("priceCents", stock.priceCents());
            line.addProperty("askCents", stock.askCents());
            line.addProperty("bidCents", stock.bidCents());
            line.addProperty("dayChangeBps", stock.dayChangeBps());
            line.addProperty("tradeable", stock.tradeable());
            line.addProperty("status", stock.status());
            line.addProperty("risk", stock.risk());
            line.addProperty("dividendBps", stock.dividendBps());
            line.add("history", tail(stock.recentCents(), RECENT_POINTS));
            line.add("daily", tail(stock.dailyCents(), DAILY_POINTS));
            stocks.add(line);
        });
        JsonArray bonds = new JsonArray();
        for (BondOffer bond : s.bonds()) {
            JsonObject line = new JsonObject();
            line.addProperty("term", bond.term());
            line.addProperty("displayName", bond.name());
            line.addProperty("days", bond.days());
            line.addProperty("rateBps", bond.rateBps());
            bonds.add(line);
        }
        JsonObject out = new JsonObject();
        out.addProperty("exchangeOpen", s.exchangeOpen());
        out.add("stocks", stocks);
        out.add("bonds", bonds);
        out.addProperty("feeBps", s.feeBps());
        out.addProperty("holdMinutes", s.holdMinutes());
        out.addProperty("asOf", s.asOf());
        return out;
    }

    /** The last {@code points} values, oldest first. */
    static JsonArray tail(List<Long> values, int points) {
        JsonArray out = new JsonArray();
        if (values == null) return out;
        for (int index = Math.max(0, values.size() - points); index < values.size(); index++) out.add(values.get(index));
        return out;
    }

    /** The real portfolio at the top level; the practice one, in play credits, under {@code practice}. */
    public static JsonObject portfolio(Portfolio p) {
        JsonObject out = book(p.real(), false);
        out.add("practice", book(p.practice(), true));
        out.addProperty("asOf", p.asOf());
        return out;
    }

    private static JsonObject book(Book b, boolean practice) {
        JsonArray holdings = new JsonArray();
        b.holdings().stream().limit(MAX_ROWS).forEach(holding -> {
            JsonObject line = new JsonObject();
            line.addProperty("stockId", holding.stockId());
            line.addProperty("displayName", holding.name());
            line.addProperty("units", holding.units());
            line.addProperty("cost", holding.cost());
            line.addProperty("value", holding.value());
            line.addProperty("difference", holding.difference());
            line.addProperty("sellableUnits", holding.sellableUnits());
            line.addProperty("sellableIn", holding.sellableIn());
            line.addProperty("status", holding.status());
            holdings.add(line);
        });
        JsonArray bonds = new JsonArray();
        b.bonds().stream().limit(MAX_ROWS).forEach(bond -> {
            JsonObject line = new JsonObject();
            line.addProperty("bondId", bond.bondId());
            line.addProperty("term", bond.term());
            line.addProperty("displayName", bond.name());
            line.addProperty("days", bond.days());
            line.addProperty("principal", bond.principal());
            line.addProperty("interest", bond.interest());
            line.addProperty("maturesIn", bond.maturesIn());
            line.addProperty("matured", bond.matured());
            bonds.add(line);
        });
        JsonObject out = new JsonObject();
        out.add("holdings", holdings);
        out.add("bonds", bonds);
        out.addProperty("cost", b.cost());
        out.addProperty("value", b.value());
        out.addProperty("difference", b.difference());
        out.addProperty("bondPrincipal", b.bondPrincipal());
        out.addProperty("bondInterest", b.bondInterest());
        out.addProperty("invested", b.invested());
        out.addProperty("portfolioLimit", b.portfolioLimit());
        out.addProperty("positionLimit", b.positionLimit());
        out.addProperty("bondLimit", b.bondLimit());
        if (practice) {
            out.addProperty("playBalance", b.playBalance());
            out.addProperty("canReset", b.canReset());
        } else {
            JsonObject dividends = new JsonObject();
            dividends.addProperty("last", b.lastDividend());
            dividends.addProperty("factorPercent", b.dividendPercent());
            dividends.addProperty("scaled", b.dividendScaled());
            out.add("dividends", dividends);
        }
        return out;
    }

    /** Inbox and outbox. The other side of every offer is "a player", never a name. */
    public static JsonObject offers(Offers o) {
        String someone = LinkText.get(A_PLAYER);
        JsonObject out = new JsonObject();
        out.add("inbox", offerRows(o.inbox(), "from", someone));
        out.add("outbox", offerRows(o.outbox(), "to", someone));
        out.addProperty("muted", o.muted());
        out.addProperty("maxOffers", o.maxOffers());
        out.addProperty("asOf", o.asOf());
        return out;
    }

    /** Lang key of the words that stand for any other player. */
    public static final String A_PLAYER = "link.neroeconomy.a_player";

    private static JsonArray offerRows(List<OfferRow> rows, String side, String someone) {
        JsonArray out = new JsonArray();
        rows.stream().limit(MAX_ROWS).forEach(row -> {
            JsonObject line = new JsonObject();
            line.addProperty("offerId", row.offerId());
            line.addProperty(side, someone);
            JsonArray goods = new JsonArray();
            row.goods().stream().limit(MAX_OFFER_LINES).forEach(given -> goods.add(goods(given)));
            line.add("goods", goods);
            line.addProperty("credits", row.credits());
            if (row.ask() != null) line.add("ask", goods(row.ask()));
            line.addProperty("askCredits", row.askCredits());
            line.addProperty("expiresIn", row.expiresIn());
            out.add(line);
        });
        return out;
    }

    public static JsonObject jobs(Jobs j) {
        JsonArray jobs = new JsonArray();
        j.jobs().stream().limit(MAX_ROWS).forEach(job -> {
            JsonObject line = new JsonObject();
            line.addProperty("jobId", job.jobId());
            line.addProperty("type", job.type());
            line.addProperty("target", job.target());
            line.addProperty("displayName", job.targetName());
            line.addProperty("qty", job.qty());
            line.addProperty("payout", job.payout());
            line.addProperty("expiresIn", job.expiresIn());
            line.addProperty("state", job.state());
            line.addProperty("mine", job.mine());
            line.addProperty("claimedByMe", job.claimedByMe());
            line.addProperty("house", job.house());
            jobs.add(line);
        });
        JsonObject out = new JsonObject();
        out.addProperty("view", j.view());
        out.add("jobs", jobs);
        out.addProperty("mineOpen", j.mineOpen());
        out.addProperty("maxJobs", j.maxJobs());
        out.addProperty("asOf", j.asOf());
        return out;
    }

    public static JsonObject auctions(Auctions a) {
        JsonArray auctions = new JsonArray();
        a.auctions().stream().limit(MAX_ROWS).forEach(auction -> {
            JsonObject line = goods(auction.goods());
            line.addProperty("auctionId", auction.auctionId());
            line.addProperty("highestBid", auction.highestBid());
            line.addProperty("nextBid", auction.nextBid());
            line.addProperty("endsIn", auction.endsIn());
            line.addProperty("mine", auction.mine());
            line.addProperty("leading", auction.leading());
            line.addProperty("house", auction.house());
            line.addProperty("interplanetary", auction.interplanetary());
            auctions.add(line);
        });
        JsonObject out = new JsonObject();
        out.addProperty("view", a.view());
        out.add("auctions", auctions);
        out.addProperty("asOf", a.asOf());
        return out;
    }

    /** Price levels sent per side of an item's book. */
    public static final int BOOK_DEPTH = 10;

    /**
     * Orders listed best first (cheapest ask, highest bid) folded into price levels: the orders at
     * one price become one level with their units added up. Cut to {@link #BOOK_DEPTH} levels.
     */
    public static List<Level> levels(List<BookOrder> bestFirst) {
        List<Level> out = new java.util.ArrayList<>();
        if (bestFirst == null) return out;
        long price = -1L;
        long qty = 0L;
        int orders = 0;
        for (BookOrder order : bestFirst) {
            if (order == null || order.unitPrice() < 1L || order.qty() < 1L) continue;
            if (order.unitPrice() != price) {
                if (orders > 0) {
                    out.add(new Level(price, qty, orders));
                    if (out.size() >= BOOK_DEPTH) return out;
                }
                price = order.unitPrice();
                qty = 0L;
                orders = 0;
            }
            qty = LinkActions.plus(qty, order.qty());
            orders++;
        }
        if (orders > 0 && out.size() < BOOK_DEPTH) out.add(new Level(price, qty, orders));
        return out;
    }

    /**
     * The {@code market} section's {@code view=book}: one item's bids and asks as price levels, the
     * best of each ({@code 0} when that side is empty) and the depth sent.
     */
    public static JsonObject book(ItemBook b) {
        JsonObject out = new JsonObject();
        out.addProperty("view", "book");
        out.addProperty("item", b.item());
        out.addProperty("displayName", b.item().isEmpty() ? "" : LinkPayloads.displayName(b.displayName(), b.item()));
        out.add("bids", levelRows(b.bids()));
        out.add("asks", levelRows(b.asks()));
        out.addProperty("bestBid", b.bids().isEmpty() ? 0L : b.bids().get(0).unitPrice());
        out.addProperty("bestAsk", b.asks().isEmpty() ? 0L : b.asks().get(0).unitPrice());
        out.addProperty("depth", BOOK_DEPTH);
        out.addProperty("asOf", b.asOf());
        return out;
    }

    private static JsonArray levelRows(List<Level> levels) {
        JsonArray out = new JsonArray();
        levels.stream().limit(BOOK_DEPTH).forEach(level -> {
            JsonObject line = new JsonObject();
            line.addProperty("unitPrice", level.unitPrice());
            line.addProperty("qty", level.qty());
            line.addProperty("orders", level.orders());
            out.add(line);
        });
        return out;
    }

    /** A line of goods: item id, readable base name, quantity and whether it is a modified stack. */
    static JsonObject goods(Goods goods) {
        JsonObject out = new JsonObject();
        out.addProperty("item", goods.item());
        String name = LinkPayloads.displayName(goods.displayName(), goods.item());
        out.addProperty("displayName", goods.modified() ? name + LinkText.get(MODIFIED_SUFFIX) : name);
        out.addProperty("qty", goods.qty());
        out.addProperty("modified", goods.modified());
        return out;
    }

    /** Lang key of what a modified stack's name gets after it, as the market rows do. */
    public static final String MODIFIED_SUFFIX = "link.neroeconomy.modified_suffix";

    /**
     * The body of an {@code alerts} event: the alert's stable id, its kind and its fixed sentence.
     * Nothing else: the alert itself never carries a name, an amount, an item or a place.
     */
    public static JsonObject alert(String kindId, long asOf) {
        EconomyAlerts.Kind kind = EconomyAlerts.Kind.byId(kindId);
        JsonObject out = new JsonObject();
        out.addProperty("alertId", kind == null ? "" : EconomyAlerts.alertId(kind.id()));
        out.addProperty("kind", kind == null ? "" : kind.id());
        out.addProperty("text", kind == null ? "" : kind.text());
        out.addProperty("asOf", asOf);
        return out;
    }

    // --- action details ----------------------------------------------------------------------
    // What each schema-2 action adds to its result, beside the balance object, action and message.

    public static JsonObject orderPlaced(String orderId, String side, String item, long qty, long unitPrice,
            long filledNow, long resting, long listingFee, long escrowed) {
        JsonObject out = new JsonObject();
        out.addProperty("orderId", orderId);
        out.addProperty("side", side);
        out.addProperty("item", item);
        out.addProperty("qty", qty);
        out.addProperty("unitPrice", unitPrice);
        out.addProperty("filledNow", filledNow);
        out.addProperty("resting", resting);
        out.addProperty("listingFee", listingFee);
        out.addProperty("escrowed", escrowed);
        // Goods an app order buys, and goods a cancelled one held, always go to the vault.
        out.addProperty("delivery", "vault");
        return out;
    }

    /** {@code order_cancel}, {@code offer_decline}: only the id of the row acted on. */
    public static JsonObject idOnly(String key, String id) {
        JsonObject out = new JsonObject();
        out.addProperty(key, id);
        return out;
    }

    public static JsonObject ruleSet(RuleRow rule, long escrowed) {
        JsonObject out = new JsonObject();
        out.add("rule", ruleJson(rule));
        out.addProperty("escrowed", escrowed);
        return out;
    }

    public static JsonObject ruleDeleted(String ruleId, long refunded, boolean refundedToClaim) {
        JsonObject out = new JsonObject();
        out.addProperty("ruleId", ruleId);
        out.addProperty("refunded", refunded);
        out.addProperty("refundedToClaim", refundedToClaim);
        return out;
    }

    public static JsonObject rulesPaused(boolean paused, int rules) {
        JsonObject out = new JsonObject();
        out.addProperty("paused", paused);
        out.addProperty("rules", rules);
        return out;
    }

    /**
     * {@code stock_buy} and {@code stock_sell}. A purchase: {@code total} is the units' cost,
     * {@code fee} comes on top and {@code net} left the balance. A sale: {@code total} is what the
     * units fetched, {@code gain} what of the gain was paid, {@code gainForfeited} what was given up,
     * {@code loss} the shortfall below cost, and {@code net} what reached the balance.
     */
    public static JsonObject stockTrade(String stockId, String side, long units, long unitPriceCents, long total,
            long fee, long net, long costBasis, long gain, long gainForfeited, long loss, boolean practice) {
        JsonObject out = new JsonObject();
        out.addProperty("stockId", stockId);
        out.addProperty("side", side);
        out.addProperty("units", units);
        out.addProperty("unitPriceCents", unitPriceCents);
        out.addProperty("total", total);
        out.addProperty("fee", fee);
        out.addProperty("net", net);
        out.addProperty("costBasis", costBasis);
        out.addProperty("gain", gain);
        out.addProperty("gainForfeited", gainForfeited);
        out.addProperty("loss", loss);
        out.addProperty("practice", practice);
        return out;
    }

    /** The parameter a {@code stock_sell} sends to accept a reduced gain. */
    public static final String ACCEPT_GAIN_PARAM = "acceptedGain";

    /**
     * A {@code stock_sell} that sold nothing because the Reserve can pay only part of the gain,
     * sent as a result only when the app asked with {@code structuredRefusal}. {@code refusal} is
     * {@code gain_reduced} (no acceptance sent) or {@code gain_changed} (the acceptance sent no
     * longer matches); to sell anyway the app sends {@code acceptParam} set to {@code acceptValue}
     * with a new request id.
     */
    public static JsonObject gainRefusal(String stockId, long units, String refusal, long gain, long payable) {
        JsonObject out = new JsonObject();
        out.addProperty("sold", false);
        out.addProperty("refusal", refusal);
        out.addProperty("stockId", stockId);
        out.addProperty("units", units);
        out.addProperty("gain", gain);
        out.addProperty("payable", payable);
        out.addProperty("gainForfeited", Math.max(0L, gain - payable));
        out.addProperty("acceptParam", ACCEPT_GAIN_PARAM);
        out.addProperty("acceptValue", payable);
        return out;
    }

    public static JsonObject bondBought(String bondId, String term, long principal, long interest, long paid,
            long maturesIn, boolean practice) {
        JsonObject out = new JsonObject();
        out.addProperty("bondId", bondId);
        out.addProperty("term", term);
        out.addProperty("principal", principal);
        out.addProperty("interest", interest);
        out.addProperty("paid", paid);
        out.addProperty("maturesIn", maturesIn);
        out.addProperty("practice", practice);
        return out;
    }

    /**
     * {@code pay}: what was sent, as {@code paid} ({@code amount} is the balance the app reads).
     * The recipient the player typed is never echoed.
     */
    public static JsonObject paid(long amount) {
        JsonObject out = new JsonObject();
        out.addProperty("paid", amount);
        return out;
    }

    public static JsonObject offerSent(String offerId, long credits, long items, long expiresIn) {
        JsonObject out = new JsonObject();
        out.addProperty("offerId", offerId);
        out.addProperty("credits", credits);
        out.addProperty("items", items);
        out.addProperty("expiresIn", expiresIn);
        return out;
    }

    public static JsonObject offerAccepted(String offerId, long received, long paid, long tax, long items) {
        JsonObject out = new JsonObject();
        out.addProperty("offerId", offerId);
        out.addProperty("received", received);
        out.addProperty("paid", paid);
        out.addProperty("tax", tax);
        out.addProperty("items", items);
        return out;
    }

    /**
     * {@code offer_cancel}: the offer taken back, the credits and items it returned, and whether the
     * credits went to the credit claim rather than the balance.
     */
    public static JsonObject offerCancelled(String offerId, long credits, long items, boolean refundedToClaim) {
        JsonObject out = new JsonObject();
        out.addProperty("offerId", offerId);
        out.addProperty("refunded", credits);
        out.addProperty("items", items);
        out.addProperty("refundedToClaim", refundedToClaim);
        return out;
    }

    public static JsonObject collected(long credits) {
        JsonObject out = new JsonObject();
        out.addProperty("collected", credits);
        return out;
    }

    public static JsonObject deposited(Goods goods) {
        JsonObject out = new JsonObject();
        out.add("goods", goods(goods));
        return out;
    }

    public static JsonObject withdrew(long moved) {
        JsonObject out = new JsonObject();
        out.addProperty("moved", moved);
        return out;
    }

    // --- action results ----------------------------------------------------------------------

    /**
     * A successful schema-2 action: the balance object as it stands after the action, plus
     * {@code action}, a plain {@code message}, the action's own {@code detail} keys and, for an
     * action that can spend credits, the {@code remote} limit as it stands now.
     *
     * @param remote null for an action that spends nothing
     */
    public static JsonObject actionResult(JsonObject balance, String action, String message, JsonObject detail,
            Remote remote) {
        JsonObject out = balance.deepCopy();
        out.addProperty("action", action);
        out.addProperty("message", message);
        // A detail never replaces a key of the balance object: the app reads those as the balance.
        if (detail != null) for (var entry : detail.entrySet())
            if (!balance.has(entry.getKey())) out.add(entry.getKey(), entry.getValue().deepCopy());
        if (remote != null) out.add("remote", remote(remote));
        return out;
    }
}
