package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.EconomyEvents;
import za.co.neroland.neroeconomy.api.EconomyResult;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.economy.TransactionRecord;
import za.co.neroland.neroeconomy.economy.TransactionType;

/** The cross-mod API's payouts, charges and transfers against a store bound as a running server binds it. */
class SiblingPaymentsTest {
    private static final Set<String> REASONS = Set.of("quest_reward", "fine", "note");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID P4 = UUID.fromString("00000000-0000-0000-0000-0000000000b4");

    private EconomyStore store;
    private EconomySavedData ledger;
    private CurrencyProvider previous;
    private final AtomicLong nanos = new AtomicLong();

    @BeforeEach void bind() {
        store = new EconomyStore();
        ledger = store.ledger();
        ledger.seedReserve(250_000L);
        for (UUID player : List.of(P1, P2, P3, P4)) {
            ledger.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
            ledger.markVested(player);
        }
        previous = CurrencyApi.provider();
        StoreTesting.bindProvider(ledger);
        CurrencyApi.setProvider(NeroEconomyCommon.CURRENCY_PROVIDER);
        // Each call a second apart, so the rate limit never refuses unless a test means it to.
        SiblingTesting.rateClock(() -> nanos.addAndGet(1_000_000_000L));
        EconomyEventBus.SIBLING_PAID.clearForTest();
    }

    @AfterEach void unbind() {
        CurrencyApi.setProvider(previous);
        StoreTesting.bindProvider(null);
        SiblingTesting.rateClock(null);
        EconomyEventBus.SIBLING_PAID.clearForTest();
        EconomyEventBus.clearPending();
        EconomyHealth.clear();
    }

    private EconomyResult pay(String mod, UUID player, long amount) {
        return SiblingPayments.pay(store, mod, REASONS, player, amount, "quest_reward", null);
    }

    private long balance(UUID player) { return ledger.getBalance(player, NeroEconomyCommon.CREDITS); }
    private long reserve() { return ledger.balance(SystemAccountId.RESERVE); }

