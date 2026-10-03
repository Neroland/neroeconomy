package za.co.neroland.neroeconomy.direct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trade requests that are waiting for an answer: who asked whom, and until when. Pure Java, so
 * the limits are unit tested directly.
 *
 * <ul>
 *   <li>A request lapses after {@link #TIMEOUT_MILLIS}.</li>
 *   <li>One requester sends at most one request every {@link #COOLDOWN_MILLIS}, whatever the
 *       answer was — a refused request uses the cooldown up too, so it cannot be used to probe.</li>
 *   <li>Between two players at most one request is waiting, whichever of them sent it. Asking
 *       somebody who has already asked you is an acceptance ({@link Result#CROSSED}).</li>
 *   <li>At most {@link #MAX_WAITING} requests wait for one player, so nobody's chat can be
 *       flooded; the requester is told the same thing as for a player who has muted requests.</li>
 * </ul>
 *
 * <p>Player ids are keys held in memory only: nothing here is saved, logged or sent, a request is
 * gone after half a minute, and everything a player has here goes when they log out
 * ({@link #forget}) and when the server stops ({@link #clear}). Time is a number the caller
 * passes in, in milliseconds from any clock that only moves forward.
 */
public final class TradeRequests {
    public static final long TIMEOUT_MILLIS = 30_000L;
    public static final long COOLDOWN_MILLIS = 5_000L;
    public static final int MAX_WAITING = 8;

    public enum Result {
        /** The request is waiting for the other player's answer. */
        SENT,
        /** A player cannot trade with themselves. */
        SELF,
        /** The requester asked somebody a moment ago. */
        COOLDOWN,
        /** The requester's own request to this player is still waiting. */
        PAIR_PENDING,
        /** The other player had already asked the requester: both want to trade, so it starts. */
        CROSSED,
        /** The other player has muted requests, is busy, or has too many waiting. Said the same way for all three. */
        NOT_ACCEPTING
    }

    /** One waiting request. */
    public record Request(UUID from, UUID to, long expiresAt) { }

    private record Pair(UUID low, UUID high) {
        static Pair of(UUID a, UUID b) { return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a); }
    }

    private final Map<Pair, Request> waiting = new LinkedHashMap<>();
    private final Map<UUID, Long> lastSent = new HashMap<>();

    /**
     * {@code from} asks {@code to} for a trade.
     *
     * @param accepting whether {@code to} takes requests at all right now: not muted, not already
     *                  in a trade
     */
    public synchronized Result request(UUID from, UUID to, boolean accepting, long now) {
        if (from == null || to == null || from.equals(to)) return Result.SELF;
        expire(now);
        Pair pair = Pair.of(from, to);
        Request open = waiting.get(pair);
        if (open != null) {
            if (open.from().equals(from)) return Result.PAIR_PENDING;
            // The other player asked first: this is their answer.
            waiting.remove(pair);
            return Result.CROSSED;
        }
        Long last = lastSent.get(from);
        if (last != null && now - last < COOLDOWN_MILLIS && now >= last) return Result.COOLDOWN;
        lastSent.put(from, now);
        if (!accepting || waitingFor(to) >= MAX_WAITING) return Result.NOT_ACCEPTING;
        waiting.put(pair, new Request(from, to, Long.MAX_VALUE - now < TIMEOUT_MILLIS ? Long.MAX_VALUE
                : now + TIMEOUT_MILLIS));
        return Result.SENT;
    }

    private int waitingFor(UUID to) {
        int count = 0;
        for (Request request : waiting.values()) if (request.to().equals(to)) count++;
        return count;
    }

    /**
     * Takes the request {@code to} is answering out of the list: the one from {@code from}, or the
     * newest one waiting for them when {@code from} is null.
     *
     * @return the request, or null when none is waiting any more
     */
    public synchronized Request answer(UUID to, UUID from, long now) {
        if (to == null) return null;
        expire(now);
        Request found = null;
        for (Request request : waiting.values())
            if (request.to().equals(to) && (from == null || request.from().equals(from))) found = request;
        if (found != null) waiting.remove(Pair.of(found.from(), found.to()));
        return found;
    }

    /** The players with a request waiting for {@code to}, oldest first. */
    public synchronized List<UUID> askers(UUID to, long now) {
        expire(now);
        List<UUID> out = new ArrayList<>();
        for (Request request : waiting.values()) if (request.to().equals(to)) out.add(request.from());
        return out;
    }

    /** Drops every request past its time. @return the ones that lapsed, so their senders can be told */
    public synchronized List<Request> expire(long now) {
        List<Request> lapsed = new ArrayList<>();
        Iterator<Request> all = waiting.values().iterator();
        while (all.hasNext()) {
            Request request = all.next();
            if (request.expiresAt() > now) continue;
            all.remove();
            lapsed.add(request);
        }
        // A cooldown that has run out is no longer worth remembering.
        lastSent.values().removeIf(sent -> now - sent >= COOLDOWN_MILLIS);
        return lapsed;
    }

    /** Logout, erasure: everything the player asked or was asked goes. */
    public synchronized void forget(UUID player) {
        if (player == null) return;
        waiting.values().removeIf(request -> request.from().equals(player) || request.to().equals(player));
        lastSent.remove(player);
    }

    /** Server stop. */
    public synchronized void clear() {
        waiting.clear();
        lastSent.clear();
    }

    public synchronized int size() { return waiting.size(); }

    /** How many players have anything remembered; exists so the clearing rules can be tested. */
    public synchronized int tracked() { return lastSent.size(); }
}
