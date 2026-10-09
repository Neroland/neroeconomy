package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.hub.HubPages.RowRef;
import za.co.neroland.neroeconomy.hub.HubRouter.Result;

/** Every hub intent reaches exactly the right server call, and every bad one is refused before any. */
class HubRouterTest {
    private static final UUID LISTING = new UUID(1L, 1L);
    private static final UUID VAULT_LINE = new UUID(1L, 2L);
    private static final UUID SELL_ORDER = new UUID(1L, 3L);
    private static final UUID BUY_ORDER = new UUID(1L, 4L);
    private static final UUID RULE = new UUID(1L, 5L);
    private static final UUID SHOP = new UUID(1L, 6L);
    private static final Map<UUID, RowRef> ROWS = Map.of(
            LISTING, new RowRef(RowRef.Kind.LISTING, LISTING, "minecraft:wheat", 0),
            VAULT_LINE, new RowRef(RowRef.Kind.VAULT_LINE, null, "minecraft:wheat", 0),
            SELL_ORDER, new RowRef(RowRef.Kind.SELL_ORDER, SELL_ORDER, "minecraft:wheat", 0),
            BUY_ORDER, new RowRef(RowRef.Kind.BUY_ORDER, BUY_ORDER, "minecraft:wheat", 0),
            RULE, new RowRef(RowRef.Kind.RULE, RULE, "minecraft:wheat", 0),
            SHOP, new RowRef(RowRef.Kind.SHOP, SHOP, "minecraft:wheat", 0));

    /** Writes down each call; remembers request ids as the market does, once each. */
    static final class Recorder implements HubRouter.HubServices {
        final List<String> calls = new ArrayList<>();
        final Set<UUID> seen = new HashSet<>();