    @Test void aPayoutMovesFromTheReserveAndTagsTheRow() {
        long before = reserve();
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P1, 50L));
        assertEquals(150L, balance(P1));
        assertEquals(before - 50L, reserve());
        TransactionRecord row = ledger.export(P1).transactions().stream()
                .filter(each -> each.type() == TransactionType.SIBLING_PAYOUT).findFirst().orElseThrow();
        assertEquals(50L, row.amount());
        assertEquals("neroquests/quest_reward", row.origin());
        assertEquals("", ledger.export(P1).transactions().stream()
                .filter(each -> each.type() == TransactionType.STARTING_BALANCE).findFirst().orElseThrow().origin());
        assertTrue(store.checkInvariants());
    }

    @Test void theCallersOwnLimitRefusesBeforeAnythingMoves() {
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P1, 1_000L));
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P2, 1_000L));
        long reserve = reserve();
        assertEquals(EconomyResult.CALLER_BUDGET_SPENT, pay("neroquests", P3, 1_000L));
        assertEquals(100L, balance(P3));
        assertEquals(reserve, reserve());
        assertEquals(500L, SiblingPayments.remainingToday(store, "neroquests"));
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P3, 500L));
        assertEquals(2_500L, SiblingPayments.remainingToday(store, "nerocolonies"), "another caller is untouched");
    }

    @Test void theSharedLimitCapsAllCallersTogether() {
        for (String mod : List.of("moda", "modb")) {
            assertEquals(EconomyResult.APPLIED, pay(mod, P1, 1_000L));
            assertEquals(EconomyResult.APPLIED, pay(mod, P2, 1_000L));
            assertEquals(EconomyResult.APPLIED, pay(mod, P3, 500L));
        }
        assertEquals(EconomyResult.SIBLING_BUDGET_SPENT, pay("modc", P4, 1L));
        assertEquals(100L, balance(P4));
        assertEquals(0L, SiblingPayments.remainingToday(store, "modc"));
    }

    @Test void anUntypedCoreDepositCountsAgainstTheSharedLimitOnly() {
        assertTrue(ledger.deposit(P1, NeroEconomyCommon.CREDITS, 4_500L));
        assertEquals(500L, SiblingPayments.remainingToday(store, "neroquests"));
        assertEquals(EconomyResult.SIBLING_BUDGET_SPENT, pay("neroquests", P2, 501L));
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P2, 500L));
    }

    @Test void thePerPlayerCapCountsEveryCaller() {
        assertEquals(EconomyResult.APPLIED, pay("moda", P1, 1_000L));
        assertEquals(EconomyResult.APPLIED, pay("modb", P1, 1_000L));
        assertEquals(EconomyResult.PLAYER_CAP_REACHED, pay("modb", P1, 1L));
        assertEquals(EconomyResult.PLAYER_CAP_REACHED, SiblingPayments.canPay(store, "modc", P1, 1L));
        assertEquals(2_100L, balance(P1));
        assertEquals(EconomyResult.APPLIED, pay("modb", P2, 1L), "another player is untouched");
    }

    @Test void argumentsAndReasonsAreCheckedFirst() {
        assertEquals(EconomyResult.INVALID, pay("neroquests", P1, 0L));
        assertEquals(EconomyResult.INVALID, pay("neroquests", P1, 1_001L), "above siblingMaxSinglePayout");
        assertEquals(EconomyResult.INVALID, pay("neroquests", null, 1L));
        assertEquals(EconomyResult.UNKNOWN_REASON,
                SiblingPayments.pay(store, "neroquests", REASONS, P1, 1L, "bribe", null));
        assertEquals(EconomyResult.INVALID, SiblingPayments.pay(store, "neroquests", REASONS, P1, 1L, "quest_reward",
                P1.toString()), "a key that reads as a player id");
        assertEquals(EconomyResult.UNAVAILABLE, SiblingPayments.pay(null, "neroquests", REASONS, P1, 1L,
                "quest_reward", null));
        assertEquals(100L, balance(P1));
    }

    @Test void aReplayedKeyMovesNothingAndARefusedOneMayBeRetried() {
        assertEquals(EconomyResult.APPLIED, SiblingPayments.pay(store, "neroquests", REASONS, P1, 40L, "quest_reward",
                "quest:42"));
        assertEquals(EconomyResult.DUPLICATE, SiblingPayments.pay(store, "neroquests", REASONS, P1, 40L, "quest_reward",
                "quest:42"));
        assertTrue(EconomyResult.DUPLICATE.ok());
        assertEquals(140L, balance(P1));
        assertEquals(EconomyResult.APPLIED, SiblingPayments.pay(store, "nerocolonies", REASONS, P1, 40L,
                "quest_reward", "quest:42"), "keys are per caller");
        assertEquals(EconomyResult.INSUFFICIENT_FUNDS, SiblingPayments.charge(store, "neroquests", REASONS, P2,
                1_000L, "fine", "fine:7"));
        assertEquals(EconomyResult.APPLIED, SiblingPayments.charge(store, "neroquests", REASONS, P2, 10L, "fine",
                "fine:7"), "only an applied key is remembered");
        EconomyStore reloaded = StoreTesting.reload(store);
        assertTrue(reloaded.sibling().seen("neroquests", "quest:42", ledger.now()), "keys survive a restart");
    }

    @Test void frozenRefusesEverything() {
        EconomyHealth.freeze();
        assertEquals(EconomyResult.FROZEN, pay("neroquests", P1, 1L));
        assertEquals(EconomyResult.FROZEN, SiblingPayments.charge(store, "neroquests", REASONS, P1, 1L, "fine", null));
        assertEquals(EconomyResult.FROZEN, SiblingPayments.canPay(store, "neroquests", P1, 1L));
        assertEquals(0L, SiblingPayments.remainingToday(store, "neroquests"));
        EconomyHealth.clear();
        assertEquals(100L, balance(P1));
    }

    @Test void theRateLimitIsPerCaller() {
        SiblingTesting.rateClock(() -> 0L);
        for (int index = 0; index < RateLimiterBurst.SIBLING; index++)
            assertEquals(EconomyResult.APPLIED, pay("neroquests", P1, 1L));
        assertEquals(EconomyResult.RATE_LIMITED, pay("neroquests", P1, 1L));
        assertEquals(EconomyResult.APPLIED, pay("nerocolonies", P1, 1L), "another caller has its own bucket");
    }

    @Test void aChargeMaySpendLockedStartingCredits() {
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        ledger.withdraw(fresh, NeroEconomyCommon.CREDITS, 0L);
        assertTrue(ledger.locked(fresh) > 0L);
        long reserve = reserve();
        assertEquals(EconomyResult.APPLIED, SiblingPayments.charge(store, "neroquests", REASONS, fresh, 30L, "fine", null));
        assertEquals(70L, balance(fresh));
        assertEquals(reserve + 30L, reserve());
        assertEquals(EconomyResult.INSUFFICIENT_FUNDS,
                SiblingPayments.charge(store, "neroquests", REASONS, fresh, 71L, "fine", null));
        assertTrue(store.checkInvariants());
    }

    @Test void aTransferTakesItsFeeFromThePayeesSide() {
        long reserve = reserve();
        assertEquals(5L, SiblingPayments.feeFor(100L));
        assertEquals(EconomyResult.APPLIED, SiblingPayments.transfer(store, "neronotes", REASONS, P1, P2, 100L, "note",
                null));
        assertEquals(0L, balance(P1));
        assertEquals(195L, balance(P2));
        assertEquals(reserve + 5L, reserve());
        assertEquals(EconomyResult.INVALID, SiblingPayments.transfer(store, "neronotes", REASONS, P2, P2, 10L, "note",
                null));
        assertEquals(EconomyResult.INVALID, SiblingPayments.transfer(store, "neronotes", REASONS, P2, P3, 1L, "note",
                null), "a transfer too small to carry its fee");
        assertEquals(EconomyResult.INSUFFICIENT_FUNDS, SiblingPayments.transfer(store, "neronotes", REASONS, P1, P3,
                10L, "note", null));
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        ledger.withdraw(fresh, NeroEconomyCommon.CREDITS, 0L);
        assertEquals(EconomyResult.FUNDS_LOCKED, SiblingPayments.transfer(store, "neronotes", REASONS, fresh, P3,
                50L, "note", null));
        assertEquals(100L, balance(P3));
        assertTrue(store.checkInvariants());
    }

    @Test void thePaidEventFiresOnceOnlyWhenAppliedAndSurvivesAThrowingListener() {
        List<EconomyEvents.SiblingPaid> seen = new ArrayList<>();
        EconomyEventBus.SIBLING_PAID.add(event -> { throw new IllegalStateException("listener bug"); });
        EconomyEventBus.SIBLING_PAID.add(seen::add);
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P1, 25L));
        assertEquals(EconomyResult.INVALID, pay("neroquests", P1, 0L));
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P1, 5L));
        assertEquals(0, seen.size(), "queued until the end of the tick");
        EconomyEventBus.drain();
        assertEquals(2, seen.size());
        assertEquals(new EconomyEvents.SiblingPaid(P1, "neroquests", "quest_reward", 25L, seen.get(0).at()), seen.get(0));
        assertEquals(130L, balance(P1));
        assertEquals(2, EconomyEventBus.SIBLING_PAID.size(), "the throwing listener stays registered");
    }

    @Test void theSectionRollsItsDayAndIsErasedAndExported() {
        SiblingSection section = new SiblingSection();
        section.count("neroquests", P1, 300L, 10L);
        assertEquals(300L, section.callerUsed("neroquests", 10L));
        assertEquals(300L, section.playerUsed(P1, 10L));
        assertEquals(0L, section.callerUsed("neroquests", 11L), "a new day starts at zero");
        JsonObject export = section.exportFor(P1);
        assertEquals(300L, export.get("credits_from_other_mods_today").getAsLong());
        assertEquals(10L, export.get("economy_day").getAsLong());
        assertEquals(0, section.exportFor(P2).size());
        section.forgetPlayer(P1);
        assertEquals(0L, section.playerUsed(P1, 10L));
        assertEquals(0, section.exportFor(P1).size());
        assertEquals(300L, section.callerUsed("neroquests", 10L), "the caller's count is not personal and stays");
        section.count("neroquests", P2, 1L, 11L);
        assertEquals(1L, section.callerUsed("neroquests", 11L));
        assertEquals(0L, section.playerUsed(P1, 11L));
    }

    @Test void theSectionRoundTripsAndLoadsEmpty() {
        SiblingSection section = new SiblingSection();
        section.count("neroquests", P1, 300L, 10L);
        section.remember("neroquests", "quest:1", 1_000L);
        JsonElement encoded = SiblingSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow();
        SiblingSection restored = SiblingSection.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(300L, restored.playerUsed(P1, 10L));
        assertTrue(restored.seen("neroquests", "quest:1", 1_000L));
        assertFalse(restored.seen("neroquests", "quest:1", 1_000L + SiblingSection.KEY_LIFETIME_MILLIS));
        assertTrue(SiblingSection.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().isEmpty());
        assertEquals(1, restored.purgeKeys(1_000L + SiblingSection.KEY_LIFETIME_MILLIS));
    }

    @Test void thePerPlayerCountIsKeptOnlyWhileTheCapIsOn() {
        assertEquals(EconomyResult.APPLIED, pay("neroquests", P1, 10L));
        assertEquals(1, store.sibling().playerCount());
    }

    /** The configured burst of the sibling bucket, named for the test above. */
    private static final class RateLimiterBurst {
        static final int SIBLING = za.co.neroland.neroeconomy.network.RateLimiter.Budget.SIBLING.burst();
    }
}
