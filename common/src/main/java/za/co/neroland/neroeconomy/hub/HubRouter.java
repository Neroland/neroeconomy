package za.co.neroland.neroeconomy.hub;

import java.util.Map;
import java.util.UUID;

import za.co.neroland.neroeconomy.book.BookSection;

/**
 * Checks one hub intent and hands it to the one server call that carries it out. Everything a
 * client sends is doubted here before anything is touched: the request id, that the action belongs
 * to the tab the player is on, that a row it names was on the page the player was last sent and is
 * the right kind of row, that every number is in range, the frozen economy and, where goods would
 * leave an inventory, the creative-mode guard. The packet budget, the open-menu check and the
 * stale container id are dealt with before this, by the network layer. Pure Java: the server calls
 * behind {@link HubServices} are swapped for a recorder in the tests.
 */
public final class HubRouter {
    /** Longest recipient a payment names: a UUID's 36 characters, or a player name. */
    public static final int MAX_RECIPIENT = 64;
    /** Longest search. */
    public static final int MAX_QUERY = 64;
    /** Longest text an intent carries. */
    public static final int MAX_TEXT = 256;
    /** Highest page number anyone could want. */
    public static final int MAX_PAGE = 100_000;
    /** The most credits one hub intent may name: far past any balance, short of any overflow. */
    public static final long MAX_CREDITS = 1_000_000_000_000L;
    /** The most units of a holding one sale may name. */
    public static final long MAX_UNITS = 1_000_000_000_000L;
    /** The highest price a Player Shop may be set to from the hub. */
    public static final long MAX_SHOP_PRICE = 1_000_000_000L;
    /** The most simulation steps one forced run from the Admin tab may ask for, as the command allows. */
    public static final int MAX_ADMIN_STEPS = 100;

    /** What happened to an intent. Only {@link #OK} reached a server call. */
    public enum Result {
        OK, NO_REQUEST, UNKNOWN_ACTION, WRONG_TAB, TAB_HIDDEN, FROZEN, GOODS_SOURCE, NO_ROW, BAD_NUMBER, BAD_TEXT,
        STALE_REQUEST,
        /** An Admin tab action from a player who is not a level-3 operator. */
        NOT_ADMIN,
        /** An admin action that changes the economy while {@code adminMutationsEnabled} is false. */
        MUTATIONS_OFF
    }

    /**
     * One intent, as decoded.
     *
     * @param target a row id of the page the player was sent; null for none
     */
    public record Intent(UUID requestId, HubTab tab, HubAction action, UUID target, long a, long b, String text) {
        public Intent {
            text = text == null ? "" : text;
        }
    }

    /**
     * What the server knows about the hub the intent arrived at.
     *
     * @param current       the tab the player is on
     * @param rows          what each row of the page last sent stands for
     * @param goodsAllowed  whether goods may leave this player's inventory (see {@code GoodsSource})
     * @param admin         whether the player is a level-3 operator
     * @param adminMutations whether {@code adminMutationsEnabled} is on
     */
    public record Context(HubTab current, Map<UUID, HubPages.RowRef> rows, boolean frozen, boolean goodsAllowed,
            boolean admin, boolean adminMutations) {
        public Context {
            rows = rows == null ? Map.of() : rows;
        }

        /** A context in which no admin action may change the economy. */
        public Context(HubTab current, Map<UUID, HubPages.RowRef> rows, boolean frozen, boolean goodsAllowed,
                boolean admin) {
            this(current, rows, frozen, goodsAllowed, admin, false);
        }
    }

    /** The server calls an intent can end in. Each one does its own economy checks again. */
    public interface HubServices {
        /** Records a request id; false when it was seen before. */
        boolean remember(UUID requestId);
        void openTab(HubTab tab);
        void page(int page);
        void collectCredits();
        void pay(UUID requestId, String recipient, long amount);
        void setLogging(boolean optOut);
        void openVault();
        void collectVault();
        void search(String query);
        void marketMode(HubAction.MarketMode mode);
        void cycleHouse();
        void quoteBuy(HubPages.RowRef row, long quantity);
        void buy(UUID requestId, HubPages.RowRef row, long quantity, long ceiling);
        /** @param row a vault line, or null for the main hand */
        void quoteSell(HubPages.RowRef row, long quantity, long unit);
        /** @param row a vault line, or null for the main hand */
        void sell(UUID requestId, HubPages.RowRef row, long quantity, long unit);
        void quoteBid(String item, long quantity, long unit);
        void bid(UUID requestId, String item, long quantity, long unit);
        void cancelOrder(HubPages.RowRef row);
        void pauseRule(HubPages.RowRef row, boolean paused);
        void deleteRule(HubPages.RowRef row);
        void pauseAll(boolean paused);
        void addRule(UUID requestId, RuleForm form, long limitPrice, long total);