        @Override public boolean remember(UUID requestId) { return seen.add(requestId); }
        @Override public void openTab(HubTab tab) { calls.add("openTab " + tab); }
        @Override public void page(int page) { calls.add("page " + page); }
        @Override public void collectCredits() { calls.add("collectCredits"); }
        @Override public void pay(UUID requestId, String recipient, long amount) { calls.add("pay " + recipient + " " + amount); }
        @Override public void setLogging(boolean optOut) { calls.add("setLogging " + optOut); }
        @Override public void openVault() { calls.add("openVault"); }
        @Override public void collectVault() { calls.add("collectVault"); }
        @Override public void search(String query) { calls.add("search " + query); }
        @Override public void marketMode(HubAction.MarketMode mode) { calls.add("marketMode " + mode); }
        @Override public void cycleHouse() { calls.add("cycleHouse"); }
        @Override public void quoteBuy(RowRef row, long quantity) { calls.add("quoteBuy " + row.kind() + " " + quantity); }
        @Override public void buy(UUID requestId, RowRef row, long quantity, long ceiling) {
            calls.add("buy " + row.kind() + " " + quantity + " " + ceiling);
        }
        @Override public void quoteSell(RowRef row, long quantity, long unit) {
            calls.add("quoteSell " + (row == null ? "hand" : row.kind()) + " " + quantity + " " + unit);
        }
        @Override public void sell(UUID requestId, RowRef row, long quantity, long unit) {
            calls.add("sell " + (row == null ? "hand" : row.kind()) + " " + quantity + " " + unit);
        }
        @Override public void quoteBid(String item, long quantity, long unit) { calls.add("quoteBid " + item + " " + quantity + " " + unit); }
        @Override public void bid(UUID requestId, String item, long quantity, long unit) { calls.add("bid " + item + " " + quantity + " " + unit); }
        @Override public void cancelOrder(RowRef row) { calls.add("cancelOrder " + row.kind()); }
        @Override public void pauseRule(RowRef row, boolean paused) { calls.add("pauseRule " + paused); }
        @Override public void deleteRule(RowRef row) { calls.add("deleteRule"); }
        @Override public void pauseAll(boolean paused) { calls.add("pauseAll " + paused); }
        @Override public void addRule(UUID requestId, RuleForm form, long limitPrice, long total) {
            calls.add("addRule " + form.encode() + " " + limitPrice + " " + total);
        }
        @Override public void myData(boolean shown) { calls.add("myData " + shown); }
        @Override public void investView(HubAction.InvestView view) { calls.add("investView " + view); }
        @Override public void investPractice(boolean practice) { calls.add("investPractice " + practice); }
        @Override public void investPick(RowRef row) { calls.add("investPick " + row.kind() + " " + row.item()); }
        @Override public void investQuote(long credits) { calls.add("investQuote " + credits); }
        @Override public void investBuy(UUID requestId, long credits) { calls.add("investBuy " + credits); }
        @Override public void investSellQuote(RowRef row, long units) { calls.add("investSellQuote " + row.item() + " " + units); }
        @Override public void investSell(UUID requestId, RowRef row, long units, long acceptedGain) {
            calls.add("investSell " + row.item() + " " + units + " " + acceptedGain);
        }
        @Override public void investRedeem(UUID requestId, RowRef row) { calls.add("investRedeem " + row.kind()); }
        @Override public void investReset(UUID requestId) { calls.add("investReset"); }
        @Override public void tradeView(HubAction.TradeView view) { calls.add("tradeView " + view); }
        @Override public void offerAccept(UUID requestId, RowRef row) { calls.add("offerAccept " + row.kind()); }
        @Override public void offerDecline(RowRef row) { calls.add("offerDecline " + row.kind()); }
        @Override public void offerCancel(RowRef row) { calls.add("offerCancel " + row.kind()); }
        @Override public void offerSend(UUID requestId, RowRef row, OfferForm form) {
            calls.add("offerSend " + (row == null ? "none" : row.kind()) + " " + form.encode());
        }
        @Override public void offerMute(boolean mute) { calls.add("offerMute " + mute); }
        @Override public void tradeRequest(String name) { calls.add("tradeRequest " + name); }
        @Override public void jobsView(HubAction.JobsView view) { calls.add("jobsView " + view); }
        @Override public void jobClaim(RowRef row) { calls.add("jobClaim"); }
        @Override public void jobRelease(RowRef row) { calls.add("jobRelease"); }
        @Override public void jobFulfil(RowRef row) { calls.add("jobFulfil"); }
        @Override public void jobCancel(RowRef row) { calls.add("jobCancel"); }
        @Override public void jobPost(JobForm form, long payout) { calls.add("jobPost " + form.encode() + " " + payout); }
        @Override public void auctionsView(HubAction.AuctionsView view) { calls.add("auctionsView " + view); }
        @Override public void auctionBid(RowRef row, long amount) { calls.add("auctionBid " + amount); }
        @Override public void auctionCancel(RowRef row) { calls.add("auctionCancel"); }
        @Override public void auctionCreate(int quantity, long minimumBid, int minutes, boolean interplanetary) {
            calls.add("auctionCreate " + quantity + " " + minimumBid + " " + minutes + " " + interplanetary);
        }
        @Override public void shopPrice(RowRef row, long price) { calls.add("shopPrice " + price); }
        @Override public void shopOpen(RowRef row) { calls.add("shopOpen"); }
        @Override public void adminView(HubAction.AdminView view) { calls.add("adminView " + view); }
        @Override public void adminSimStep(int steps) { calls.add("adminSimStep " + steps); }
        @Override public void adminMarketFreeze(boolean closed) { calls.add("adminMarketFreeze " + closed); }
        @Override public void adminReloadCheck() { calls.add("adminReloadCheck"); }
        @Override public void adminAcceptFresh() { calls.add("adminAcceptFresh"); }
        @Override public void adminTarget(String typed) { calls.add("adminTarget " + typed); }
        @Override public void adminExport(RowRef row) { calls.add("adminExport " + row.kind()); }
        @Override public void adminErase(RowRef row) { calls.add("adminErase " + row.kind()); }
        @Override public void adminGrant(RowRef row, long amount) { calls.add("adminGrant " + amount); }
        @Override public void adminTake(RowRef row, long amount) { calls.add("adminTake " + amount); }
        @Override public void adminSet(RowRef row, long amount) { calls.add("adminSet " + amount); }
    }

