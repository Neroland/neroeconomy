package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.hub.HubPages.RowRef;
import za.co.neroland.neroeconomy.hub.HubRouter.Result;

/** Every F1b intent reaches its one server call, and each kind of bad one is refused before any. */
class HubRouterF1bTest {
    private static final UUID STOCK = new UUID(2L, 1L);
    private static final UUID TERM = new UUID(2L, 2L);
    private static final UUID HOLDING = new UUID(2L, 3L);
    private static final UUID BOND = new UUID(2L, 4L);
    private static final UUID OFFER_IN = new UUID(2L, 5L);
    private static final UUID OFFER_OUT = new UUID(2L, 6L);
    private static final UUID VAULT_LINE = new UUID(2L, 7L);
    private static final UUID JOB = new UUID(2L, 8L);
    private static final UUID AUCTION = new UUID(2L, 9L);
    private static final UUID SHOP = new UUID(2L, 10L);
    private static final UUID DATA = new UUID(2L, 11L);
    private static final Map<UUID, RowRef> ROWS = Map.ofEntries(
            Map.entry(STOCK, new RowRef(RowRef.Kind.STOCK, null, "neroeconomy:company", 0)),
            Map.entry(TERM, new RowRef(RowRef.Kind.BOND_TERM, null, "3d", 0)),
            Map.entry(HOLDING, new RowRef(RowRef.Kind.HOLDING, null, "neroeconomy:company", 0)),
            Map.entry(BOND, new RowRef(RowRef.Kind.BOND, BOND, "", 0)),
            Map.entry(OFFER_IN, new RowRef(RowRef.Kind.OFFER_IN, OFFER_IN, "", 0)),
            Map.entry(OFFER_OUT, new RowRef(RowRef.Kind.OFFER_OUT, OFFER_OUT, "", 0)),
            Map.entry(VAULT_LINE, new RowRef(RowRef.Kind.VAULT_LINE, null, "minecraft:wheat", 0)),
            Map.entry(JOB, new RowRef(RowRef.Kind.JOB, JOB, "", 0)),
            Map.entry(AUCTION, new RowRef(RowRef.Kind.AUCTION, AUCTION, "minecraft:diamond", 0)),
            Map.entry(SHOP, new RowRef(RowRef.Kind.PLAYER_SHOP, null, "minecraft:overworld|1", 0)),
            Map.entry(DATA, new RowRef(RowRef.Kind.DATA, null, "", 0)));

    private static HubRouter.Context on(HubTab tab) { return new HubRouter.Context(tab, ROWS, false, true, false); }

    private static HubRouter.Intent intent(HubAction action, UUID target, long a, long b, String text) {
        return new HubRouter.Intent(UUID.randomUUID(), action.tab(), action, target, a, b, text);
    }

    private static String call(HubAction action, UUID target, long a, long b, String text) {
        HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
        assertEquals(Result.OK, HubRouter.route(intent(action, target, a, b, text), on(action.tab()), recorder),
                action.name());
        assertEquals(1, recorder.calls.size(), action.name());
        return recorder.calls.get(0);
    }

    private static Result refusal(HubRouter.Intent intent, HubRouter.Context context) {
        HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
        Result result = HubRouter.route(intent, context, recorder);
        assertTrue(recorder.calls.isEmpty(), "a refused intent still called " + recorder.calls);
        return result;
    }

    private static Result refusal(HubAction action, UUID target, long a, long b, String text) {
        return refusal(intent(action, target, a, b, text), on(action.tab()));
    }

