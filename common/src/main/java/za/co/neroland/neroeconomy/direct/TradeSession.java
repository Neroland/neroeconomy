package za.co.neroland.neroeconomy.direct;

import java.util.Locale;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.trade.Accounts;

/**
 * The rules of one direct trade between two players, as a state machine with no game in it: who
 * has confirmed, when the contents are frozen, when the swap is due, and what is ignored because
 * it came too late. The trade window, the commands and the fuzzer all drive this one class.
 *
 * <ul>
 *   <li><b>Open.</b> Each side may change what it gives: the goods in its slots and a credit
 *       amount. <b>Any change by either side clears both confirmations</b> and moves the
 *       {@linkplain #revision() revision} on, so nobody can be held to something they agreed to
 *       before it changed. A confirmation names the revision the player was looking at; one that
 *       names an older revision is ignored.</li>
 *   <li><b>Locked.</b> Once both sides have confirmed the same revision the contents freeze for
 *       {@link #LOCK_MILLIS}: no change is accepted, a confirmation cannot be taken back, and
 *       either side can still cancel.</li>
 *   <li><b>Done</b> or <b>cancelled.</b> When the lock runs out the caller makes the swap and
 *       reports it with {@link #swapped}; a swap that could not be made reopens the trade with both
 *       confirmations cleared ({@link #swapRefused}). After either end state every call is
 *       ignored, so a packet that arrives late changes nothing.</li>
 * </ul>
 *
 * <p>Value-moving intents carry a request id. The session does not keep ids: it asks the
 * {@link RequestGate} it is handed — the economy's one request ledger — and acts only on an id the
 * gate has not seen. An intent that is refused for any other reason does not use its id up.
 *
 * <p>Time is a number the caller passes in, in milliseconds from any clock that only moves
 * forward. Nothing here is saved: a session lasts as long as its window, and what its sides have
 * put in is recorded by {@link TradeEscrowSection}.
 */
public final class TradeSession {
    /** How long the contents stay frozen between the second confirmation and the swap. */
    public static final long LOCK_MILLIS = 3_000L;
    /** The largest credit amount one side may put in a trade. */
    public static final long MAX_CREDITS = 1_000_000_000_000L;

    /** The two sides of a trade. */
    public enum Side {
        FIRST, SECOND;

        public Side other() { return this == FIRST ? SECOND : FIRST; }
    }

    public enum Phase { OPEN, LOCKED, DONE, CANCELLED }

    /** What a call did. Only {@link #APPLIED} changed anything. */
    public enum Change {
        APPLIED,
        /** Nothing to do: the same amount again, a confirmation already given, or one never given. */
        UNCHANGED,
        /** The request id was already acted on. */
        STALE_REQUEST,
        /** A confirmation of a revision that has since changed. */
        STALE_REVISION,
        /** Refused because the contents are frozen. */
        LOCKED,
        /** The trade is over: swapped or cancelled. */
        CLOSED,
        /** An amount the trade does not accept: negative, or past {@link #MAX_CREDITS}. */
        INVALID
    }

    /** Why a trade ended without a swap. The name is never saved; the wording is a lang key. */
    public enum CancelReason {
        /** A player closed the window or pressed Cancel. */
        CLOSED,
        /** The players are no longer within range of each other, or one left their terminal. */
        OUT_OF_RANGE,
        DIED,
        DISCONNECTED,
        /** A player is no longer allowed to trade: spectating, or the window is no longer theirs. */
        NOT_ALLOWED,
        /** The economy froze, or the server is stopping. */
        STOPPED;

