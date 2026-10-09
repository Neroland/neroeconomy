package za.co.neroland.neroeconomy.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * O1: every operator credit mutation is counted in the admin section, under its own action, once,
 * and only when it changed a balance, wherever it entered: the ledger itself, or the public typed
 * calls of the currency provider that other code can reach without {@code AdminOps}.
 */
class AdminMutationCountTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private EconomyStore store;
    private EconomySavedData ledger;
    private CurrencyProvider previous;

    @BeforeEach void openOneAccount() {
        store = new EconomyStore();
        ledger = store.ledger();
        ledger.seedReserve(10_000L);
        ledger.withdraw(PLAYER, NeroEconomyCommon.CREDITS, 0L);
        previous = CurrencyApi.provider();
        StoreTesting.bindProvider(ledger);
        CurrencyApi.setProvider(NeroEconomyCommon.CURRENCY_PROVIDER);
    }

    @AfterEach void unbind() {
        CurrencyApi.setProvider(previous);
        StoreTesting.bindProvider(null);
    }

    private long count(AdminSection.Action action) { return store.admin().count(action); }

    @Test void aMutationThroughTheLedgerIsCountedOnceAndOnlyWhenItSucceeds() {
        assertTrue(ledger.deposit(PLAYER, NeroEconomyCommon.CREDITS, 50L, TransactionType.ADMIN_GRANT));
        assertEquals(1L, count(AdminSection.Action.GRANT));
        assertFalse(ledger.withdraw(PLAYER, NeroEconomyCommon.CREDITS, 1_000_000L, TransactionType.ADMIN_TAKE));
        assertEquals(0L, count(AdminSection.Action.TAKE), "a refused take is not counted");
        assertTrue(ledger.withdraw(PLAYER, NeroEconomyCommon.CREDITS, 10L, TransactionType.ADMIN_TAKE));
        assertEquals(1L, count(AdminSection.Action.TAKE));
        assertTrue(ledger.setBalance(PLAYER, 500L, TransactionType.ADMIN_SET));
        assertEquals(1L, count(AdminSection.Action.SET));
        assertTrue(ledger.deposit(PLAYER, NeroEconomyCommon.CREDITS, 0L, TransactionType.ADMIN_GRANT));
        assertEquals(1L, count(AdminSection.Action.GRANT), "nothing changed, nothing counted");
        assertFalse(ledger.deposit(PLAYER, NeroEconomyCommon.CREDITS, Long.MAX_VALUE, TransactionType.ADMIN_GRANT));
        assertEquals(1L, count(AdminSection.Action.GRANT), "an overflowing grant is refused and not counted");
        assertTrue(store.checkInvariants());
    }

    @Test void theProvidersPublicTypedCallsAreCountedToo() {
        assertTrue(NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(PLAYER, NeroEconomyCommon.CREDITS, 25L,
                TransactionType.ADMIN_GRANT));
        assertTrue(NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(PLAYER, NeroEconomyCommon.CREDITS, 5L,
                TransactionType.ADMIN_TAKE));
        assertTrue(NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(PLAYER, NeroEconomyCommon.CREDITS, 1L,
                TransactionType.ADMIN_SET));
        assertEquals(1L, count(AdminSection.Action.GRANT));
        assertEquals(1L, count(AdminSection.Action.TAKE));
        assertEquals(1L, count(AdminSection.Action.SET));
        // A typed call under a reason that does not mint is refused by the ledger, and counts as nothing.
        assertFalse(NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(PLAYER, NeroEconomyCommon.CREDITS, 25L,
                TransactionType.CREDIT_CHIP_REDEMPTION));
        assertFalse(NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(PLAYER, NeroEconomyCommon.CREDITS, 1_000_000L,
                TransactionType.ADMIN_TAKE));
        assertEquals(1L, count(AdminSection.Action.GRANT));
        assertEquals(1L, count(AdminSection.Action.TAKE));
        assertTrue(store.checkInvariants());
    }
}
