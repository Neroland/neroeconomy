package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.level.ServerPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.StackLimit;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeSettings;

/**
 * Offline trading, end to end: two players who are both away from the game trade through the very
 * entry the bridge calls ({@link NeroEconomyLinkModule#act(String, LinkActions.Call)}): the shared
 * and per-action budgets, the gate that decides what works offline, the request id, the daily
 * remote spending limit and the action itself, against a real store. Only what needs a running
 * server is stood in for: the store is handed over instead of resolved from the world, the trading
 * rules get the configured settings with an open item catalogue instead of the item registry, and
 * nobody is in game.
 *
 * <p>After every step the store's invariants hold, and every item and every credit is still where
 * the books say: nothing is made or lost, and fees land in the Reserve.
 */
class OfflineTradeProofTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final String DIAMOND = "minecraft:diamond";
    /** A block item: stored and traded as a lot like any other item. */
    private static final String STONE = "minecraft:stone";
    private static final StackLimit LIMIT = (item, payload) -> 64;
    private static final long RESERVE = 250_000L;

    /** Nobody is in game; a recipient is found by UUID among the two players, as a known account. */
    private static final LinkActions.Players AWAY = new LinkActions.Players() {
        @Override public ServerPlayer online(UUID player) { return null; }
        @Override public UUID resolve(String recipient) {
            UUID id = KnownAccounts.parse(recipient);
            return ALICE.equals(id) || BOB.equals(id) ? id : null;
        }
    };

    private final NeroEconomyLinkModule module = new NeroEconomyLinkModule();
    private CurrencyProvider previous;
    private EconomyStore store;
    private EconomySavedData ledger;
    private TradeEnv env;
    private long credits;
    private Map<String, Long> goods;

    @BeforeEach void openTwoAccountsWithGoodsInAlicesVault() {
        store = new EconomyStore();
        ledger = store.ledger();
        ledger.seedReserve(RESERVE);
        for (UUID player : List.of(ALICE, BOB)) {
            // Opening pays the starting balance out of the Reserve; vested, so it may be paid on.
            ledger.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
            ledger.markVested(player);
        }
        env = new TradeEnv(TradeSettings.fromConfig(), LIMIT, null, ItemCatalog.OPEN, null, null);
        assertTrue(store.vault().deposit(account(ALICE), List.of(new Lot(DIAMOND, 10, 0), new Lot(STONE, 32, 0)),
                env.settings().vaultTradeCap(), LIMIT));
        previous = CurrencyApi.provider();
        StoreTesting.bindProvider(ledger);
        CurrencyApi.setProvider(NeroEconomyCommon.CURRENCY_PROVIDER);
        credits = creditsEverywhere();
        goods = goodsInStore();
        assertEquals(Map.of(DIAMOND, 10L, STONE, 32L), goods);
        assertTrue(store.checkInvariants());
    }

    @AfterEach void unbind() {
        CurrencyApi.setProvider(previous);
        StoreTesting.bindProvider(null);
        for (UUID player : List.of(ALICE, BOB)) {
            NeroEconomyLinkModule.forget(player);
            IntentGuard.forget(player);
        }
    }

    // --- the proof ---------------------------------------------------------------------------

    @Test void anItemABlockAndCreditsMoveVaultToVaultWithBothPlayersAway() {
        long aliceBefore = balance(ALICE);
        long bobBefore = balance(BOB);
        UUID send = UUID.randomUUID();
        JsonObject offer = params(send, "recipient", BOB.toString(), "credits", 20, "askCredits", 5);
        offer.add("goods", lines(DIAMOND, 3, STONE, 16));
        LinkActionResult sent = act(ALICE, "offer_send", offer);
        assertTrue(sent.ok(), sent.message());
        String offerId = sent.state().get("offerId").getAsString();
        assertEquals(20L, sent.state().get("credits").getAsLong());
        assertEquals(19L, sent.state().get("items").getAsLong());
        assertFalse(sent.state().toString().contains(BOB.toString()), "the recipient is never echoed back");
        // Everything given is escrowed at once: out of Alice's vault and balance, not yet Bob's.
        assertEquals(7L, vault(ALICE, DIAMOND));
        assertEquals(16L, vault(ALICE, STONE));
        assertEquals(aliceBefore - 20L, balance(ALICE));
        assertEquals(0L, vault(BOB, DIAMOND));

        // The same request sent again (a retry after a lost reply) does nothing.
        String before = holdings();
        LinkActionResult replay = act(ALICE, "offer_send", offer);
        assertFalse(replay.ok());
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, replay.message());
        assertEquals(before, holdings());

        UUID accept = UUID.randomUUID();
        LinkActionResult accepted = act(BOB, "offer_accept", params(accept, "offerId", offerId));
        assertTrue(accepted.ok(), accepted.message());
        assertEquals(20L, accepted.state().get("received").getAsLong());
        assertEquals(5L, accepted.state().get("paid").getAsLong());
        assertEquals(3L, vault(BOB, DIAMOND));
        assertEquals(16L, vault(BOB, STONE));
        long tax = accepted.state().get("tax").getAsLong();
        assertEquals(bobBefore + 20L - 5L - tax, balance(BOB));
        assertNull(store.offers().offer(UUID.fromString(offerId)));
        // What the sender asked for waits in their credit claim, as in game, until collected.
        assertEquals(aliceBefore - 20L, balance(ALICE));
        long asked = store.market().creditClaimTotal(ALICE);
        assertTrue(asked > 0L && asked <= 5L);
        LinkActionResult collected = act(ALICE, "claim_collect", params(UUID.randomUUID()));
        assertTrue(collected.ok(), collected.message());
        assertEquals(asked, collected.state().get("collected").getAsLong());
        assertEquals(aliceBefore - 20L + asked, balance(ALICE));

        // An accept's request id is spent: used again, even on another open offer, it does nothing.
        LinkActionResult second = act(ALICE, "offer_send", withGoods(params(UUID.randomUUID(), "recipient",
                BOB.toString()), DIAMOND, 1));
        assertTrue(second.ok(), second.message());
        before = holdings();
        LinkActionResult replayed = act(BOB, "offer_accept", params(accept, "offerId",
                second.state().get("offerId").getAsString()));
        assertFalse(replayed.ok());
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, replayed.message());
        assertEquals(before, holdings());
    }

    @Test void payMovesCreditsBetweenTwoPlayersWhoAreAwayAndAReplayPaysOnce() {
        long aliceBefore = balance(ALICE);
        long bobBefore = balance(BOB);
        JsonObject pay = params(UUID.randomUUID(), "recipient", BOB.toString(), "amount", 15);
        LinkActionResult paid = act(ALICE, "pay", pay);
        assertTrue(paid.ok(), paid.message());
        assertEquals(15L, paid.state().get("paid").getAsLong());
        assertEquals(aliceBefore - 15L, balance(ALICE));
        assertEquals(bobBefore + 15L, balance(BOB));
        String before = holdings();
        LinkActionResult replay = act(ALICE, "pay", pay);
        assertFalse(replay.ok());
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, replay.message());
        assertEquals(before, holdings());
        assertEquals(15L, spentToday(ALICE), "what was really paid counts against the remote limit, once");
    }

    @Test void aSellOrderFromTheVaultFillsARestingBuyAndCollectingPaysTheSeller() {
        long reserveBefore = ledger.balance(SystemAccountId.RESERVE);
        long bobBefore = balance(BOB);
        long fee = EconomyConfig.LISTING_FEE.get();
        LinkActionResult bid = act(BOB, "order_place", params(UUID.randomUUID(), "side", "buy", "item", DIAMOND,
                "qty", 4, "unitPrice", 10));
        assertTrue(bid.ok(), bid.message());
        assertEquals(4L, bid.state().get("resting").getAsLong());
        assertEquals(40L, bid.state().get("escrowed").getAsLong());
        assertEquals(bobBefore - 40L - fee, balance(BOB));

        UUID sell = UUID.randomUUID();
        JsonObject ask = params(sell, "side", "sell", "item", DIAMOND, "qty", 4, "unitPrice", 10);
        LinkActionResult filled = act(ALICE, "order_place", ask);
        assertTrue(filled.ok(), filled.message());
        assertEquals(4L, filled.state().get("filledNow").getAsLong());
        assertEquals(0L, filled.state().get("resting").getAsLong());
        // The buyer's goods went to the buyer's vault; the seller's to nobody else.
        assertEquals(4L, vault(BOB, DIAMOND));
        assertEquals(6L, vault(ALICE, DIAMOND));
        String before = holdings();
        LinkActionResult replay = act(ALICE, "order_place", ask);
        assertFalse(replay.ok());
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, replay.message());
        assertEquals(before, holdings());

        long waiting = store.market().creditClaimTotal(ALICE);
        long marketFee = 40L - waiting;
        assertTrue(waiting > 0L && marketFee >= 1L, "proceeds less the market fee wait in the credit claim");
        long aliceBefore = balance(ALICE);
        LinkActionResult collected = act(ALICE, "claim_collect", params(UUID.randomUUID()));
        assertTrue(collected.ok(), collected.message());
        assertEquals(waiting, collected.state().get("collected").getAsLong());
        assertEquals(aliceBefore + waiting, balance(ALICE));
        assertEquals(0L, store.market().creditClaimTotal(ALICE));
        // Both listing fees and the market fee, and nothing else, went to the Reserve.
        assertEquals(reserveBefore + 2L * fee + marketFee, ledger.balance(SystemAccountId.RESERVE));
        LinkActionResult again = act(ALICE, "claim_collect", params(UUID.randomUUID()));
        assertTrue(again.ok());
        assertEquals(0L, again.state().get("collected").getAsLong());
    }

    @Test void declineAndCancelReturnEverythingAndAReplayDoesNothingTwice() {
        long aliceBefore = balance(ALICE);
        String start = holdings();
        LinkActionResult first = act(ALICE, "offer_send", withGoods(params(UUID.randomUUID(), "recipient",
                BOB.toString(), "credits", 10), DIAMOND, 2));
        assertTrue(first.ok(), first.message());
        UUID decline = UUID.randomUUID();
        JsonObject declining = params(decline, "offerId", first.state().get("offerId").getAsString());
        LinkActionResult declined = act(BOB, "offer_decline", declining);
        assertTrue(declined.ok(), declined.message());
        assertEquals(start, holdings(), "a declined offer leaves both players as they were");
        LinkActionResult replay = act(BOB, "offer_decline", declining);
        assertFalse(replay.ok());
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, replay.message());
        assertEquals(start, holdings());

        LinkActionResult second = act(ALICE, "offer_send", withGoods(params(UUID.randomUUID(), "recipient",
                BOB.toString(), "credits", 7), STONE, 8));
        assertTrue(second.ok(), second.message());
        assertEquals(aliceBefore - 7L, balance(ALICE));
        UUID cancel = UUID.randomUUID();
        JsonObject cancelling = params(cancel, "offerId", second.state().get("offerId").getAsString());
        LinkActionResult cancelled = act(ALICE, "offer_cancel", cancelling);
        assertTrue(cancelled.ok(), cancelled.message());
        assertEquals(start, holdings(), "a cancelled offer comes back whole");
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, act(ALICE, "offer_cancel", cancelling).message());
        // A fresh id finds nothing left to take back.
        LinkActionResult gone = act(ALICE, "offer_cancel", params(UUID.randomUUID(), "offerId",
                second.state().get("offerId").getAsString()));
        assertFalse(gone.ok());
        assertEquals(start, holdings());
    }

    @Test void theVaultMovesAreRefusedWhileAwayAndNothingMoves() {
        String start = holdings();
        for (String action : List.of("vault_deposit", "vault_withdraw")) {
            assertFalse(module.allowOffline(action), action);
            LinkActionResult refused = act(ALICE, action, params(UUID.randomUUID()));
            assertFalse(refused.ok(), action);
            assertEquals(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED, refused.error(), action);
        }
        assertEquals(start, holdings());
    }

    @Test void anActionWithoutARequestIdOrOverTheRemoteLimitMovesNothing() {
        String start = holdings();
        JsonObject noId = new JsonObject();
        noId.addProperty("recipient", BOB.toString());
        noId.addProperty("amount", 5);
        LinkActionResult refused = act(ALICE, "pay", noId);
        assertEquals(LinkPayloads.REQUEST_ID_REQUIRED, refused.message());
        long over = EconomyConfig.REMOTE_DAILY_SPEND_LIMIT.get() + 1L;
        LinkActionResult limited = act(ALICE, "offer_send", withGoods(params(UUID.randomUUID(), "recipient",
                BOB.toString(), "credits", over), DIAMOND, 1));
        assertFalse(limited.ok());
        assertEquals(LinkText.get("link.neroeconomy.remote_limit", EconomyConfig.REMOTE_DAILY_SPEND_LIMIT.get(),
                EconomyConfig.REMOTE_DAILY_SPEND_LIMIT.get()), limited.message());
        assertEquals(start, holdings());
        assertEquals(0L, spentToday(ALICE));
    }

    @Test void everyActionTheProofUsesIsOneTheGateLetsThroughForAPlayerAway() {
        for (String action : List.of("offer_send", "offer_accept", "offer_decline", "offer_cancel", "pay",
                "order_place", "claim_collect"))
            assertTrue(module.allowOffline(action), action);
    }

    // --- R1: goods leaving through the app count against the daily remote goods limit --------

    /** What goods leaving an account through the app may be worth per economy-day. */
    private static long goodsLimit() { return EconomyConfig.REMOTE_DAILY_GOODS_LIMIT.get(); }

    /**
     * What one unit of goods with no reference price counts as. No commodity data is loaded here,
     * so every item is such goods.
     */
    private static long floorValue() { return EconomyConfig.REMOTE_GOODS_FLOOR_VALUE.get(); }

    /** Puts 40 more stacks of stone in Alice's vault: the goods a stolen token would go after. */
    private void fillAlicesVaultWithStone() {
        List<Lot> stacks = new java.util.ArrayList<>();
        for (int stack = 0; stack < 40; stack++) stacks.add(new Lot(STONE, 64, 0));
        assertTrue(store.vault().deposit(account(ALICE), stacks, env.settings().vaultTradeCap(), LIMIT));
        goods = goodsInStore();
    }

    @Test void aStolenTokenCannotEmptyTheVaultWithGoodsOnlyOffers() {
        fillAlicesVaultWithStone();
        long before = vault(ALICE, STONE);
        boolean refused = false;
        for (int offer = 0; offer < 16 && !refused; offer++) {
            JsonObject send = params(UUID.randomUUID(), "recipient", BOB.toString());
            send.add("goods", lines(STONE, 64));
            refused = !act(ALICE, "offer_send", send).ok();
        }
        long moved = before - vault(ALICE, STONE);
        assertTrue(moved > 0L, "goods within the limit still go");
        assertTrue(refused, "an offer past the limit is refused");
        assertTrue(moved * floorValue() <= goodsLimit(), "moved " + moved);
        assertEquals(moved * floorValue(), goodsToday(ALICE), "what left is what was counted");
        assertEquals(0L, spentToday(ALICE), "no credits were spent");
    }

    @Test void aStolenTokenCannotSellTheVaultForNothingOrGiveItAwayByRuleOrAcceptedOffer() {
        fillAlicesVaultWithStone();
        long stone = vault(ALICE, STONE);
        long tooMany = goodsLimit() / floorValue() + 1L;
        LinkActionResult order = act(ALICE, "order_place", params(UUID.randomUUID(), "side", "sell", "item", STONE,
                "qty", tooMany, "unitPrice", 1));
        assertFalse(order.ok(), "a sell order from the vault past the limit");
        LinkActionResult rule = act(ALICE, "rule_set", params(UUID.randomUUID(), "side", "sell", "target", STONE,
                "limitPrice", 1, "perDay", tooMany, "total", tooMany));
        assertFalse(rule.ok(), "a vault-backed sell rule past the limit");
        // The thief's own account asks for the goods for 1 credit; the stolen token accepts.
        LinkActionResult ask = act(BOB, "offer_send", params(UUID.randomUUID(), "recipient", ALICE.toString(),
                "credits", 1, "askItem", STONE, "askQty", tooMany));
        assertTrue(ask.ok(), ask.message());
        LinkActionResult accepted = act(ALICE, "offer_accept", params(UUID.randomUUID(), "offerId",
                ask.state().get("offerId").getAsString()));
        assertFalse(accepted.ok(), "accepting an offer that takes goods past the limit");
        assertEquals(stone, vault(ALICE, STONE));
        assertEquals(0L, spentToday(ALICE));
        assertEquals(0L, goodsToday(ALICE), "nothing refused is counted");
        // Within the limit the same actions still work, and are counted.
        LinkActionResult small = act(ALICE, "order_place", params(UUID.randomUUID(), "side", "sell", "item", STONE,
                "qty", 10, "unitPrice", 3));
        assertTrue(small.ok(), small.message());
        assertEquals(10L * floorValue(), goodsToday(ALICE));
    }

    // --- driving and checking ----------------------------------------------------------------

    /**
     * One action as the bridge hands it over for a player who is away, then the checks every step
     * must pass. The budgets are refilled first: they are proven in {@link LinkGateTest}, and here
     * a burst of steps would otherwise run into them.
     */
    private LinkActionResult act(UUID player, String action, JsonObject params) {
        NeroEconomyLinkModule.forget(player);
        IntentGuard.forget(player);
        LinkActions.Call call = new LinkActions.Call(null, store, env, AWAY, player, null, params,
                LinkPayloads.requestId(params), NeroEconomyLinkModule::balance);
        LinkActionResult result = NeroEconomyLinkModule.act(action, call);
        assertTrue(store.checkInvariants(), action);
        assertTrue(store.supplyReport().healthy(), action);
        assertEquals(credits, creditsEverywhere(), action + ": credits made or lost");
        assertEquals(goods, goodsInStore(), action + ": goods made or lost");
        if (!result.ok()) assertNull(result.state(), action);
        return result;
    }

    /**
     * §13 Input (J1 I4): every number an offline app action reads, set to {@code Long.MIN_VALUE},
     * -1, {@code Long.MAX_VALUE}, a number past 64 bits and a fraction, is refused by name before
     * anything moves (act() checks that nothing was made or lost after every call).
     */
    @Test void everyNumberAnOfflineActionReadsIsRefusedAtTheExtremesAndMovesNothing() {
        Object[] extremes = {Long.MIN_VALUE, -1L, Long.MAX_VALUE, new java.math.BigDecimal("1e30"), 1.5D};
        List<Object[]> cases = List.of(
                new Object[] {"pay", "amount", new Object[] {"recipient", BOB.toString(), "amount", 1}},
                new Object[] {"offer_send", "credits", new Object[] {"recipient", BOB.toString(), "credits", 1}},
                new Object[] {"offer_send", "askCredits", new Object[] {"recipient", BOB.toString(), "credits", 1,
                        "askCredits", 1}},
                new Object[] {"offer_send", "askQty", new Object[] {"recipient", BOB.toString(), "credits", 1,
                        "askItem", DIAMOND, "askQty", 1}},
                new Object[] {"order_place", "qty", new Object[] {"side", "sell", "item", DIAMOND, "qty", 1,
                        "unitPrice", 5}},
                new Object[] {"order_place", "unitPrice", new Object[] {"side", "buy", "item", DIAMOND, "qty", 1,
                        "unitPrice", 5}},
                new Object[] {"order_place", "payload", new Object[] {"side", "sell", "item", DIAMOND, "qty", 1,
                        "unitPrice", 5, "payload", 0}},
                new Object[] {"rule_set", "limitPrice", new Object[] {"side", "buy", "target", DIAMOND, "limitPrice", 5,
                        "perDay", 1, "total", 10}},
                new Object[] {"rule_set", "perDay", new Object[] {"side", "buy", "target", DIAMOND, "limitPrice", 5,
                        "perDay", 1, "total", 10}},
                new Object[] {"rule_set", "total", new Object[] {"side", "sell", "target", DIAMOND, "limitPrice", 5,
                        "perDay", 1, "total", 10}},
                new Object[] {"bond_buy", "principal", new Object[] {"term", "1d", "principal", 10}});
        for (Object[] row : cases) {
            String action = (String) row[0];
            String key = (String) row[1];
            for (Object extreme : extremes) {
                Object[] pairs = ((Object[]) row[2]).clone();
                boolean set = false;
                for (int index = 0; index < pairs.length; index += 2)
                    if (pairs[index].equals(key)) {
                        pairs[index + 1] = extreme;
                        set = true;
                    }
                if (!set) {
                    pairs = java.util.Arrays.copyOf(pairs, pairs.length + 2);
                    pairs[pairs.length - 2] = key;
                    pairs[pairs.length - 1] = extreme;
                }
                String before = holdings();
                LinkActionResult result = act(ALICE, action, params(UUID.randomUUID(), pairs));
                String what = action + " " + key + "=" + extreme;
                assertFalse(result.ok(), what);
                assertTrue(result.message().contains(key), what + ": " + result.message());
                assertEquals(before, holdings(), what);
            }
        }
        // Inside a goods line the same numbers are refused as bad goods.
        for (Object extreme : extremes) for (String key : List.of("qty", "payload")) {
            JsonObject offer = params(UUID.randomUUID(), "recipient", BOB.toString());
            JsonArray goods = lines(DIAMOND, 1);
            if (extreme instanceof Number number) goods.get(0).getAsJsonObject().addProperty(key, number);
            offer.add("goods", goods);
            String before = holdings();
            LinkActionResult result = act(ALICE, "offer_send", offer);
            assertFalse(result.ok(), key + "=" + extreme);
            assertTrue(result.message().contains("goods"), result.message());
            assertEquals(before, holdings());
        }
    }

    /** Every credit there is here: both players, the Reserve, and what escrow holds for offers, orders and claims. */
    private long creditsEverywhere() {
        return balance(ALICE) + balance(BOB) + ledger.balance(SystemAccountId.RESERVE)
                + ledger.balance(SystemAccountId.ESCROW) + ledger.balance(SystemAccountId.EXCHANGE);
    }

    /** Every item the store holds, wherever in it: vaults, open offers, the order book. */
    private Map<String, Long> goodsInStore() {
        Map<String, Long> out = new TreeMap<>();
        store.forEachLot(lot -> out.merge(lot.item(), (long) lot.count(), Long::sum));
        return out;
    }

    /** Both players' balances, vaults and credit claims, escrow and the Reserve, and what is open. */
    private String holdings() {
        return balance(ALICE) + "/" + store.vault().totals(account(ALICE)) + "/" + store.market().creditClaimTotal(ALICE)
                + ";" + balance(BOB) + "/" + store.vault().totals(account(BOB)) + "/"
                + store.market().creditClaimTotal(BOB) + ";" + ledger.balance(SystemAccountId.ESCROW) + ";"
                + ledger.balance(SystemAccountId.RESERVE) + ";" + store.offers().sentBy(account(ALICE)).size() + ";"
                + store.offers().receivedBy(account(BOB)).size();
    }

    private long balance(UUID player) { return ledger.getBalance(player, NeroEconomyCommon.CREDITS); }

    private long vault(UUID player, String item) { return store.vault().total(account(player), item, 0); }

    private long spentToday(UUID player) {
        return store.remoteSpend().spentToday(player, EconomySavedData.economyDay(LinkReads.now()));
    }

    private long goodsToday(UUID player) {
        return store.remoteSpend().goodsToday(player, EconomySavedData.economyDay(LinkReads.now()));
    }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    /** A request with its id and {@code key, value} pairs. */
    private static JsonObject params(UUID requestId, Object... pairs) {
        JsonObject out = new JsonObject();
        out.addProperty("requestId", requestId.toString());
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value instanceof Number number) out.addProperty((String) pairs[index], number);
            else out.addProperty((String) pairs[index], String.valueOf(value));
        }
        return out;
    }

    private static JsonObject withGoods(JsonObject params, Object... itemThenQty) {
        params.add("goods", lines(itemThenQty));
        return params;
    }

    /** Goods lines from the vault: {@code item, qty} pairs. */
    private static JsonArray lines(Object... itemThenQty) {
        JsonArray out = new JsonArray();
        for (int index = 0; index < itemThenQty.length; index += 2) {
            JsonObject line = new JsonObject();
            line.addProperty("item", (String) itemThenQty[index]);
            line.addProperty("qty", (Number) itemThenQty[index + 1]);
            out.add(line);
        }
        return out;
    }
}
