package za.co.neroland.neroeconomy.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.direct.TradeSession.CancelReason;
import za.co.neroland.neroeconomy.direct.TradeSession.Change;
import za.co.neroland.neroeconomy.direct.TradeSession.Phase;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The state machine of a direct trade: every change clears both confirmations, the freeze accepts
 * no change, a late packet changes nothing, and request ids go through the economy's one request
 * ledger. No game and no goods here; the swap itself is {@link TradeSwapTest}.
 */
class TradeSessionTest {
    private final TradeWorld world = new TradeWorld();
    private final UUID alice = world.player(1, 1_000L);
    private final UUID bob = world.player(2, 1_000L);
    private final TradeSession session = new TradeSession(TradeWorld.account(alice), TradeWorld.account(bob));
    /** The real request ledger of a real store. */
    private final TradeSession.RequestGate gate = world.store.market()::rememberRequest;
    private long now = 50_000L;

    @AfterEach void done() { world.close(); }

    private static UUID id() { return UUID.randomUUID(); }

    private Change confirm(Side side) { return session.confirm(side, session.revision(), id(), gate, now); }

    private void lock() {
        assertEquals(Change.APPLIED, confirm(Side.FIRST));
        assertEquals(Change.APPLIED, confirm(Side.SECOND));
        assertEquals(Phase.LOCKED, session.phase());
    }

    @Test void aTradeNeedsTwoDifferentPlayers() {
        AccountId account = TradeWorld.account(alice);
        assertThrows(IllegalArgumentException.class, () -> new TradeSession(account, account));
        assertThrows(IllegalArgumentException.class, () -> new TradeSession(account, null));
        assertThrows(IllegalArgumentException.class, () -> new TradeSession(account, SystemAccountId.RESERVE));
        assertEquals(Side.FIRST, session.sideOf(account));
        assertEquals(Side.SECOND, session.sideOf(TradeWorld.account(bob)));
        assertNull(session.sideOf(TradeWorld.account(world.player(3, 0L))));
        assertEquals(Side.SECOND, Side.FIRST.other());
    }

    @Test void everyChangeByEitherSideClearsBothConfirmations() {
        for (Side changer : Side.values()) {
            // A change to the goods.
            assertEquals(Change.APPLIED, confirm(changer.other()));
            int before = session.revision();
            assertEquals(Change.APPLIED, session.goodsChanged(changer));
            assertFalse(session.confirmed(Side.FIRST));
            assertFalse(session.confirmed(Side.SECOND));
            assertEquals(before + 1, session.revision());
            // A change to the credits, by the side that had confirmed and by the other one.
            assertEquals(Change.APPLIED, confirm(changer));
            assertEquals(Change.APPLIED, session.setCredits(changer, 10L + session.revision(), id(), gate));
            assertFalse(session.confirmed(changer));
            assertEquals(Change.APPLIED, confirm(changer));
            assertEquals(Change.APPLIED, session.setCredits(changer.other(), 20L + session.revision(), id(), gate));
            assertFalse(session.confirmed(Side.FIRST));
            assertFalse(session.confirmed(Side.SECOND));
            assertEquals(Phase.OPEN, session.phase());
        }
    }

    @Test void settingTheSameAmountAgainIsNotAChange() {
        assertEquals(Change.APPLIED, session.setCredits(Side.FIRST, 40L, id(), gate));
        assertEquals(Change.APPLIED, confirm(Side.SECOND));
        int revision = session.revision();
        assertEquals(Change.UNCHANGED, session.setCredits(Side.FIRST, 40L, id(), gate));
        assertTrue(session.confirmed(Side.SECOND));
        assertEquals(revision, session.revision());
    }