    private static HubRouter.Context on(HubTab tab) { return new HubRouter.Context(tab, ROWS, false, true, false); }

    private static HubRouter.Intent intent(HubAction action, UUID target, long a, long b, String text) {
        HubTab tab = action.tab() == null ? HubTab.WALLET : action.tab();
        return new HubRouter.Intent(UUID.randomUUID(), tab, action, target, a, b, text);
    }

    /** Routes on the action's own tab and returns the one call made. */
    private static String call(HubAction action, UUID target, long a, long b, String text) {
        Recorder recorder = new Recorder();
        HubTab tab = action.tab() == null ? HubTab.WALLET : action.tab();
        assertEquals(Result.OK, HubRouter.route(intent(action, target, a, b, text), on(tab), recorder), action.name());
        assertEquals(1, recorder.calls.size(), action.name());
        return recorder.calls.get(0);
    }

    private static Result refusal(HubRouter.Intent intent, HubRouter.Context context) {
        Recorder recorder = new Recorder();
        Result result = HubRouter.route(intent, context, recorder);
        assertTrue(recorder.calls.isEmpty(), "a refused intent still called " + recorder.calls);
        return result;
    }

    @Test void eachActionReachesItsOwnCall() {
        assertEquals("openTab MARKET", call(HubAction.OPEN_TAB, null, HubTab.MARKET.ordinal(), 0, ""));
        assertEquals("page 3", call(HubAction.PAGE, null, 3, 0, ""));
        assertEquals("collectCredits", call(HubAction.WALLET_COLLECT, null, 0, 0, ""));
        assertEquals("pay Someone 40", call(HubAction.WALLET_PAY, null, 40, 0, " Someone "));
        assertEquals("setLogging true", call(HubAction.WALLET_LOGGING, null, 1, 0, ""));
        assertEquals("openVault", call(HubAction.VAULT_OPEN, null, 0, 0, ""));
        assertEquals("collectVault", call(HubAction.VAULT_COLLECT, null, 0, 0, ""));
        assertEquals("search wheat", call(HubAction.MARKET_SEARCH, null, 0, 0, "wheat"));
        assertEquals("marketMode VAULT", call(HubAction.MARKET_MODE, null, 1, 0, ""));
        assertEquals("cycleHouse", call(HubAction.MARKET_HOUSE, null, 0, 0, ""));
        assertEquals("quoteBuy LISTING 4", call(HubAction.MARKET_QUOTE, LISTING, 4, 0, ""));
        assertEquals("buy LISTING 4 9", call(HubAction.MARKET_BUY, LISTING, 4, 9, ""));
        assertEquals("quoteSell hand 2 7", call(HubAction.MARKET_SELL_QUOTE, null, 2, 7, ""));
        assertEquals("sell VAULT_LINE 2 7", call(HubAction.MARKET_SELL, VAULT_LINE, 2, 7, ""));
        assertEquals("quoteBid minecraft:wheat 5 3", call(HubAction.MARKET_BID_QUOTE, null, 5, 3, "minecraft:wheat"));
        assertEquals("bid minecraft:wheat 5 3", call(HubAction.MARKET_BID, null, 5, 3, "minecraft:wheat"));
        assertEquals("cancelOrder SELL_ORDER", call(HubAction.ORDER_CANCEL, SELL_ORDER, 0, 0, ""));
        assertEquals("cancelOrder BUY_ORDER", call(HubAction.ORDER_CANCEL, BUY_ORDER, 0, 0, ""));
        assertEquals("pauseRule true", call(HubAction.RULE_PAUSE, RULE, 1, 0, ""));
        assertEquals("deleteRule", call(HubAction.RULE_DELETE, RULE, 0, 0, ""));
        assertEquals("pauseAll false", call(HubAction.RULE_PAUSE_ALL, null, 0, 0, ""));
        assertEquals("addRule BI:16:minecraft:wheat 5 200",
                call(HubAction.RULE_ADD, null, 5, 200, "BI:16:minecraft:wheat"));
    }

