package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.link.LinkActionResult;

/** Every check an app action passes before it reaches a store, and the offline/online matrix. */
class LinkGateTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static LinkGate.Request request(String action) {
        return new LinkGate.Request(action, true, false, true, true, true, true, false);
    }

    @Test void theOfflineColumnIsExactlyTheDesignTable() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        for (String action : new String[] {"trade", "order_place", "order_cancel", "rule_set", "rule_delete",
                "rules_pause", "stock_buy", "stock_sell", "bond_buy", "pay", "offer_send", "offer_accept",
                "offer_decline", "claim_collect", "offer_cancel"}) expected.put(action, true);
        expected.put("vault_deposit", false);
        expected.put("vault_withdraw", false);
        assertEquals(expected, LinkActions.offlineTable());
        NeroEconomyLinkModule module = new NeroEconomyLinkModule();
        for (Map.Entry<String, Boolean> row : expected.entrySet())
            assertEquals(row.getValue(), module.allowOffline(row.getKey()), row.getKey());
        assertFalse(module.allowOffline("no_such_action"));
        // Taking an offer back is a refund: it spends its own request id and is not stopped by a closed market.
        assertTrue(LinkActions.RECORDS_OWN_REQUEST.contains("offer_cancel"));
        assertFalse(LinkActions.TRADING.contains("offer_cancel"));
        assertEquals("offer_cancel", LinkActions.ALL.get(LinkActions.ALL.size() - 1), "added at the end");
        assertEquals(LinkActions.ALL, module.actionIds());
    }

    @Test void everyActionRunsForAPlayerInGameAndOnlyTheOfflineOnesForAPlayerAway() {
        for (String action : LinkActions.ALL) {
            assertEquals(LinkGate.Refusal.NONE, LinkGate.check(request(action)), action);
            LinkGate.Refusal away = LinkGate.check(new LinkGate.Request(action, true, false, true, true, true, false,
                    false));
            assertEquals(LinkActions.OFFLINE.contains(action) ? LinkGate.Refusal.NONE : LinkGate.Refusal.OFFLINE, away,
                    action);
        }
        assertEquals(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED,
                LinkGate.result(LinkGate.Refusal.OFFLINE).error());
    }

    @Test void aRequestIdIsRequiredByEveryAction() {
        for (String action : LinkActions.ALL) {
            assertEquals(LinkGate.Refusal.NO_REQUEST, LinkGate.check(new LinkGate.Request(action, true, false, true, true,
                    false, true, false)), action);
        }
        LinkActionResult result = LinkGate.result(LinkGate.Refusal.NO_REQUEST);
        assertEquals(LinkActionResult.Error.VALIDATION, result.error());
        assertEquals(LinkPayloads.REQUEST_ID_REQUIRED, result.message());
    }

    @Test void aFrozenEconomyAndTheRemoteSwitchStopEveryActionFirst() {
        for (String action : LinkActions.ALL) {
            assertEquals(LinkGate.Refusal.FROZEN, LinkGate.check(new LinkGate.Request(action, true, true, false, false,
                    false, false, true)), action);
            assertEquals(LinkGate.Refusal.DISABLED, LinkGate.check(new LinkGate.Request(action, false, true, true, true,
                    true, true, false)), action);
        }
        assertEquals(LinkPayloads.ECONOMY_FROZEN, LinkGate.result(LinkGate.Refusal.FROZEN).message());
        assertEquals(LinkActionResult.Error.ACTION_DISABLED, LinkGate.result(LinkGate.Refusal.DISABLED).error());
        assertEquals(LinkGate.Refusal.UNKNOWN_ACTION, LinkGate.early("mint_money", true, false));
        assertEquals(LinkGate.Refusal.UNKNOWN_ACTION, LinkGate.early(null, true, false));
    }

    @Test void aClosedMarketStopsTradingButNotTakingBackCollectingOrTheVault() {
        for (String action : LinkActions.ALL) {
            LinkGate.Refusal refusal = LinkGate.check(new LinkGate.Request(action, true, false, true, true, true, true,
                    true));
            boolean trades = Map.of("trade", 1, "order_place", 1, "rule_set", 1, "stock_buy", 1, "stock_sell", 1,
                    "bond_buy", 1, "offer_send", 1, "offer_accept", 1).containsKey(action);
            assertEquals(trades ? LinkGate.Refusal.MARKET_CLOSED : LinkGate.Refusal.NONE, refusal, action);
        }
        assertEquals(LinkPayloads.MARKET_CLOSED, LinkGate.result(LinkGate.Refusal.MARKET_CLOSED).message());
    }

    @Test void theBudgetsAreCheckedBeforeAnythingElseAboutTheRequest() {
        assertEquals(LinkGate.Refusal.TOO_MANY, LinkGate.check(new LinkGate.Request("pay", true, false, false, false,
                false, false, true)));
        assertEquals(LinkGate.Refusal.TOO_OFTEN, LinkGate.check(new LinkGate.Request("pay", true, false, true, false,
                false, false, true)));
    }

    @Test void eachActionHasItsOwnLimitOnTopOfTheSharedBudget() {
        AtomicLong clock = new AtomicLong();
        LinkActionLimits limits = new LinkActionLimits(clock::get);
        int burst = za.co.neroland.neroeconomy.network.RateLimiter.Budget.VALUE.burst();
        for (int index = 0; index < burst; index++) assertTrue(limits.tryAcquire(ALICE, "pay"));
        assertFalse(limits.tryAcquire(ALICE, "pay"), "a burst of one action, then refused");
        assertTrue(limits.tryAcquire(ALICE, "order_place"), "another action has its own limit");
        // A token comes back after SLOWDOWN / perSecond seconds, not after the in-game half second.
        clock.addAndGet(1_000_000_000L);
        assertFalse(limits.tryAcquire(ALICE, "pay"));
        clock.addAndGet(LinkActionLimits.SLOWDOWN * 1_000_000_000L
                / za.co.neroland.neroeconomy.network.RateLimiter.Budget.VALUE.perSecond());
        assertTrue(limits.tryAcquire(ALICE, "pay"));
        limits.forget(ALICE);
        for (int index = 0; index < burst; index++) assertTrue(limits.tryAcquire(ALICE, "pay"));
        limits.clear();
        assertTrue(limits.tryAcquire(ALICE, "pay"));
    }

    @Test void everyRefusalIsAPlainSentenceFromTheLangFile() {
        for (LinkGate.Refusal refusal : LinkGate.Refusal.values()) {
            if (refusal == LinkGate.Refusal.NONE) continue;
            String message = LinkGate.result(refusal).message();
            assertFalse(message.isBlank(), refusal.name());
            assertFalse(message.startsWith("link.neroeconomy."), "unresolved key for " + refusal);
        }
    }
}