    @Test void eachF1bActionReachesItsOwnCall() {
        assertEquals("myData true", call(HubAction.WALLET_MY_DATA, null, 1, 0, ""));
        assertEquals("investView PORTFOLIO", call(HubAction.INVEST_VIEW, null, 1, 0, ""));
        assertEquals("investPractice true", call(HubAction.INVEST_PRACTICE, null, 1, 0, ""));
        assertEquals("investPick STOCK neroeconomy:company", call(HubAction.INVEST_PICK, STOCK, 0, 0, ""));
        assertEquals("investPick BOND_TERM 3d", call(HubAction.INVEST_PICK, TERM, 0, 0, ""));
        assertEquals("investQuote 250", call(HubAction.INVEST_QUOTE, null, 250, 0, ""));
        assertEquals("investBuy 250", call(HubAction.INVEST_BUY, null, 250, 0, ""));
        assertEquals("investSellQuote neroeconomy:company 0", call(HubAction.INVEST_SELL_QUOTE, HOLDING, 0, 0, ""));
        assertEquals("investSell neroeconomy:company 0 -1", call(HubAction.INVEST_SELL, HOLDING, 0, -1, ""));
        assertEquals("investSell neroeconomy:company 5 12", call(HubAction.INVEST_SELL, HOLDING, 5, 12, ""));
        assertEquals("investRedeem BOND", call(HubAction.INVEST_REDEEM, BOND, 0, 0, ""));
        assertEquals("investReset", call(HubAction.INVEST_RESET, null, 0, 0, ""));
        assertEquals("tradeView DIRECT", call(HubAction.TRADE_VIEW, null, 3, 0, ""));
        assertEquals("offerAccept OFFER_IN", call(HubAction.OFFER_ACCEPT, OFFER_IN, 0, 0, ""));
        assertEquals("offerDecline OFFER_IN", call(HubAction.OFFER_DECLINE, OFFER_IN, 0, 0, ""));
        assertEquals("offerCancel OFFER_OUT", call(HubAction.OFFER_CANCEL, OFFER_OUT, 0, 0, ""));
        assertEquals("offerSend VAULT_LINE Alex;V;3;0;;0;5",
                call(HubAction.OFFER_SEND, VAULT_LINE, 0, 0, "Alex;V;3;0;;0;5"));
        assertEquals("offerSend none Alex;H;1;0;minecraft:emerald;2;0",
                call(HubAction.OFFER_SEND, null, 0, 0, "Alex;H;1;0;minecraft:emerald;2;0"));
        assertEquals("offerMute true", call(HubAction.OFFER_MUTE, null, 1, 0, ""));
        assertEquals("tradeRequest Alex", call(HubAction.TRADE_REQUEST, null, 0, 0, "Alex"));
        assertEquals("jobsView POST", call(HubAction.JOBS_VIEW, null, 4, 0, ""));
        assertEquals("jobClaim", call(HubAction.JOB_CLAIM, JOB, 0, 0, ""));
        assertEquals("jobRelease", call(HubAction.JOB_RELEASE, JOB, 0, 0, ""));
        assertEquals("jobFulfil", call(HubAction.JOB_FULFIL, JOB, 0, 0, ""));
        assertEquals("jobCancel", call(HubAction.JOB_CANCEL, JOB, 0, 0, ""));
        assertEquals("jobPost D;minecraft:wheat;64;here 100", call(HubAction.JOB_POST, null, 100, 0, "D;minecraft:wheat;64;here"));
        assertEquals("auctionsView CREATE", call(HubAction.AUCTION_VIEW, null, 2, 0, ""));
        assertEquals("auctionBid 42", call(HubAction.AUCTION_BID, AUCTION, 42, 0, ""));
        assertEquals("auctionCancel", call(HubAction.AUCTION_CANCEL, AUCTION, 0, 0, ""));
        assertEquals("auctionCreate 16 10 60 true", call(HubAction.AUCTION_CREATE, null, 16, 10, "60:1"));
        assertEquals("shopPrice 7", call(HubAction.SHOP_PRICE, SHOP, 7, 0, ""));
        assertEquals("shopOpen", call(HubAction.SHOP_OPEN, SHOP, 0, 0, ""));
    }

    @Test void everyF1bActionBelongsToItsTabAndValueMovesAreMarked() {
        for (HubAction action : List.of(HubAction.INVEST_BUY, HubAction.INVEST_SELL, HubAction.INVEST_REDEEM,
                HubAction.INVEST_RESET, HubAction.OFFER_ACCEPT, HubAction.OFFER_DECLINE, HubAction.OFFER_CANCEL,
                HubAction.OFFER_SEND, HubAction.TRADE_REQUEST, HubAction.JOB_CLAIM, HubAction.JOB_RELEASE,
                HubAction.JOB_FULFIL, HubAction.JOB_CANCEL, HubAction.JOB_POST, HubAction.AUCTION_BID,
                HubAction.AUCTION_CANCEL, HubAction.AUCTION_CREATE, HubAction.SHOP_PRICE)) {
            assertTrue(action.movesValue(), action + " must be frozen-checked and on the tight budget");
            assertTrue(action.request() != HubAction.Request.NONE, action + " must spend a request id");
        }
        assertEquals(HubAction.Request.CALL, HubAction.INVEST_BUY.request());
        assertEquals(HubAction.Request.CALL, HubAction.OFFER_SEND.request());
        assertTrue(HubAction.AUCTION_CREATE.takesGoods());
    }