        // --- F1b: the Wallet's "My data", Invest, Trade, Jobs, Auctions and Shop -------------
        void myData(boolean shown);
        void investView(HubAction.InvestView view);
        void investPractice(boolean practice);
        void investPick(HubPages.RowRef row);
        void investQuote(long credits);
        void investBuy(UUID requestId, long credits);
        /** @param units 0 for all of the holding */
        void investSellQuote(HubPages.RowRef row, long units);
        /** @param units 0 for all of it; @param acceptedGain the reduced gain accepted, or -1 for the full gain only */
        void investSell(UUID requestId, HubPages.RowRef row, long units, long acceptedGain);
        void investRedeem(UUID requestId, HubPages.RowRef row);
        void investReset(UUID requestId);
        void tradeView(HubAction.TradeView view);
        void offerAccept(UUID requestId, HubPages.RowRef row);
        void offerDecline(HubPages.RowRef row);
        void offerCancel(HubPages.RowRef row);
        /** @param row the vault line given, or null */
        void offerSend(UUID requestId, HubPages.RowRef row, OfferForm form);
        void offerMute(boolean mute);
        void tradeRequest(String name);
        void jobsView(HubAction.JobsView view);
        void jobClaim(HubPages.RowRef row);
        void jobRelease(HubPages.RowRef row);
        void jobFulfil(HubPages.RowRef row);
        void jobCancel(HubPages.RowRef row);
        void jobPost(JobForm form, long payout);
        void auctionsView(HubAction.AuctionsView view);
        void auctionBid(HubPages.RowRef row, long amount);
        void auctionCancel(HubPages.RowRef row);
        void auctionCreate(int quantity, long minimumBid, int minutes, boolean interplanetary);
        void shopPrice(HubPages.RowRef row, long price);
        void shopOpen(HubPages.RowRef row);

        // --- F3: the Admin tab. Each one checks for a level-3 operator again. ------------------
        void adminView(HubAction.AdminView view);
        void adminSimStep(int steps);
        void adminMarketFreeze(boolean closed);
        void adminReloadCheck();
        void adminAcceptFresh();
        /** @param typed the name or UUID the operator typed */
        void adminTarget(String typed);
        void adminExport(HubPages.RowRef row);
        void adminErase(HubPages.RowRef row);
        void adminGrant(HubPages.RowRef row, long amount);
        void adminTake(HubPages.RowRef row, long amount);
        void adminSet(HubPages.RowRef row, long amount);
    }