    @Test void anAmountThatIsNegativeOrTooLargeIsRefusedAndDoesNotUseItsRequestIdUp() {
        UUID request = id();
        assertEquals(Change.INVALID, session.setCredits(Side.FIRST, -1L, request, gate));
        assertEquals(Change.INVALID, session.setCredits(Side.FIRST, TradeSession.MAX_CREDITS + 1L, request, gate));
        assertEquals(Change.INVALID, session.setCredits(Side.FIRST, Long.MIN_VALUE, request, gate));
        assertEquals(Change.INVALID, session.setCredits(null, 1L, request, gate));
        assertEquals(Change.INVALID, session.setCredits(Side.FIRST, 1L, null, gate));
        assertEquals(0L, session.credits(Side.FIRST));
        // The same id is still good for a valid amount.
        assertEquals(Change.APPLIED, session.setCredits(Side.FIRST, TradeSession.MAX_CREDITS, request, gate));
        assertEquals(TradeSession.MAX_CREDITS, session.credits(Side.FIRST));
        assertEquals(0L, session.credits(Side.SECOND));
    }

    @Test void requestIdsAreIdempotentThroughTheSharedRequestLedger() {
        UUID request = id();
        assertEquals(Change.APPLIED, session.setCredits(Side.FIRST, 25L, request, gate));
        // The same packet again, and the same id carrying something else: neither does anything.
        assertEquals(Change.STALE_REQUEST, session.setCredits(Side.FIRST, 25L, request, gate));
        assertEquals(Change.STALE_REQUEST, session.setCredits(Side.FIRST, 999L, request, gate));
        assertEquals(25L, session.credits(Side.FIRST));
        // An id is the player's: the other side may happen to use the same one.
        assertEquals(Change.APPLIED, session.setCredits(Side.SECOND, 5L, request, gate));
        UUID confirming = id();
        assertEquals(Change.APPLIED, session.confirm(Side.FIRST, session.revision(), confirming, gate, now));
        assertEquals(Change.APPLIED, session.withdraw(Side.FIRST));
        // A replay of the confirmation that was taken back does not put it back.
        assertEquals(Change.STALE_REQUEST, session.confirm(Side.FIRST, session.revision(), confirming, gate, now));
        assertFalse(session.confirmed(Side.FIRST));
        // The ids are the store's own record, so they survive a save and a load.
        world.reload();
        TradeSession.RequestGate reloaded = world.store.market()::rememberRequest;
        assertEquals(Change.STALE_REQUEST, session.setCredits(Side.FIRST, 77L, request, reloaded));
        assertEquals(25L, session.credits(Side.FIRST));
    }

    @Test void aConfirmationOfAnOlderRevisionIsIgnored() {
        int seen = session.revision();
        // The other side changes its offer while the confirmation is on its way.
        assertEquals(Change.APPLIED, session.goodsChanged(Side.SECOND));
        UUID request = id();
        assertEquals(Change.STALE_REVISION, session.confirm(Side.FIRST, seen, request, gate, now));
        assertFalse(session.confirmed(Side.FIRST));
        // The id was not used up: the player confirms what they now see with the same click.
        assertEquals(Change.APPLIED, session.confirm(Side.FIRST, session.revision(), request, gate, now));
        assertEquals(Change.UNCHANGED, confirm(Side.FIRST));
        assertEquals(Change.STALE_REVISION, session.confirm(Side.SECOND, session.revision() + 1, id(), gate, now));
    }

    @Test void bothConfirmationsFreezeTheContentsForThreeSeconds() {
        assertEquals(Change.APPLIED, confirm(Side.FIRST));
        assertEquals(Phase.OPEN, session.phase());
        assertEquals(0L, session.lockRemaining(now));
        assertFalse(session.due(now + 10_000L));
        now += 700L;
        assertEquals(Change.APPLIED, confirm(Side.SECOND));
        assertEquals(Phase.LOCKED, session.phase());
        assertFalse(session.editable());
        // The freeze runs from the second confirmation, not the first.
        assertEquals(TradeSession.LOCK_MILLIS, session.lockRemaining(now));
        assertEquals(3_000L, TradeSession.LOCK_MILLIS);
        assertFalse(session.due(now));
        assertFalse(session.due(now + TradeSession.LOCK_MILLIS - 1L));
        assertEquals(1L, session.lockRemaining(now + TradeSession.LOCK_MILLIS - 1L));
        assertTrue(session.due(now + TradeSession.LOCK_MILLIS));
        assertEquals(0L, session.lockRemaining(now + TradeSession.LOCK_MILLIS + 5L));
    }

