package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CurrencyEvents;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/** The typed ledger calls with a currency listener that throws, as another mod's might. */
class ServerCurrencyProviderTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicInteger FIRED = new AtomicInteger();
    private static volatile Runnable nested;

    private CurrencyProvider previous;
    private ServerCurrencyProvider provider;
    private EconomySavedData data;

    @BeforeAll static void registerThrowingListener() {
        // Core cannot remove a listener, so this one stays for the JVM and only acts while armed.
        CurrencyEvents.onChange(change -> {
            if (!ARMED.get()) return;
            FIRED.incrementAndGet();
            Runnable hook = nested;
            nested = null;
            if (hook != null) hook.run();
            throw new IllegalStateException("listener failed");
        });
    }

    @BeforeEach void bind() {
        previous = CurrencyApi.provider();
        provider = new ServerCurrencyProvider();
        data = Ledgers.seeded();
        Ledgers.open(data, ALICE, BOB);
        provider.bindForTest(data);
        CurrencyApi.setProvider(provider);
        FIRED.set(0);
        nested = null;
        ARMED.set(true);
    }

    @AfterEach void restore() {
        ARMED.set(false);
        nested = null;
        CurrencyApi.setProvider(previous);
    }

    @Test void depositReportsTheLedgerOutcomeWhenTheListenerThrows() {
        assertTrue(provider.depositTyped(ALICE, NeroEconomyCommon.CREDITS, 25L, TransactionType.ADMIN_GRANT));
        assertEquals(125L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(1, FIRED.get());
    }

    @Test void withdrawReportsTheLedgerOutcomeWhenTheListenerThrows() {
        assertTrue(provider.withdrawTyped(ALICE, NeroEconomyCommon.CREDITS, 40L, TransactionType.ADMIN_TAKE));
        assertEquals(60L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        // A refused debit fires no listener and stays refused.
        assertFalse(provider.withdrawTyped(ALICE, NeroEconomyCommon.CREDITS, 1_000L, TransactionType.ADMIN_TAKE));
        assertEquals(60L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(1, FIRED.get());
    }

    @Test void transferReportsTheLedgerOutcomeWhenTheListenerThrows() {
        assertTrue(provider.transferTyped(ALICE, BOB, NeroEconomyCommon.CREDITS, 30L, TransactionType.SHOP_PURCHASE));
        assertEquals(70L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(130L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertFalse(provider.transferTyped(ALICE, BOB, NeroEconomyCommon.CREDITS, 500L, TransactionType.SHOP_PURCHASE));
        assertEquals(70L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(130L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
    }

    @Test void typedReasonIsWrittenForItsOwnCallAndNeverLeaksIntoANestedOne() {
        // The listener makes its own, untyped ledger call from inside the typed one.
        nested = () -> CurrencyApi.deposit(BOB, NeroEconomyCommon.CREDITS, 1L);
        assertEquals(LedgerResult.APPLIED, provider.move(new PlayerAccountId(ALICE), SystemAccountId.ESCROW, 10L,
                TransactionType.MARKET_ESCROW));
        assertEquals(90L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(101L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(10L, data.balance(SystemAccountId.ESCROW));
        assertTrue(types(ALICE).contains(TransactionType.MARKET_ESCROW));
        // The nested call had no reason of its own, so it is another mod's deposit, paid by the Reserve.
        assertTrue(types(BOB).contains(TransactionType.SIBLING_PAYOUT));
        assertFalse(types(BOB).contains(TransactionType.MARKET_ESCROW));
    }

    @Test void anUntypedCallAfterATypedOneUsesTheDefaultReason() {
        assertEquals(LedgerResult.APPLIED, provider.payFromReserve(BudgetKind.UNBUDGETED, ALICE, 5L,
                TransactionType.CREDIT_CHIP_REDEMPTION));
        ARMED.set(false);
        assertTrue(CurrencyApi.withdraw(ALICE, NeroEconomyCommon.CREDITS, 2L));
        assertTrue(types(ALICE).contains(TransactionType.CREDIT_CHIP_REDEMPTION));
        assertTrue(types(ALICE).contains(TransactionType.SIBLING_CHARGE));
        assertEquals(1L, types(ALICE).stream().filter(type -> type == TransactionType.CREDIT_CHIP_REDEMPTION).count());
    }

    @Test void aMissingReasonOrAnUnboundProviderIsRefusedWithoutThrowing() {
        assertFalse(provider.depositTyped(ALICE, NeroEconomyCommon.CREDITS, 5L, null));
        ServerCurrencyProvider unbound = new ServerCurrencyProvider();
        CurrencyApi.setProvider(unbound);
        assertFalse(unbound.depositTyped(ALICE, NeroEconomyCommon.CREDITS, 5L, TransactionType.ADMIN_GRANT));
        assertFalse(unbound.withdrawTyped(ALICE, NeroEconomyCommon.CREDITS, 5L, TransactionType.ADMIN_TAKE));
        assertFalse(unbound.transferTyped(ALICE, BOB, NeroEconomyCommon.CREDITS, 5L, TransactionType.PLAYER_PAYMENT));
        assertFalse(unbound.clockBound());
        assertFalse(unbound.reserveChipRedemption(ALICE, 5));
    }

    @Test void providerErasureHookIsANoOpBecauseTheDirectEraserOwnsTheLedgerPass() {
        ARMED.set(false);
        assertTrue(provider.depositTyped(ALICE, NeroEconomyCommon.CREDITS, 5L, TransactionType.ADMIN_GRANT));
        provider.forgetPlayer(ALICE);
        assertEquals(105L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertFalse(data.tombstoned(ALICE));
        // The store's own hook, which the direct eraser calls, is the one that erases.
        data.forgetPlayer(ALICE);
        assertTrue(data.tombstoned(ALICE));
    }

    @Test void nowIsTheLedgerClockWhenBoundAndTheWallClockOtherwise() {
        JsonObject saved = new JsonObject();
        saved.addProperty("clock_offset", 3_600_000L);
        saved.addProperty("clock_last_wall", System.currentTimeMillis());
        EconomySavedData offset = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        provider.bindForTest(offset);
        assertTrue(provider.clockBound());
        long ahead = provider.now() - System.currentTimeMillis();
        assertTrue(ahead > 3_500_000L && ahead < 3_700_000L, "economy time should carry the persisted offset");
        long drift = Math.abs(new ServerCurrencyProvider().now() - System.currentTimeMillis());
        assertTrue(drift < 60_000L, "an unbound provider falls back to the wall clock");
    }

    @Test void aPayoutCanNeverBeMintedThroughTheTypedCalls() {
        ARMED.set(false);
        long supply = data.totalBalancesForTest();
        for (TransactionType type : TransactionType.values()) {
            if (type.mints()) continue;
            assertFalse(provider.depositTyped(ALICE, NeroEconomyCommon.CREDITS, 5L, type), type.name());
        }
        for (TransactionType type : TransactionType.values()) {
            if (type.burns()) continue;
            assertFalse(provider.withdrawTyped(ALICE, NeroEconomyCommon.CREDITS, 5L, type), type.name());
        }
        assertEquals(supply, data.totalBalancesForTest());
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
    }

    @Test void aPlanIsOneOperationAndReportsTheLedgerOutcomeWhenTheListenerThrows() {
        PlayerAccountId alice = new PlayerAccountId(ALICE);
        PlayerAccountId bob = new PlayerAccountId(BOB);
        // A shop sale: Bob receives the price, pays the fee out of it, and the buyer is told once.
        LedgerPlan sale = LedgerPlan.builder().move(alice, bob, 50L, TransactionType.SHOP_PURCHASE)
                .move(bob, SystemAccountId.RESERVE, 1L, TransactionType.FEE_MARKET).build();
        long reserve = data.balance(SystemAccountId.RESERVE);
        assertEquals(LedgerResult.APPLIED, provider.execute(sale));
        assertEquals(50L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(149L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(reserve + 1L, data.balance(SystemAccountId.RESERVE));
        assertTrue(FIRED.get() >= 1);
        // The same sale again cannot be paid for: nothing moves, not even the fee leg.
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, provider.execute(LedgerPlan.builder()
                .move(alice, bob, 51L, TransactionType.SHOP_PURCHASE)
                .move(bob, SystemAccountId.RESERVE, 1L, TransactionType.FEE_MARKET).build()));
        assertEquals(50L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(149L, data.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(reserve + 1L, data.balance(SystemAccountId.RESERVE));
        assertTrue(data.supplyBalanced());
    }

    @Test void escrowAndItsCollectionAreTransfersNotAMintAndABurn() {
        ARMED.set(false);
        PlayerAccountId alice = new PlayerAccountId(ALICE);
        long supply = data.totalBalancesForTest();
        assertEquals(LedgerResult.APPLIED, provider.move(alice, SystemAccountId.ESCROW, 30L, TransactionType.MARKET_ESCROW));
        assertEquals(30L, data.balance(SystemAccountId.ESCROW));
        assertEquals(supply, data.totalBalancesForTest());
        assertEquals(LedgerResult.APPLIED, provider.move(SystemAccountId.ESCROW, alice, 30L, TransactionType.ESCROW_REFUND));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        // One row each way, both naming the escrow account and neither a mint.
        List<TransactionRecord> rows = data.history(ALICE, 100);
        assertTrue(rows.stream().anyMatch(row -> row.type() == TransactionType.MARKET_ESCROW
                && row.destinationAccount().equals("system:escrow")));
        assertTrue(rows.stream().anyMatch(row -> row.type() == TransactionType.ESCROW_REFUND
                && row.sourceAccount().equals("system:escrow")));
        assertEquals(0L, data.supplyForTest().mintedByReason().getOrDefault(TransactionType.ESCROW_REFUND, 0L));
    }

    @Test void aPlanThatWouldChangeMoreThanOnePayerOrPayeeIsRefused() {
        ARMED.set(false);
        UUID carol = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        Ledgers.open(data, carol);
        LedgerPlan twoPayers = LedgerPlan.builder()
                .move(new PlayerAccountId(ALICE), SystemAccountId.ESCROW, 1L, TransactionType.MARKET_ESCROW)
                .move(new PlayerAccountId(BOB), SystemAccountId.ESCROW, 1L, TransactionType.MARKET_ESCROW).build();
        assertEquals(LedgerResult.INVALID, provider.execute(twoPayers));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        // Between system accounts only, a plan needs no player and is applied directly.
        assertEquals(LedgerResult.APPLIED, provider.move(SystemAccountId.RESERVE, SystemAccountId.ESCROW, 7L,
                TransactionType.MARKET_ESCROW));
        assertEquals(7L, data.balance(SystemAccountId.ESCROW));
    }

    @Test void theSiblingBudgetBoundsWhatOtherModsCanDrawInADay() {
        ARMED.set(false);
        // The default siblingDailyBudget is 5000 credits.
        assertTrue(CurrencyApi.deposit(ALICE, NeroEconomyCommon.CREDITS, 4_999L));
        assertFalse(CurrencyApi.deposit(BOB, NeroEconomyCommon.CREDITS, 2L));
        assertTrue(CurrencyApi.deposit(BOB, NeroEconomyCommon.CREDITS, 1L));
        assertFalse(CurrencyApi.deposit(BOB, NeroEconomyCommon.CREDITS, 1L));
        assertEquals(5_000L, data.budgetUsed(BudgetKind.SIBLING));
        assertEquals(0L, data.budgetRemaining(BudgetKind.SIBLING));
        // A charge is a transfer into the Reserve and gives no budget back.
        assertTrue(CurrencyApi.withdraw(ALICE, NeroEconomyCommon.CREDITS, 4_000L));
        assertFalse(CurrencyApi.deposit(ALICE, NeroEconomyCommon.CREDITS, 1L));
        assertTrue(data.supplyBalanced());
    }

    @Test void aFrozenEconomyRefusesEveryCallAndStillAnswersReads() {
        ARMED.set(false);
        EconomyHealth.freeze();
        try {
            assertFalse(CurrencyApi.deposit(ALICE, NeroEconomyCommon.CREDITS, 1L));
            assertFalse(CurrencyApi.withdraw(ALICE, NeroEconomyCommon.CREDITS, 1L));
            assertFalse(CurrencyApi.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 1L));
            assertFalse(provider.depositTyped(ALICE, NeroEconomyCommon.CREDITS, 1L, TransactionType.ADMIN_GRANT));
            assertFalse(provider.withdrawTyped(ALICE, NeroEconomyCommon.CREDITS, 1L, TransactionType.ADMIN_TAKE));
            assertEquals(LedgerResult.FROZEN, provider.move(new PlayerAccountId(ALICE), SystemAccountId.ESCROW, 1L,
                    TransactionType.MARKET_ESCROW));
            assertEquals(LedgerResult.FROZEN, provider.payFromReserve(BudgetKind.UNBUDGETED, ALICE, 1L,
                    TransactionType.CREDIT_CHIP_REDEMPTION));
            assertEquals(100L, CurrencyApi.getBalance(ALICE, NeroEconomyCommon.CREDITS));
            assertEquals(100L, CurrencyApi.getBalance(BOB, NeroEconomyCommon.CREDITS));
        } finally {
            EconomyHealth.clear();
        }
        assertTrue(CurrencyApi.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 1L));
    }

    private List<TransactionType> types(UUID player) {
        return data.history(player, 100).stream().map(TransactionRecord::type).toList();
    }
}