    /** An auction's length and orbit switch, as {@code AUCTION_CREATE} carries them: {@code minutes:0|1}. */
    public record AuctionForm(int minutes, boolean interplanetary) {
        public static AuctionForm parse(String text) {
            if (text == null) return null;
            String[] parts = text.trim().split(":", -1);
            if (parts.length != 2) return null;
            try {
                long minutes = Long.parseLong(parts[0].trim());
                String orbit = parts[1].trim();
                if (minutes < 1L || minutes > AuctionsPages.LONGEST_MINUTES) return null;
                if (!orbit.equals("0") && !orbit.equals("1")) return null;
                return new AuctionForm((int) minutes, orbit.equals("1"));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
    }

    private HubRouter() { }

    public static Result route(Intent intent, Context context, HubServices services) {
        if (intent == null || context == null || services == null) return Result.UNKNOWN_ACTION;
        if (intent.requestId() == null) return Result.NO_REQUEST;
        HubAction action = intent.action();
        if (action == null) return Result.UNKNOWN_ACTION;
        // A client that shows the Admin tab anyway gains nothing: every admin action is refused here.
        if (action.adminOnly() && !context.admin()) return Result.NOT_ADMIN;
        if (action.adminMutation() && !context.adminMutations()) return Result.MUTATIONS_OFF;
        if (action.tab() != null && (action.tab() != intent.tab() || action.tab() != context.current()))
            return Result.WRONG_TAB;
        if (intent.text().length() > MAX_TEXT) return Result.BAD_TEXT;

        HubPages.RowRef row = null;
        if (action.target() != HubAction.Target.NONE) {
            if (intent.target() != null) {
                row = context.rows().get(intent.target());
                if (row == null) return Result.NO_ROW;
            } else if (action.target() == HubAction.Target.ROW) {
                return Result.NO_ROW;
            }
            if (row != null && !rowFits(action, row.kind())) return Result.NO_ROW;
        }

        Result numbers = checkNumbers(intent, action);
        if (numbers != Result.OK) return numbers;
        RuleForm form = null;
        if (action == HubAction.RULE_ADD) {
            form = RuleForm.parse(intent.text());
            if (form == null || form.perDay() > BookSection.MAX_ORDER_QUANTITY) return Result.BAD_TEXT;
        }
        OfferForm offer = null;
        if (action == HubAction.OFFER_SEND) {
            offer = OfferForm.parse(intent.text());
            if (offer == null) return Result.BAD_TEXT;
            // The form says where the goods come from; a vault line needs its row, and only then.
            if ((offer.source() == OfferForm.Source.VAULT) != (row != null)) return Result.NO_ROW;
        }
        JobForm job = null;
        if (action == HubAction.JOB_POST) {
            job = JobForm.parse(intent.text());
            if (job == null) return Result.BAD_TEXT;
        }
        AuctionForm auction = null;
        if (action == HubAction.AUCTION_CREATE) {
            auction = AuctionForm.parse(intent.text());
            if (auction == null) return Result.BAD_TEXT;
        }
        HubTab tab = null;
        if (action == HubAction.OPEN_TAB) {
            tab = HubTab.byOrdinal((int) Math.max(-1L, Math.min(Integer.MAX_VALUE, intent.a())));
            if (tab == null) return Result.BAD_NUMBER;
            if (!tab.visible(context.admin())) return Result.TAB_HIDDEN;
        }

        if (action.movesValue() && context.frozen()) return Result.FROZEN;
        if (action.takesGoods() && row == null && !context.goodsAllowed()) return Result.GOODS_SOURCE;
        // Goods leave a live inventory: an offer given from the hand, and a job handed in.
        boolean fromInventory = (offer != null && offer.source() == OfferForm.Source.HAND)
                || action == HubAction.JOB_FULFIL;
        if (fromInventory && !context.goodsAllowed()) return Result.GOODS_SOURCE;
        if (action.request() == HubAction.Request.HUB && !services.remember(intent.requestId()))
            return Result.STALE_REQUEST;

        UUID request = intent.requestId();
        long a = intent.a();
        long b = intent.b();
        String text = intent.text().trim();
        switch (action) {
            case OPEN_TAB -> services.openTab(tab);
            case PAGE -> services.page((int) a);
            case WALLET_COLLECT -> services.collectCredits();
            case WALLET_PAY -> services.pay(request, text, a);
            case WALLET_LOGGING -> services.setLogging(a == 1L);
            case VAULT_OPEN -> services.openVault();
            case VAULT_COLLECT -> services.collectVault();
            case MARKET_SEARCH -> services.search(text);
            case MARKET_MODE -> services.marketMode(HubAction.MarketMode.byOrdinal(a));
            case MARKET_HOUSE -> services.cycleHouse();
            case MARKET_QUOTE -> services.quoteBuy(row, a);
            case MARKET_BUY -> services.buy(request, row, a, b);
            case MARKET_SELL_QUOTE -> services.quoteSell(row, a, b);
            case MARKET_SELL -> services.sell(request, row, a, b);
            case MARKET_BID_QUOTE -> services.quoteBid(text, a, b);
            case MARKET_BID -> services.bid(request, text, a, b);
            case ORDER_CANCEL -> services.cancelOrder(row);
            case RULE_PAUSE -> services.pauseRule(row, a == 1L);
            case RULE_DELETE -> services.deleteRule(row);
            case RULE_PAUSE_ALL -> services.pauseAll(a == 1L);
            case RULE_ADD -> services.addRule(request, form, a, b);
            case WALLET_MY_DATA -> services.myData(a == 1L);
            case INVEST_VIEW -> services.investView(HubAction.InvestView.byOrdinal(a));
            case INVEST_PRACTICE -> services.investPractice(a == 1L);
            case INVEST_PICK -> services.investPick(row);
            case INVEST_QUOTE -> services.investQuote(a);
            case INVEST_BUY -> services.investBuy(request, a);
            case INVEST_SELL_QUOTE -> services.investSellQuote(row, a);
            case INVEST_SELL -> services.investSell(request, row, a, b);
            case INVEST_REDEEM -> services.investRedeem(request, row);
            case INVEST_RESET -> services.investReset(request);
            case TRADE_VIEW -> services.tradeView(HubAction.TradeView.byOrdinal(a));
            case OFFER_ACCEPT -> services.offerAccept(request, row);
            case OFFER_DECLINE -> services.offerDecline(row);
            case OFFER_CANCEL -> services.offerCancel(row);
            case OFFER_SEND -> services.offerSend(request, row, offer);
            case OFFER_MUTE -> services.offerMute(a == 1L);
            case TRADE_REQUEST -> services.tradeRequest(text);
            case JOBS_VIEW -> services.jobsView(HubAction.JobsView.byOrdinal(a));
            case JOB_CLAIM -> services.jobClaim(row);
            case JOB_RELEASE -> services.jobRelease(row);
            case JOB_FULFIL -> services.jobFulfil(row);
            case JOB_CANCEL -> services.jobCancel(row);
            case JOB_POST -> services.jobPost(job, a);
            case AUCTION_VIEW -> services.auctionsView(HubAction.AuctionsView.byOrdinal(a));
            case AUCTION_BID -> services.auctionBid(row, a);
            case AUCTION_CANCEL -> services.auctionCancel(row);
            case AUCTION_CREATE -> {
                if (auction == null) return Result.BAD_TEXT;
                services.auctionCreate((int) a, b, auction.minutes(), auction.interplanetary());
            }
            case SHOP_PRICE -> services.shopPrice(row, a);
            case SHOP_OPEN -> services.shopOpen(row);
            case ADMIN_VIEW -> services.adminView(HubAction.AdminView.byOrdinal(a));
            case ADMIN_SIM_STEP -> services.adminSimStep((int) a);
            case ADMIN_MARKET_FREEZE -> services.adminMarketFreeze(a == 1L);
            case ADMIN_RELOAD_CHECK -> services.adminReloadCheck();
            case ADMIN_ACCEPT_FRESH -> services.adminAcceptFresh();
            case ADMIN_TARGET -> services.adminTarget(text);
            case ADMIN_EXPORT -> services.adminExport(row);
            case ADMIN_ERASE -> services.adminErase(row);
            case ADMIN_GRANT -> services.adminGrant(row, a);
            case ADMIN_TAKE -> services.adminTake(row, a);
            case ADMIN_SET -> services.adminSet(row, a);
            default -> { return Result.UNKNOWN_ACTION; }
        }
        return Result.OK;
    }

    /** Whether a row of this kind is one the action can act on. */
    private static boolean rowFits(HubAction action, HubPages.RowRef.Kind kind) {
        return switch (action) {
            case MARKET_QUOTE, MARKET_BUY -> kind == HubPages.RowRef.Kind.LISTING;
            case MARKET_SELL_QUOTE, MARKET_SELL -> kind == HubPages.RowRef.Kind.VAULT_LINE;
            case ORDER_CANCEL -> kind == HubPages.RowRef.Kind.SELL_ORDER || kind == HubPages.RowRef.Kind.BUY_ORDER;
            case RULE_PAUSE, RULE_DELETE -> kind == HubPages.RowRef.Kind.RULE;
            case INVEST_PICK -> kind == HubPages.RowRef.Kind.STOCK || kind == HubPages.RowRef.Kind.BOND_TERM;
            case INVEST_SELL_QUOTE, INVEST_SELL -> kind == HubPages.RowRef.Kind.HOLDING;
            case INVEST_REDEEM -> kind == HubPages.RowRef.Kind.BOND;
            case OFFER_ACCEPT, OFFER_DECLINE -> kind == HubPages.RowRef.Kind.OFFER_IN;
            case OFFER_CANCEL -> kind == HubPages.RowRef.Kind.OFFER_OUT;
            case OFFER_SEND -> kind == HubPages.RowRef.Kind.VAULT_LINE;
            case JOB_CLAIM, JOB_RELEASE, JOB_FULFIL, JOB_CANCEL -> kind == HubPages.RowRef.Kind.JOB;
            case AUCTION_BID, AUCTION_CANCEL -> kind == HubPages.RowRef.Kind.AUCTION;
            case SHOP_PRICE, SHOP_OPEN -> kind == HubPages.RowRef.Kind.PLAYER_SHOP;
            case ADMIN_EXPORT, ADMIN_ERASE, ADMIN_GRANT, ADMIN_TAKE, ADMIN_SET ->
                    kind == HubPages.RowRef.Kind.ADMIN_TARGET;
            default -> false;
        };
    }

    private static boolean credits(long value) { return value >= 1L && value <= MAX_CREDITS; }

    private static boolean between(long value, long low, long high) { return value >= low && value <= high; }

    private static boolean quantity(long value) { return value >= 1L && value <= BookSection.MAX_ORDER_QUANTITY; }

    private static boolean flag(long value) { return value == 0L || value == 1L; }

    private static Result checkNumbers(Intent intent, HubAction action) {
        long a = intent.a();
        long b = intent.b();
        String text = intent.text().trim();
        boolean ok = switch (action) {
            case OPEN_TAB -> true;
            case PAGE -> a >= 0L && a <= MAX_PAGE;
            case WALLET_PAY -> a >= 1L;
            case WALLET_LOGGING, RULE_PAUSE, RULE_PAUSE_ALL, MARKET_MODE -> flag(a);
            case MARKET_QUOTE -> quantity(a);
            case MARKET_BUY, MARKET_SELL_QUOTE, MARKET_SELL, MARKET_BID_QUOTE, MARKET_BID -> quantity(a) && b >= 1L;
            case RULE_ADD -> a >= 1L && b >= 1L;
            case WALLET_MY_DATA, INVEST_PRACTICE, OFFER_MUTE -> flag(a);
            case INVEST_VIEW -> between(a, 0L, HubAction.InvestView.values().length - 1L);
            case TRADE_VIEW -> between(a, 0L, HubAction.TradeView.values().length - 1L);
            case JOBS_VIEW -> between(a, 0L, HubAction.JobsView.values().length - 1L);
            case AUCTION_VIEW -> between(a, 0L, HubAction.AuctionsView.values().length - 1L);
            case INVEST_QUOTE, INVEST_BUY, AUCTION_BID, JOB_POST -> credits(a);
            case INVEST_SELL_QUOTE -> between(a, 0L, MAX_UNITS);
            case INVEST_SELL -> between(a, 0L, MAX_UNITS) && between(b, -1L, MAX_CREDITS);
            case AUCTION_CREATE -> between(a, 1L, AuctionsPages.MOST_PER_LOT) && credits(b);
            case SHOP_PRICE -> between(a, 1L, MAX_SHOP_PRICE);
            case ADMIN_VIEW -> between(a, 0L, HubAction.AdminView.values().length - 1L);
            case ADMIN_SIM_STEP -> between(a, 1L, MAX_ADMIN_STEPS);
            case ADMIN_MARKET_FREEZE -> flag(a);
            case ADMIN_GRANT, ADMIN_TAKE -> credits(a);
            case ADMIN_SET -> between(a, 0L, MAX_CREDITS);
            default -> true;
        };
        if (!ok) return Result.BAD_NUMBER;
        boolean textOk = switch (action) {
            case WALLET_PAY -> !text.isEmpty() && text.length() <= MAX_RECIPIENT;
            case MARKET_SEARCH -> text.length() <= MAX_QUERY;
            case MARKET_BID_QUOTE, MARKET_BID -> !text.isEmpty() && text.indexOf(' ') < 0;
            case TRADE_REQUEST, ADMIN_TARGET -> !text.isEmpty() && text.length() <= MAX_RECIPIENT && text.indexOf(' ') < 0;
            default -> true;
        };
        return textOk ? Result.OK : Result.BAD_TEXT;
    }
}
