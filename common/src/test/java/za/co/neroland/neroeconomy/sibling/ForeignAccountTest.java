package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.EconomyResult;
import za.co.neroland.neroeconomy.api.ForeignAccountId;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;

/** Other mods' accounts: their kind, their moves, their place in the supply and in the saved ledger. */
class ForeignAccountTest {
    private static final Set<String> REASONS = Set.of("treasury");
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final ForeignAccountId TREASURY = new ForeignAccountId("nerofactions", "faction/ironclad");

    private EconomyStore store;
    private EconomySavedData ledger;
    private CurrencyProvider previous;

    @BeforeEach void bind() {
        store = new EconomyStore();
        ledger = store.ledger();
        ledger.seedReserve(250_000L);
        ledger.withdraw(P, NeroEconomyCommon.CREDITS, 0L);
        ledger.markVested(P);
        previous = CurrencyApi.provider();
        StoreTesting.bindProvider(ledger);
        CurrencyApi.setProvider(NeroEconomyCommon.CURRENCY_PROVIDER);
        SiblingTesting.rateClock(() -> System.nanoTime() * 1_000L);
    }

    @AfterEach void unbind() {
        CurrencyApi.setProvider(previous);
        StoreTesting.bindProvider(null);
        SiblingTesting.rateClock(null);
    }

    @Test void onlyAnotherModsIdIsAKind() {
        assertThrows(IllegalArgumentException.class, () -> new ForeignAccountId("player", "x"));
        assertThrows(IllegalArgumentException.class, () -> new ForeignAccountId("system", "reserve"));
        assertThrows(IllegalArgumentException.class, () -> new ForeignAccountId("neroeconomy", "x"));
        assertThrows(IllegalArgumentException.class, () -> new ForeignAccountId("nerofactions", "Iron Clad"));
        assertEquals("nerofactions:faction/ironclad", TREASURY.stableKey());
    }

    @Test void aCallerMovesCreditsInAndOutOfItsOwnAccountsOnly() {
        assertEquals(EconomyResult.APPLIED, SiblingPayments.deposit(store, "nerofactions", REASONS, P, TREASURY, 60L,
                "treasury", null));
        assertEquals(40L, ledger.getBalance(P, NeroEconomyCommon.CREDITS));
        assertEquals(60L, SiblingPayments.balance(store, "nerofactions", TREASURY));
        assertEquals(0L, SiblingPayments.balance(store, "neroquests", TREASURY), "another caller cannot read it");
        assertEquals(EconomyResult.INVALID, SiblingPayments.withdraw(store, "neroquests", REASONS, TREASURY, P, 10L,
                "treasury", null), "another caller cannot touch it");
        assertEquals(EconomyResult.INSUFFICIENT_FUNDS, SiblingPayments.withdraw(store, "nerofactions", REASONS, TREASURY,
                P, 61L, "treasury", null));
        assertEquals(EconomyResult.APPLIED, SiblingPayments.withdraw(store, "nerofactions", REASONS, TREASURY, P, 25L,
                "treasury", null));
        assertEquals(65L, ledger.getBalance(P, NeroEconomyCommon.CREDITS));
        assertEquals(Map.of("nerofactions", 35L), ledger.foreignBalances());
        assertTrue(store.checkInvariants(), "invariant A counts the foreign account");
    }

    @Test void unvestedStartingCreditsCannotGoIntoAnotherModsAccount() {
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
        ledger.withdraw(fresh, NeroEconomyCommon.CREDITS, 0L);
        assertEquals(EconomyResult.FUNDS_LOCKED, SiblingPayments.deposit(store, "nerofactions", REASONS, fresh, TREASURY,
                10L, "treasury", null));
        assertEquals(0L, SiblingPayments.balance(store, "nerofactions", TREASURY));
    }

    @Test void aForeignAccountIsSavedAndKeptWhenItsModIsGone() {
        assertEquals(EconomyResult.APPLIED, SiblingPayments.deposit(store, "nerofactions", REASONS, P, TREASURY, 50L,
                "treasury", null));
        EconomyStore reloaded = StoreTesting.reload(store);
        // Nothing claims "nerofactions" in this test: the account is kept all the same.
        assertFalse(CallerRegistry.GAME.claimed("nerofactions"));
        assertEquals(50L, reloaded.ledger().getBalance(TREASURY, NeroEconomyCommon.CREDITS));
        assertEquals(Map.of("nerofactions", 50L), reloaded.ledger().foreignBalances());
        assertTrue(reloaded.checkInvariants());
    }
}