    @Test void aChangeDuringTheFreezeIsRejected() {
        assertEquals(Change.APPLIED, session.setCredits(Side.FIRST, 30L, id(), gate));
        lock();
        int revision = session.revision();
        for (Side side : Side.values()) {
            assertEquals(Change.LOCKED, session.goodsChanged(side));
            UUID request = id();
            assertEquals(Change.LOCKED, session.setCredits(side, 500L, request, gate));
            assertEquals(Change.LOCKED, session.withdraw(side));
            assertEquals(Change.LOCKED, session.confirm(side, revision, id(), gate, now));
            // A refused change did not use its id up.
            assertTrue(gate.fresh(side == Side.FIRST ? alice : bob, request));
        }
        assertEquals(30L, session.credits(Side.FIRST));
        assertEquals(0L, session.credits(Side.SECOND));
        assertEquals(revision, session.revision());
        assertTrue(session.confirmed(Side.FIRST) && session.confirmed(Side.SECOND));
        assertEquals(Phase.LOCKED, session.phase());
    }

    @Test void eitherSideCanCancelDuringTheFreeze() {
        for (Side side : Side.values()) {
            TradeSession trade = new TradeSession(TradeWorld.account(alice), TradeWorld.account(bob));
            trade.confirm(Side.FIRST, 0, id(), gate, now);
            trade.confirm(Side.SECOND, 0, id(), gate, now);
            assertEquals(Phase.LOCKED, trade.phase());
            assertEquals(side, trade.sideOf(trade.account(side)));
            assertTrue(trade.cancel(CancelReason.CLOSED));
            assertEquals(Phase.CANCELLED, trade.phase());
            assertEquals(CancelReason.CLOSED, trade.cancelReason());
            // The swap that was due is not made.
            assertFalse(trade.due(now + TradeSession.LOCK_MILLIS));
            assertFalse(trade.swapped());
            assertFalse(trade.confirmed(Side.FIRST) || trade.confirmed(Side.SECOND));
        }
    }

    @Test void aLatePacketAfterTheSwapIsIgnored() {
        assertEquals(Change.APPLIED, session.setCredits(Side.SECOND, 12L, id(), gate));
        lock();
        now += TradeSession.LOCK_MILLIS;
        assertTrue(session.due(now));
        assertTrue(session.swapped());
        assertEquals(Phase.DONE, session.phase());
        assertTrue(session.over());
        int revision = session.revision();
        for (Side side : Side.values()) {
            assertEquals(Change.CLOSED, session.goodsChanged(side));
            assertEquals(Change.CLOSED, session.setCredits(side, 1L, id(), gate));
            assertEquals(Change.CLOSED, session.confirm(side, revision, id(), gate, now));
            assertEquals(Change.CLOSED, session.withdraw(side));
        }
        // It cannot be swapped a second time, cancelled, or reopened.
        assertFalse(session.swapped());
        assertFalse(session.swapRefused());
        assertFalse(session.cancel(CancelReason.DISCONNECTED));
        assertFalse(session.due(now + 60_000L));
        assertNull(session.cancelReason());
        assertEquals(12L, session.credits(Side.SECOND));
        assertEquals(revision, session.revision());
        assertEquals(Phase.DONE, session.phase());
    }

    @Test void aLatePacketAfterACancelIsIgnoredAndASecondCancelReturnsNothingTwice() {
        assertTrue(session.cancel(CancelReason.OUT_OF_RANGE));
        assertFalse(session.cancel(CancelReason.CLOSED));
        assertEquals(CancelReason.OUT_OF_RANGE, session.cancelReason());
        assertEquals(Change.CLOSED, session.goodsChanged(Side.FIRST));
        assertEquals(Change.CLOSED, session.setCredits(Side.FIRST, 1L, id(), gate));
        assertEquals(Change.CLOSED, confirm(Side.SECOND));
        assertFalse(session.swapped());
        assertTrue(session.over());
        // A cancel with no reason given is a closed window.
        TradeSession other = new TradeSession(TradeWorld.account(alice), TradeWorld.account(bob));
        assertTrue(other.cancel(null));
        assertEquals(CancelReason.CLOSED, other.cancelReason());
    }