    @Test void aFrozenEconomyRefusesEveryValueMove() {
        for (HubAction action : HubAction.values()) {
            // The Admin tab's own refusals are tested in HubRouterAdminTest.
            if (!action.movesValue() || action.tab() == null || action.ordinal() < HubAction.WALLET_MY_DATA.ordinal()
                    || action.adminOnly()) continue;
            HubRouter.Context frozen = new HubRouter.Context(action.tab(), ROWS, true, true, false);
            UUID target = switch (action) {
                case INVEST_SELL -> HOLDING;
                case INVEST_REDEEM -> BOND;
                case OFFER_ACCEPT, OFFER_DECLINE -> OFFER_IN;
                case OFFER_CANCEL -> OFFER_OUT;
                case JOB_CLAIM, JOB_RELEASE, JOB_FULFIL, JOB_CANCEL -> JOB;
                case AUCTION_BID, AUCTION_CANCEL -> AUCTION;
                case SHOP_PRICE -> SHOP;
                default -> null;
            };
            String text = switch (action) {
                case OFFER_SEND -> "Alex;N;0;5;;0;0";
                case TRADE_REQUEST -> "Alex";
                case JOB_POST -> "C;minecraft:wheat;1;";
                case AUCTION_CREATE -> "60:0";
                default -> "";
            };
            long a = action == HubAction.INVEST_SELL ? 0L : 1L;
            assertEquals(Result.FROZEN, refusal(intent(action, target, a, 1, text), frozen), action.name());
        }
    }

    @Test void goodsFromAnInventoryAreRefusedInCreativeOrSpectatorMode() {
        HubRouter.Context trade = new HubRouter.Context(HubTab.TRADE, ROWS, false, false, false);
        assertEquals(Result.GOODS_SOURCE, refusal(intent(HubAction.OFFER_SEND, null, 0, 0, "Alex;H;1;0;;0;0"), trade));
        HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
        assertEquals(Result.OK, HubRouter.route(intent(HubAction.OFFER_SEND, VAULT_LINE, 0, 0, "Alex;V;1;0;;0;0"), trade,
                recorder), "giving from the vault takes nothing from an inventory");
        assertEquals(Result.OK, HubRouter.route(intent(HubAction.OFFER_SEND, null, 0, 0, "Alex;N;0;5;;0;0"), trade,
                recorder), "credits only");
        assertEquals(Result.GOODS_SOURCE, refusal(intent(HubAction.JOB_FULFIL, JOB, 0, 0, ""),
                new HubRouter.Context(HubTab.JOBS, ROWS, false, false, false)));
        assertEquals(Result.GOODS_SOURCE, refusal(intent(HubAction.AUCTION_CREATE, null, 1, 1, "60:0"),
                new HubRouter.Context(HubTab.AUCTIONS, ROWS, false, false, false)));
    }

    @Test void rowsOfTheWrongKindOrNeverSentAreRefused() {
        assertEquals(Result.NO_ROW, refusal(HubAction.INVEST_PICK, HOLDING, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.INVEST_PICK, null, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.INVEST_SELL, STOCK, 0, -1, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.INVEST_SELL_QUOTE, BOND, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.INVEST_REDEEM, HOLDING, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.OFFER_ACCEPT, OFFER_OUT, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.OFFER_DECLINE, OFFER_OUT, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.OFFER_CANCEL, OFFER_IN, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.OFFER_SEND, OFFER_IN, 0, 0, "Alex;V;1;0;;0;0"));
        assertEquals(Result.NO_ROW, refusal(HubAction.OFFER_SEND, null, 0, 0, "Alex;V;1;0;;0;0"),
                "giving from the vault needs the vault line");
        assertEquals(Result.NO_ROW, refusal(HubAction.OFFER_SEND, VAULT_LINE, 0, 0, "Alex;H;1;0;;0;0"),
                "giving from the hand names no row");
        assertEquals(Result.NO_ROW, refusal(HubAction.JOB_CLAIM, AUCTION, 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.JOB_FULFIL, new UUID(9L, 9L), 0, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.AUCTION_BID, JOB, 5, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.SHOP_PRICE, DATA, 5, 0, ""));
        assertEquals(Result.NO_ROW, refusal(HubAction.SHOP_OPEN, null, 0, 0, ""));
    }

