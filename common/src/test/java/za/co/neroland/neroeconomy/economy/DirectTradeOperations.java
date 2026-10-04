package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.direct.TradeEscrowSection;
import za.co.neroland.neroeconomy.direct.TradeSession;
import za.co.neroland.neroeconomy.direct.TradeSession.Change;
import za.co.neroland.neroeconomy.direct.TradeSession.Phase;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.direct.TradeSwap;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.Accounts;

/**
 * The fuzzer's direct-trade operations, against the real trade-escrow section, the real session
 * state machine and the real swap. What a side puts on the table is in the store from the moment
 * it leaves the hand, so the world's checks cover it without a model; an operation only tells the
 * world when goods cross between a hand and the store. The sessions themselves are the world's
 * ({@link FuzzWorld#trades}): nothing saves them, and a restart ends them all.
 */
final class DirectTradeOperations {
    private DirectTradeOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new EscrowIn(), new EscrowOut(), new Credits(), new Swap(), new Cancel());
    }

    private static long balance(FuzzWorld world, AccountId account) {
        return world.ledger().getBalance(Accounts.player(account), NeroEconomyCommon.CREDITS);
    }

    /** The economy's one request ledger, as the session asks it. */
    private static TradeSession.RequestGate gate(FuzzWorld world) {
        return (player, requestId) -> world.store.market().rememberRequest(player, requestId);
    }

    /** Where a side's goods go when they leave the table: the inventory, or the vault. */
    private static GoodsReceiver receiver(FuzzWorld world, AccountId account) {
        return world.random.nextBoolean() ? BookOperations.inventory(world)
                : VaultGoods.receiver(world.store, world.env, account);
    }

    /** One of the trades that are open, or null when none is. */
    private static TradeSession anyTrade(FuzzWorld world) {
        return world.trades.isEmpty() ? null : world.trades.get(world.random.nextInt(world.trades.size()));
    }

    /** The trade a player is in; one is opened with somebody free when they are in none. */
    private static TradeSession sessionFor(FuzzWorld world, AccountId account) {
        TradeSession session = world.tradeOf(account);
        if (session != null) return session;
        AccountId partner = FuzzWorld.account(world.player());
        // Nobody trades with themselves, and nobody is in two trades at once.
        if (partner.equals(account) || world.tradeOf(partner) != null) return null;
        session = new TradeSession(account, partner);
        world.trades.add(session);
        return session;
    }

    /** A side puts a stack from its hand on the table: the goods are in escrow from that moment. */
    static final class EscrowIn implements FuzzOperation {
        @Override public String name() { return "trade_escrow_in"; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            TradeSession session = sessionFor(world, account);
            if (session == null) return 0L;
            TradeEscrowSection escrow = world.store.tradeEscrow();
            Lot wanted = world.lot();
            // One slot holds one stack, and a hand gives only what it holds.
            int count = (int) Math.min(Math.min(wanted.count(), FuzzWorld.LIMIT.limit(wanted.item(), wanted.payload())),
                    world.outside(wanted.key()));
            if (count <= 0) return 0L;
            List<Lot> before = escrow.held(account);
            List<Lot> next = new ArrayList<>(before);
            next.add(wanted.withCount(count));
            if (next.size() > TradeEscrowSection.MAX_STACKS) {
                // The table is full: refused, with nothing changed.
                assertFalse(escrow.replace(account, next));
                assertEquals(before, escrow.held(account));
                return 0L;
            }
            if (session.goodsChanged(session.sideOf(account)) != Change.APPLIED) {
                // The contents are frozen: the stack stays in the hand.
                assertEquals(Phase.LOCKED, session.phase());
                return 0L;
            }
            assertTrue(escrow.replace(account, next));
            world.intoStore(wanted.key(), count);
            assertFalse(session.confirmed(Side.FIRST) || session.confirmed(Side.SECOND));
            return count;
        }
    }

    /** A side takes a stack back off the table into its hand. */
    static final class EscrowOut implements FuzzOperation {
        @Override public String name() { return "trade_escrow_out"; }
        @Override public int weight() { return 15; }
        @Override public long run(FuzzWorld world) {
            TradeSession session = anyTrade(world);
            if (session == null) return 0L;
            AccountId account = session.account(world.random.nextBoolean() ? Side.FIRST : Side.SECOND);
            TradeEscrowSection escrow = world.store.tradeEscrow();
            List<Lot> before = escrow.held(account);
            if (before.isEmpty()) return 0L;
            if (session.goodsChanged(session.sideOf(account)) != Change.APPLIED) {
                assertEquals(Phase.LOCKED, session.phase());
                return 0L;
            }
            List<Lot> next = new ArrayList<>(before);
            Lot taken = next.remove(world.random.nextInt(next.size()));
            assertTrue(escrow.replace(account, next));
            world.outOfStore(taken.key(), taken.count());
            return taken.count();
        }
    }

    /** A side sets the credits it gives: sometimes an amount no trade accepts, sometimes the same request twice. */
    static final class Credits implements FuzzOperation {
        @Override public String name() { return "trade_credits"; }
        @Override public int weight() { return 15; }
        @Override public long run(FuzzWorld world) {
            TradeSession session = anyTrade(world);
            if (session == null) return 0L;
            Side side = world.random.nextBoolean() ? Side.FIRST : Side.SECOND;
            long amount = world.amount();
            long before = session.credits(side);
            UUID request = BookOperations.id(world);
            Change change = session.setCredits(side, amount, request, gate(world));
            if (change != Change.APPLIED) {
                assertEquals(before, session.credits(side));
                return 0L;
            }
            assertEquals(amount, session.credits(side));
            // The same request again is not acted on twice.
            assertEquals(Change.STALE_REQUEST, session.setCredits(side, amount + 1L, request, gate(world)));
            assertEquals(amount, session.credits(side));
            return 1L + amount;
        }
    }

    /**
     * Both sides confirm and the contents freeze; picked again once the freeze has run out, the
     * swap is made in one operation — or refused, with nothing moved and the trade open again.
     */
    static final class Swap implements FuzzOperation {
        @Override public String name() { return "trade_swap"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            TradeSession session = anyTrade(world);
            if (session == null) return 0L;
            AccountId first = session.account(Side.FIRST);
            AccountId second = session.account(Side.SECOND);
            TradeEscrowSection escrow = world.store.tradeEscrow();
            if (session.phase() == Phase.OPEN) {
                long now = world.ledger().now();
                int revision = session.revision();
                // A confirmation of what the trade looked like before its last change is ignored.
                assertEquals(Change.STALE_REVISION, session.confirm(Side.FIRST, revision - 1, BookOperations.id(world),
                        gate(world), now));
                if (!session.confirmed(Side.FIRST))
                    assertEquals(Change.APPLIED, session.confirm(Side.FIRST, revision, BookOperations.id(world), gate(world), now));
                if (!session.confirmed(Side.SECOND))
                    assertEquals(Change.APPLIED, session.confirm(Side.SECOND, revision, BookOperations.id(world), gate(world), now));
                assertEquals(Phase.LOCKED, session.phase());
                // Frozen: nothing may change, and the swap is not due yet.
                assertFalse(session.due(now));
                assertEquals(Change.LOCKED, session.goodsChanged(Side.SECOND));
                assertEquals(Change.LOCKED, session.withdraw(Side.FIRST));
                return 1L;
            }
            assertEquals(Phase.LOCKED, session.phase());
            // The freeze runs out.
            world.wall += TradeSession.LOCK_MILLIS;
            assertTrue(session.due(world.ledger().now()));
            List<Lot> firstGives = escrow.held(first);
            List<Lot> secondGives = escrow.held(second);
            long firstBefore = balance(world, first);
            long secondBefore = balance(world, second);
            if (world.random.nextInt(12) == 0 && !firstGives.isEmpty()) {
                // The trade says something other than what is in escrow: nobody agreed to that.
                assertEquals(TradeSwap.Result.ESCROW_CHANGED, TradeSwap.execute(world.store, world.env, first,
                        session.credits(Side.FIRST), List.of(), receiver(world, first), second, session.credits(Side.SECOND),
                        secondGives, receiver(world, second)).result());
                assertEquals(firstGives, escrow.held(first));
            }
            TradeSwap.Outcome swapped = TradeSwap.execute(world.store, world.env, first, session.credits(Side.FIRST),
                    firstGives, receiver(world, first), second, session.credits(Side.SECOND), secondGives,
                    receiver(world, second));
            if (!swapped.ok()) {
                // Refused: no credit and no stack has moved, and the trade is open again, unconfirmed.
                assertEquals(firstBefore, balance(world, first));
                assertEquals(secondBefore, balance(world, second));
                assertEquals(firstGives, escrow.held(first));
                assertEquals(secondGives, escrow.held(second));
                assertTrue(session.swapRefused());
                assertEquals(Phase.OPEN, session.phase());
                assertFalse(session.confirmed(Side.FIRST) || session.confirmed(Side.SECOND));
                return 0L;
            }
            assertTrue(session.swapped());
            world.trades.remove(session);
            // Each side's balance moved by exactly what it received less what it gave; the tax is the Reserve's.
            assertEquals(firstBefore + swapped.terms().net(Side.FIRST), balance(world, first));
            assertEquals(secondBefore + swapped.terms().net(Side.SECOND), balance(world, second));
            assertEquals(firstGives, swapped.first());
            assertEquals(secondGives, swapped.second());
            assertTrue(escrow.held(first).isEmpty() && escrow.held(second).isEmpty());
            // Over: a late packet changes nothing.
            assertEquals(Change.CLOSED, session.goodsChanged(Side.FIRST));
            assertFalse(session.cancel(TradeSession.CancelReason.CLOSED));
            return 2L + firstGives.size() * 7L + secondGives.size() * 31L + swapped.terms().firstTax()
                    + swapped.terms().secondTax();
        }
    }

    /** A window is closed, somebody walks away or dies: everything on the table goes back to who put it there. */
    static final class Cancel implements FuzzOperation {
        @Override public String name() { return "trade_cancel"; }
        @Override public int weight() { return 6; }
        @Override public long run(FuzzWorld world) {
            TradeSession session = anyTrade(world);
            if (session == null) return 0L;
            TradeSession.CancelReason[] reasons = TradeSession.CancelReason.values();
            assertTrue(session.cancel(reasons[world.random.nextInt(reasons.length)]));
            world.trades.remove(session);
            long returned = 0L;
            for (Side side : Side.values()) {
                AccountId account = session.account(side);
                long balance = balance(world, account);
                for (Lot lot : TradeSwap.giveBack(world.store, account, receiver(world, account))) returned += lot.count();
                assertTrue(world.store.tradeEscrow().held(account).isEmpty());
                assertEquals(balance, balance(world, account));
            }
            // Cancelled twice, nothing is returned twice.
            assertFalse(session.cancel(TradeSession.CancelReason.CLOSED));
            assertTrue(TradeSwap.giveBack(world.store, session.account(Side.FIRST), BookOperations.inventory(world)).isEmpty());
            return 1L + returned;
        }
    }

    /**
     * Erasure: a trade the erased player has open ends, the other side gets back what it put in,
     * and what the erased player had put in is destroyed with the rest of their goods.
     *
     * @return how many goods were destroyed
     */
    static long erased(FuzzWorld world, UUID player) {
        AccountId account = FuzzWorld.account(player);
        TradeSession session = world.tradeOf(account);
        if (session == null) {
            assertTrue(world.store.tradeEscrow().forgetPlayer(player).isEmpty());
            return 0L;
        }
        session.cancel(TradeSession.CancelReason.NOT_ALLOWED);
        world.trades.remove(session);
        AccountId other = session.account(session.sideOf(account).other());
        TradeSwap.giveBack(world.store, other, BookOperations.inventory(world));
        long destroyed = 0L;
        for (Lot lot : world.store.tradeEscrow().forgetPlayer(player)) {
            world.destroyedInStore(lot.key(), lot.count());
            destroyed += lot.count();
        }
        return destroyed;
    }
}
