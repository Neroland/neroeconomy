package za.co.neroland.neroeconomy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.TransactionType;

class CreditClaimsTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void claimsAreKeptPerReasonAndSummedPerPlayer() {
        CreditClaims claims = new CreditClaims();
        assertTrue(claims.add(ALICE, TransactionType.MARKET_PAYOUT, 40L));
        assertTrue(claims.add(ALICE, TransactionType.MARKET_PAYOUT, 10L));
        assertTrue(claims.add(ALICE, TransactionType.ESCROW_REFUND, 7L));
        assertEquals(57L, claims.total(ALICE));
        assertEquals(Map.of(TransactionType.MARKET_PAYOUT, 50L, TransactionType.ESCROW_REFUND, 7L), claims.buckets(ALICE));
        assertEquals(0L, claims.total(BOB));
        assertTrue(claims.buckets(BOB).isEmpty());
    }

    @Test void invalidAndOverflowingClaimsAreRefusedAndChangeNothing() {
        CreditClaims claims = new CreditClaims();
        assertFalse(claims.add(null, TransactionType.MARKET_PAYOUT, 1L));
        assertFalse(claims.add(ALICE, null, 1L));
        assertFalse(claims.add(ALICE, TransactionType.MARKET_PAYOUT, 0L));
        assertFalse(claims.add(ALICE, TransactionType.MARKET_PAYOUT, -5L));
        assertTrue(claims.add(ALICE, TransactionType.MARKET_PAYOUT, Long.MAX_VALUE - 1L));
        // The total across every reason is what must fit, not each reason on its own.
        assertFalse(claims.canAccept(ALICE, 2L));
        assertFalse(claims.add(ALICE, TransactionType.ESCROW_REFUND, 2L));
        assertTrue(claims.add(ALICE, TransactionType.ESCROW_REFUND, 1L));
        assertEquals(Long.MAX_VALUE, claims.total(ALICE));
    }

    @Test void settlingOneReasonLeavesTheOthersClaimable() {
        CreditClaims claims = new CreditClaims();
        claims.add(ALICE, TransactionType.MARKET_PAYOUT, 40L);
        claims.add(ALICE, TransactionType.ESCROW_REFUND, 7L);
        assertEquals(40L, claims.settle(ALICE, TransactionType.MARKET_PAYOUT));
        assertEquals(0L, claims.settle(ALICE, TransactionType.MARKET_PAYOUT));
        assertEquals(7L, claims.total(ALICE));
        assertEquals(7L, claims.settle(ALICE, TransactionType.ESCROW_REFUND));
        assertFalse(claims.snapshot().containsKey(ALICE));
        assertEquals(0L, claims.settle(BOB, TransactionType.MARKET_PAYOUT));
    }

    @Test void erasureDropsEverythingOwedToOnePlayerAndToleratesUnknownOnes() {
        CreditClaims claims = new CreditClaims();
        claims.add(ALICE, TransactionType.MARKET_PAYOUT, 40L);
        claims.add(BOB, TransactionType.AUCTION_PAYOUT, 9L);
        assertTrue(claims.forget(ALICE));
        assertFalse(claims.forget(ALICE));
        assertFalse(claims.forget(null));
        assertEquals(0L, claims.total(ALICE));
        assertEquals(9L, claims.total(BOB));
    }

    @Test void snapshotAndBucketsAreCopiesNotViews() {
        CreditClaims claims = new CreditClaims();
        claims.add(ALICE, TransactionType.MARKET_PAYOUT, 40L);
        claims.snapshot().get(ALICE).put(TransactionType.MARKET_PAYOUT, 1L);
        claims.buckets(ALICE).clear();
        assertEquals(40L, claims.total(ALICE));
    }
}