    @Test void everyActionIsCoveredByTheRoutingTest() {
        // A new action must get a line in eachActionReachesItsOwnCall, or in HubRouterF1bTest for the F1b tabs,
        // or in HubRouterAdminTest for the Admin tab.
        assertEquals(61, HubAction.values().length);
    }

    @Test void anIntentWithoutARequestIdOrActionIsRefused() {
        assertEquals(Result.NO_REQUEST, refusal(new HubRouter.Intent(null, HubTab.WALLET, HubAction.WALLET_COLLECT,
                null, 0, 0, ""), on(HubTab.WALLET)));
        assertEquals(Result.UNKNOWN_ACTION, refusal(new HubRouter.Intent(UUID.randomUUID(), HubTab.WALLET, null,
                null, 0, 0, ""), on(HubTab.WALLET)));
        assertEquals(Result.UNKNOWN_ACTION, HubRouter.route(null, on(HubTab.WALLET), new Recorder()));
    }

    @Test void anActionForAnotherTabIsRefused() {
        // Sent from the wallet tab while the server shows the market, or claiming a tab it is not on.
        assertEquals(Result.WRONG_TAB, refusal(intent(HubAction.MARKET_BUY, LISTING, 1, 1, ""), on(HubTab.WALLET)));
        assertEquals(Result.WRONG_TAB, refusal(new HubRouter.Intent(UUID.randomUUID(), HubTab.ORDERS,
                HubAction.MARKET_BUY, LISTING, 1, 1, ""), on(HubTab.MARKET)));
    }