    @Test void numbersOutsideTheirRangeAreRefused() {
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.WALLET_MY_DATA, null, 2, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.INVEST_VIEW, null, 2, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.INVEST_PRACTICE, null, -1, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.INVEST_QUOTE, null, 0, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.INVEST_BUY, null, HubRouter.MAX_CREDITS + 1, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.INVEST_SELL_QUOTE, HOLDING, -1, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.INVEST_SELL, HOLDING, 0, -2, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.TRADE_VIEW, null, 4, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.OFFER_MUTE, null, 3, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.JOBS_VIEW, null, 5, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.JOB_POST, null, 0, 0, "C;minecraft:wheat;1;"));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.AUCTION_VIEW, null, -1, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.AUCTION_BID, AUCTION, 0, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.AUCTION_CREATE, null, 65, 1, "60:0"));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.AUCTION_CREATE, null, 1, 0, "60:0"));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.SHOP_PRICE, SHOP, 0, 0, ""));
        assertEquals(Result.BAD_NUMBER, refusal(HubAction.SHOP_PRICE, SHOP, HubRouter.MAX_SHOP_PRICE + 1, 0, ""));
    }

    @Test void badFormsAndNamesAreRefused() {
        assertEquals(Result.BAD_TEXT, refusal(HubAction.OFFER_SEND, null, 0, 0, "Alex;N;0;0;;0;0"));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.OFFER_SEND, null, 0, 0, "nonsense"));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.TRADE_REQUEST, null, 0, 0, ""));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.TRADE_REQUEST, null, 0, 0, "two words"));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.TRADE_REQUEST, null, 0, 0, "n".repeat(65)));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.JOB_POST, null, 5, 0, "C;minecraft:wheat;1;here"));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.JOB_POST, null, 5, 0, "B;player:" + new UUID(1L, 1L) + ";1;"));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.AUCTION_CREATE, null, 1, 1, "0:0"));
        assertEquals(Result.BAD_TEXT, refusal(HubAction.AUCTION_CREATE, null, 1, 1, ""));
    }

    @Test void f1bActionsAreRefusedOnAnotherTab() {
        assertEquals(Result.WRONG_TAB, refusal(intent(HubAction.INVEST_BUY, null, 5, 0, ""), on(HubTab.MARKET)));
        assertEquals(Result.WRONG_TAB, refusal(intent(HubAction.OFFER_ACCEPT, OFFER_IN, 0, 0, ""), on(HubTab.JOBS)));
        assertEquals(Result.WRONG_TAB, refusal(new HubRouter.Intent(UUID.randomUUID(), HubTab.SHOP, HubAction.AUCTION_BID,
                AUCTION, 5, 0, ""), on(HubTab.AUCTIONS)));
    }

    @Test void aRepeatedRequestIdActsOnce() {
        HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
        HubRouter.Intent claim = intent(HubAction.JOB_CLAIM, JOB, 0, 0, "");
        assertEquals(Result.OK, HubRouter.route(claim, on(HubTab.JOBS), recorder));
        assertEquals(Result.STALE_REQUEST, HubRouter.route(claim, on(HubTab.JOBS), recorder));
        HubRouter.Intent bid = intent(HubAction.AUCTION_BID, AUCTION, 9, 0, "");
        assertEquals(Result.OK, HubRouter.route(bid, on(HubTab.AUCTIONS), recorder));
        assertEquals(Result.STALE_REQUEST, HubRouter.route(bid, on(HubTab.AUCTIONS), recorder));
        assertEquals(List.of("jobClaim", "auctionBid 9"), recorder.calls);
    }
}
