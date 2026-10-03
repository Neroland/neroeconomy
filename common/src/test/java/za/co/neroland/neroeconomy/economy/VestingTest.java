package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * The starting-balance lock. The config is at its defaults here: a 100-credit starting balance that
 * vests after 30 minutes of play.
 */
class VestingTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PlayerAccountId ALICE_ACCOUNT = new PlayerAccountId(ALICE);
    private static final PlayerAccountId BOB_ACCOUNT = new PlayerAccountId(BOB);

    @Test void aNewAccountsStartingBalanceIsLocked() {
        EconomySavedData data = Ledgers.seeded();
        // Before the account is opened, a read already reports what will be locked.
        assertEquals(100L, data.locked(ALICE));
        assertEquals(0L, data.spendable(ALICE));
        Ledgers.openLocked(data, ALICE);
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(100L, data.locked(ALICE));
        assertEquals(0L, data.spendable(ALICE));
        assertTrue(data.unvested(ALICE));
        assertTrue(data.hasUnvested());
        assertTrue(data.lockBlocks(ALICE, 1L));
        assertFalse(data.lockBlocks(ALICE, 101L));
        assertFalse(data.lockBlocks(ALICE, 0L));
    }

    @Test void lockedCreditsCannotBePaidOfferedOrBidToAnotherPlayer() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.openLocked(data, ALICE);
        Ledgers.open(data, BOB);
        // A payment, by either entry point.
        assertFalse(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 1L));
        assertEquals(LedgerResult.FUNDS_LOCKED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, BOB_ACCOUNT, 1L,
                TransactionType.SHOP_PURCHASE)));
        // Escrow is credits promised to somebody else: a buy offer, a bid, a job payout, a board purchase.
        for (TransactionType type : new TransactionType[] {TransactionType.MARKET_ESCROW, TransactionType.AUCTION_BID,
                TransactionType.MARKET_PURCHASE})
            assertEquals(LedgerResult.FUNDS_LOCKED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.ESCROW,
                    1L, type)), type.name());
        // Nothing moved and nothing was unlocked by trying.
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(100L, data.locked(ALICE));
        assertEquals(0L, data.balance(SystemAccountId.ESCROW));
        // More than the whole balance is a plain shortage, not a lock.
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, data.apply(LedgerPlan.of(ALICE_ACCOUNT, BOB_ACCOUNT, 101L,
                TransactionType.PLAYER_PAYMENT)));
    }

    @Test void lockedCreditsStillPayFeesAndSystemCounterparties() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.openLocked(data, ALICE);
        long reserve = data.balance(SystemAccountId.RESERVE);
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.RESERVE, 5L,
                TransactionType.FEE_LISTING)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.EXCHANGE, 20L,
                TransactionType.FEE_EXCHANGE)));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.house("ironworks"),
                10L, TransactionType.SHOP_PURCHASE)));
        // Another mod's charge is a system counterparty too.
        assertTrue(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 15L));
        assertEquals(50L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(reserve + 20L, data.balance(SystemAccountId.RESERVE));
        // What was spent came out of the locked part, so the lock shrank with the balance.
        assertEquals(50L, data.locked(ALICE));
        assertEquals(0L, data.spendable(ALICE));
        // A leg that is explicitly system-facing may pass through escrow on locked credits.
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.builder().moveAllowingLocked(ALICE_ACCOUNT,
                SystemAccountId.ESCROW, 10L, TransactionType.MARKET_ESCROW).build()));
        assertEquals(40L, data.locked(ALICE));
        assertTrue(data.supplyBalanced());
    }

    @Test void whatAnUnvestedAccountEarnsIsItsOwnToSpend() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.openLocked(data, ALICE);
        Ledgers.open(data, BOB);
        assertTrue(data.transfer(BOB, ALICE, NeroEconomyCommon.CREDITS, 40L));
        assertEquals(140L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(100L, data.locked(ALICE));
        assertEquals(40L, data.spendable(ALICE));
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 40L));
        assertFalse(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 1L));
        // A fee comes out of the locked part first, leaving earned credits for players.
        assertTrue(data.transfer(BOB, ALICE, NeroEconomyCommon.CREDITS, 30L));
        assertEquals(LedgerResult.APPLIED, data.apply(LedgerPlan.of(ALICE_ACCOUNT, SystemAccountId.RESERVE, 25L,
                TransactionType.FEE_MARKET)));
        assertEquals(75L, data.locked(ALICE));
        assertEquals(30L, data.spendable(ALICE));
        // One plan with both kinds of leg: the escrow needs unlocked credits, the fee does not.
        LedgerPlan offer = LedgerPlan.builder()
                .move(ALICE_ACCOUNT, SystemAccountId.ESCROW, 30L, TransactionType.MARKET_ESCROW)
                .move(ALICE_ACCOUNT, SystemAccountId.RESERVE, 1L, TransactionType.FEE_LISTING).build();
        assertEquals(LedgerResult.APPLIED, data.apply(offer));
        assertEquals(74L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(74L, data.locked(ALICE));
        LedgerPlan tooMuch = LedgerPlan.builder()
                .move(ALICE_ACCOUNT, SystemAccountId.ESCROW, 1L, TransactionType.MARKET_ESCROW)
                .move(ALICE_ACCOUNT, SystemAccountId.RESERVE, 1L, TransactionType.FEE_LISTING).build();
        assertEquals(LedgerResult.FUNDS_LOCKED, data.apply(tooMuch));
        assertEquals(74L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
    }

    @Test void vestingEndsTheLock() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.openLocked(data, ALICE);
        Ledgers.open(data, BOB);
        assertFalse(EconomySavedData.vestingReached(29L));
        assertTrue(EconomySavedData.vestingReached(30L));
        assertTrue(data.markVested(ALICE));
        assertFalse(data.markVested(ALICE));
        assertFalse(data.unvested(ALICE));
        assertFalse(data.hasUnvested());
        assertEquals(0L, data.locked(ALICE));
        assertEquals(100L, data.spendable(ALICE));
        assertTrue(data.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 100L));
        // Marking an account nobody has opened does nothing; its grant is locked when it opens.
        UUID carol = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertFalse(data.markVested(carol));
        Ledgers.openLocked(data, carol);
        assertTrue(data.unvested(carol));
    }

    @Test void anOperatorTakingCreditsShrinksTheLockWithTheBalance() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.openLocked(data, ALICE);
        assertTrue(data.withdraw(ALICE, NeroEconomyCommon.CREDITS, 70L, TransactionType.ADMIN_TAKE));
        assertEquals(30L, data.locked(ALICE));
        // A grant on top is not part of the starting balance, so it is spendable at once.
        assertTrue(data.deposit(ALICE, NeroEconomyCommon.CREDITS, 500L, TransactionType.ADMIN_GRANT));
        assertEquals(30L, data.locked(ALICE));
        assertEquals(500L, data.spendable(ALICE));
        assertTrue(data.setBalance(ALICE, 10L, TransactionType.ADMIN_SET));
        assertEquals(10L, data.locked(ALICE));
        assertEquals(0L, data.spendable(ALICE));
    }

    @Test void theLockIsPersistedExportedAndErasedWithTheAccount() {
        EconomySavedData data = Ledgers.seeded();
        Ledgers.openLocked(data, ALICE);
        Ledgers.open(data, BOB);
        var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(100L, restored.locked(ALICE));
        assertTrue(restored.unvested(ALICE));
        assertEquals(0L, restored.locked(BOB));
        assertFalse(restored.unvested(BOB));
        assertFalse(restored.transfer(ALICE, BOB, NeroEconomyCommon.CREDITS, 1L));
        PlayerLedgerExport export = restored.export(ALICE);
        assertEquals(100L, export.lockedStartingBalance());
        assertFalse(export.vested());
        assertTrue(restored.export(BOB).vested());
        restored.forgetPlayer(ALICE);
        assertEquals(0L, restored.locked(ALICE));
        assertFalse(restored.unvested(ALICE));
        assertFalse(restored.hasUnvested());
        var again = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, restored).getOrThrow().getAsJsonObject();
        assertFalse(again.has("locked"));
        assertFalse(again.has("unvested"));
        // An erased account that returns gets no second grant, so there is nothing to lock.
        Ledgers.openLocked(restored, ALICE);
        assertEquals(0L, restored.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertFalse(restored.unvested(ALICE));
    }

    @Test void anAccountFromBeforeVestingExistedIsNotLocked() {
        // A 0.2 ledger: a balance and an initialised account, and none of the newer fields.
        var old = new com.google.gson.JsonObject();
        var balances = new com.google.gson.JsonArray();
        var row = new com.google.gson.JsonObject();
        row.addProperty("player", ALICE.toString());
        row.addProperty("balance", 100L);
        balances.add(row);
        old.add("balances", balances);
        var initialized = new com.google.gson.JsonArray();
        initialized.add("player:" + ALICE);
        old.add("initialized", initialized);
        EconomySavedData data = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(100L, data.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(0L, data.locked(ALICE));
        assertFalse(data.unvested(ALICE));
        assertEquals(100L, data.spendable(ALICE));
    }
}