    @Test void theAdminTabIsHiddenFromPlayersButOpensForOperators() {
        HubRouter.Intent admin = intent(HubAction.OPEN_TAB, null, HubTab.ADMIN.ordinal(), 0, "");
        assertEquals(Result.TAB_HIDDEN, refusal(admin, on(HubTab.WALLET)));
        Recorder recorder = new Recorder();
        assertEquals(Result.OK, HubRouter.route(admin, new HubRouter.Context(HubTab.WALLET, ROWS, false, true, true),
                recorder));
        assertEquals(List.of("openTab ADMIN"), recorder.calls);
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.OPEN_TAB, null, 99, 0, ""), on(HubTab.WALLET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.OPEN_TAB, null, -1, 0, ""), on(HubTab.WALLET)));
    }

    @Test void aFrozenEconomyRefusesEverythingThatMovesValueButStillBrowses() {
        HubRouter.Context frozen = new HubRouter.Context(HubTab.MARKET, ROWS, true, true, false);
        assertEquals(Result.FROZEN, refusal(intent(HubAction.MARKET_BUY, LISTING, 1, 1, ""), frozen));
        assertEquals(Result.FROZEN, refusal(intent(HubAction.MARKET_BID, null, 1, 1, "minecraft:wheat"), frozen));
        assertEquals(Result.FROZEN, refusal(intent(HubAction.WALLET_PAY, null, 1, 0, "Someone"),
                new HubRouter.Context(HubTab.WALLET, ROWS, true, true, false)));
        Recorder recorder = new Recorder();
        assertEquals(Result.OK, HubRouter.route(intent(HubAction.MARKET_SEARCH, null, 0, 0, "x"), frozen, recorder));
        for (HubAction action : HubAction.values())
            if (action.movesValue()) assertTrue(action.tab() != null, action + " moves value but has no tab");
    }

    @Test void aCreativeOrSpectatorPlayerCannotSellFromHandButMaySellFromTheVault() {
        HubRouter.Context noGoods = new HubRouter.Context(HubTab.MARKET, ROWS, false, false, false);
        assertEquals(Result.GOODS_SOURCE, refusal(intent(HubAction.MARKET_SELL, null, 1, 1, ""), noGoods));
        Recorder recorder = new Recorder();
        assertEquals(Result.OK, HubRouter.route(intent(HubAction.MARKET_SELL, VAULT_LINE, 1, 1, ""), noGoods, recorder));
    }

    @Test void aRowThatWasNeverSentOrIsOfTheWrongKindIsRefused() {
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.MARKET_BUY, new UUID(9L, 9L), 1, 1, ""), on(HubTab.MARKET)));
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.MARKET_BUY, null, 1, 1, ""), on(HubTab.MARKET)));
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.MARKET_BUY, VAULT_LINE, 1, 1, ""), on(HubTab.MARKET)));
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.MARKET_SELL, LISTING, 1, 1, ""), on(HubTab.MARKET)));
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.ORDER_CANCEL, RULE, 0, 0, ""), on(HubTab.ORDERS)));
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.ORDER_CANCEL, SHOP, 0, 0, ""), on(HubTab.ORDERS)));
        assertEquals(Result.NO_ROW, refusal(intent(HubAction.RULE_DELETE, SELL_ORDER, 0, 0, ""), on(HubTab.ORDERS)));
    }

    @Test void numbersOutsideTheirRangeAreRefused() {
        long tooMany = BookSection.MAX_ORDER_QUANTITY + 1L;
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.MARKET_BUY, LISTING, 0, 1, ""), on(HubTab.MARKET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.MARKET_BUY, LISTING, tooMany, 1, ""), on(HubTab.MARKET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.MARKET_BUY, LISTING, 1, 0, ""), on(HubTab.MARKET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.MARKET_QUOTE, LISTING, -3, 0, ""), on(HubTab.MARKET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.WALLET_PAY, null, 0, 0, "Someone"), on(HubTab.WALLET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.WALLET_LOGGING, null, 2, 0, ""), on(HubTab.WALLET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.PAGE, null, -1, 0, ""), on(HubTab.WALLET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.PAGE, null, HubRouter.MAX_PAGE + 1L, 0, ""),
                on(HubTab.WALLET)));
        assertEquals(Result.BAD_NUMBER, refusal(intent(HubAction.RULE_ADD, null, 0, 5, "BI:1:minecraft:wheat"),
                on(HubTab.ORDERS)));
    }

    @Test void badTextIsRefused() {
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.WALLET_PAY, null, 5, 0, "  "), on(HubTab.WALLET)));
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.WALLET_PAY, null, 5, 0, "n".repeat(65)), on(HubTab.WALLET)));
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.MARKET_SEARCH, null, 0, 0, "q".repeat(65)),
                on(HubTab.MARKET)));
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.MARKET_BID, null, 1, 1, "minecraft:wheat x"),
                on(HubTab.MARKET)));
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.MARKET_SEARCH, null, 0, 0, "q".repeat(300)),
                on(HubTab.MARKET)));
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.RULE_ADD, null, 5, 5, "XI:1:minecraft:wheat"),
                on(HubTab.ORDERS)));
        assertEquals(Result.BAD_TEXT, refusal(intent(HubAction.RULE_ADD, null, 5, 5,
                "BI:" + (BookSection.MAX_ORDER_QUANTITY + 1) + ":minecraft:wheat"), on(HubTab.ORDERS)));
    }

    @Test void aRepeatedRequestIdIsRefusedTheSecondTime() {
        Recorder recorder = new Recorder();
        HubRouter.Intent collect = intent(HubAction.VAULT_COLLECT, null, 0, 0, "");
        assertEquals(Result.OK, HubRouter.route(collect, on(HubTab.VAULT), recorder));
        assertEquals(Result.STALE_REQUEST, HubRouter.route(collect, on(HubTab.VAULT), recorder));
        assertEquals(List.of("collectVault"), recorder.calls);
    }

    @Test void ruleFormsRoundTripAndRejectNonsense() {
        RuleForm form = new RuleForm(za.co.neroland.neroeconomy.rules.RuleSide.SELL, true, 32, "minecraft:logs");
        assertEquals(form, RuleForm.parse(form.encode()));
        assertEquals("minecraft:logs", RuleForm.parse("ST:32:#minecraft:logs").target());
        assertEquals(null, RuleForm.parse("BI:0:minecraft:wheat"));
        assertEquals(null, RuleForm.parse("BI:x:minecraft:wheat"));
        assertEquals(null, RuleForm.parse("BI:4:"));
        assertEquals(null, RuleForm.parse("BI:4:a b"));
        assertEquals(null, RuleForm.parse(null));
    }
}