    @Test void aSwapThatCannotBeMadeReopensTheTradeWithBothConfirmationsCleared() {
        lock();
        int revision = session.revision();
        assertTrue(session.swapRefused());
        assertEquals(Phase.OPEN, session.phase());
        assertFalse(session.confirmed(Side.FIRST) || session.confirmed(Side.SECOND));
        assertTrue(session.revision() > revision);
        assertTrue(session.editable());
        // Not frozen any more: nothing is due, and a swap is not accepted until both confirm again.
        assertFalse(session.due(now + 60_000L));
        assertFalse(session.swapped());
        assertFalse(session.swapRefused());
        assertEquals(Change.APPLIED, session.goodsChanged(Side.FIRST));
        lock();
        assertTrue(session.swapped());
    }

    @Test void aSwapIsOnlyEverAcceptedOutOfTheFreeze() {
        assertFalse(session.swapped());
        assertEquals(Change.APPLIED, confirm(Side.FIRST));
        assertFalse(session.swapped());
        assertEquals(Phase.OPEN, session.phase());
    }

    @Test void aConfirmationCanBeTakenBackWhileTheTradeIsOpen() {
        assertEquals(Change.UNCHANGED, session.withdraw(Side.FIRST));
        assertEquals(Change.APPLIED, confirm(Side.FIRST));
        int revision = session.revision();
        assertEquals(Change.APPLIED, session.withdraw(Side.FIRST));
        assertFalse(session.confirmed(Side.FIRST));
        // Taking a confirmation back changes nothing that was offered.
        assertEquals(revision, session.revision());
        assertEquals(Change.APPLIED, confirm(Side.SECOND));
        assertEquals(Phase.OPEN, session.phase());
    }

    @Test void everyCancelReasonHasItsOwnWording() {
        for (CancelReason reason : CancelReason.values())
            assertTrue(reason.translationKey().startsWith("message.neroeconomy.direct_trade.cancelled."));
        assertEquals("message.neroeconomy.direct_trade.cancelled.out_of_range", CancelReason.OUT_OF_RANGE.translationKey());
    }

    @Test void aRandomRunOfPacketsNeverSwapsAnythingButWhatBothSidesConfirmed() {
        Random random = new Random(20_261_003L);
        for (int run = 0; run < 400; run++) {
            TradeSession trade = new TradeSession(TradeWorld.account(alice), TradeWorld.account(bob));
            long clock = 0L;
            // What each side last agreed to: the revision it confirmed.
            int[] agreed = {-1, -1};
            for (int step = 0; step < 60 && !trade.over(); step++) {
                clock += random.nextInt(900);
                Side side = random.nextBoolean() ? Side.FIRST : Side.SECOND;
                switch (random.nextInt(7)) {
                    case 0 -> trade.goodsChanged(side);
                    case 1 -> trade.setCredits(side, random.nextInt(50), id(), gate);
                    case 2, 3 -> {
                        int revision = random.nextInt(6) == 0 ? trade.revision() - 1 : trade.revision();
                        if (trade.confirm(side, revision, id(), gate, clock) == Change.APPLIED)
                            agreed[side.ordinal()] = revision;
                    }
                    case 4 -> trade.withdraw(side);
                    case 5 -> { if (random.nextInt(8) == 0) trade.cancel(CancelReason.CLOSED); }
                    default -> {
                        if (!trade.due(clock)) break;
                        // The swap is made for exactly the revision both confirmed.
                        assertEquals(trade.revision(), agreed[0]);
                        assertEquals(trade.revision(), agreed[1]);
                        assertTrue(trade.confirmed(Side.FIRST) && trade.confirmed(Side.SECOND));
                        if (random.nextInt(4) == 0) assertTrue(trade.swapRefused());
                        else assertTrue(trade.swapped());
                    }
                }
                // Frozen means both confirmed; a confirmation always belongs to the current revision.
                if (trade.phase() == Phase.LOCKED) assertTrue(trade.confirmed(Side.FIRST) && trade.confirmed(Side.SECOND));
                for (Side each : Side.values())
                    if (trade.confirmed(each)) assertEquals(trade.revision(), agreed[each.ordinal()]);
            }
        }
    }
}