        /** Lang key of the one line both players are shown. */
        public String translationKey() {
            return "message.neroeconomy.direct_trade.cancelled." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The economy's request ledger, as the session needs it. */
    @FunctionalInterface
    public interface RequestGate {
        /** Records {@code requestId} for {@code player}. @return false when it was already recorded */
        boolean fresh(UUID player, UUID requestId);
    }

    private final AccountId first;
    private final AccountId second;
    private final long[] credits = new long[2];
    private final boolean[] confirmed = new boolean[2];
    private Phase phase = Phase.OPEN;
    private int revision;
    private long lockedUntil;
    private CancelReason cancelReason;

    public TradeSession(AccountId first, AccountId second) {
        if (first == null || second == null || first.equals(second) || Accounts.player(first) == null
                || Accounts.player(second) == null) throw new IllegalArgumentException("trade sides");
        this.first = first;
        this.second = second;
    }

    // --- reads -------------------------------------------------------------------------------

    public AccountId account(Side side) { return side == Side.FIRST ? first : second; }

    /** The side an account is on, or null when it is not part of this trade. */
    public Side sideOf(AccountId account) {
        if (first.equals(account)) return Side.FIRST;
        return second.equals(account) ? Side.SECOND : null;
    }

    public Phase phase() { return phase; }
    public long credits(Side side) { return credits[side.ordinal()]; }
    public boolean confirmed(Side side) { return confirmed[side.ordinal()]; }
    public CancelReason cancelReason() { return cancelReason; }

    /** Moves on with every accepted change; a confirmation must name the current value. */
    public int revision() { return revision; }

    /** Whether what the sides give may still be changed. */
    public boolean editable() { return phase == Phase.OPEN; }

    /** Whether the trade has ended, by a swap or a cancel. */
    public boolean over() { return phase == Phase.DONE || phase == Phase.CANCELLED; }

    /** How much of the freeze is left at {@code now}; 0 when the contents are not frozen. */
    public long lockRemaining(long now) {
        return phase == Phase.LOCKED ? Math.max(0L, lockedUntil - now) : 0L;
    }

    /** Whether the freeze has run out and the swap is to be made now. */
    public boolean due(long now) { return phase == Phase.LOCKED && now >= lockedUntil; }

    // --- changing what is given --------------------------------------------------------------

    /**
     * A side changed the goods in its slots. Accepted only while the trade is open; it clears both
     * confirmations. While the contents are frozen the answer is {@link Change#LOCKED} and the
     * caller must not let the change stand.
     */
    public Change goodsChanged(Side side) {
        if (side == null) return Change.INVALID;
        if (over()) return Change.CLOSED;
        if (phase == Phase.LOCKED) return Change.LOCKED;
        changed();
        return Change.APPLIED;
    }

    /** A side sets the credits it gives. A different amount clears both confirmations. */
    public Change setCredits(Side side, long amount, UUID requestId, RequestGate gate) {
        if (side == null || requestId == null || gate == null) return Change.INVALID;
        if (over()) return Change.CLOSED;
        if (phase == Phase.LOCKED) return Change.LOCKED;
        if (amount < 0L || amount > MAX_CREDITS) return Change.INVALID;
        if (!gate.fresh(Accounts.player(account(side)), requestId)) return Change.STALE_REQUEST;
        if (credits[side.ordinal()] == amount) return Change.UNCHANGED;
        credits[side.ordinal()] = amount;
        changed();
        return Change.APPLIED;
    }

    private void changed() {
        confirmed[0] = false;
        confirmed[1] = false;
        revision++;
    }

    // --- confirming --------------------------------------------------------------------------

    /**
     * A side confirms the trade as it stood at {@code seenRevision}. When both sides have
     * confirmed, the contents freeze until {@code now + LOCK_MILLIS}.
     */
    public Change confirm(Side side, int seenRevision, UUID requestId, RequestGate gate, long now) {
        if (side == null || requestId == null || gate == null) return Change.INVALID;
        if (over()) return Change.CLOSED;
        if (phase == Phase.LOCKED) return Change.LOCKED;
        if (seenRevision != revision) return Change.STALE_REVISION;
        if (confirmed[side.ordinal()]) return Change.UNCHANGED;
        if (!gate.fresh(Accounts.player(account(side)), requestId)) return Change.STALE_REQUEST;
        confirmed[side.ordinal()] = true;
        if (confirmed[0] && confirmed[1]) {
            phase = Phase.LOCKED;
            lockedUntil = Long.MAX_VALUE - now < LOCK_MILLIS ? Long.MAX_VALUE : now + LOCK_MILLIS;
        }
        return Change.APPLIED;
    }

    /** A side takes its confirmation back. Not possible once the contents are frozen: cancel instead. */
    public Change withdraw(Side side) {
        if (side == null) return Change.INVALID;
        if (over()) return Change.CLOSED;
        if (phase == Phase.LOCKED) return Change.LOCKED;
        if (!confirmed[side.ordinal()]) return Change.UNCHANGED;
        confirmed[side.ordinal()] = false;
        return Change.APPLIED;
    }

    // --- ending ------------------------------------------------------------------------------

    /**
     * Ends the trade without a swap. Possible at any time before the swap, the freeze included.
     *
     * @return false when the trade was already over, so the caller returns nothing twice
     */
    public boolean cancel(CancelReason reason) {
        if (over()) return false;
        phase = Phase.CANCELLED;
        cancelReason = reason == null ? CancelReason.CLOSED : reason;
        confirmed[0] = false;
        confirmed[1] = false;
        return true;
    }

    /**
     * The swap was made. @return false when the trade was not frozen and waiting for one, in which
     * case nothing may have been swapped
     */
    public boolean swapped() {
        if (phase != Phase.LOCKED) return false;
        phase = Phase.DONE;
        return true;
    }

    /**
     * The swap could not be made, or something changed that should not have been able to: the
     * trade is open again with both confirmations cleared.
     */
    public boolean swapRefused() {
        if (phase != Phase.LOCKED) return false;
        phase = Phase.OPEN;
        changed();
        return true;
    }
}
