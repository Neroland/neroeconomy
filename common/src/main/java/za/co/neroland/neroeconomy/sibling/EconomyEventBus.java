package za.co.neroland.neroeconomy.sibling;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.EconomyEvents;

/**
 * The dispatch behind {@link EconomyEvents}: the listeners of one event type, each called in turn
 * with its exceptions caught. Only this mod fires; the public API can only register.
 *
 * <p>Events are <b>queued</b> where an operation settles and handed out by {@link #drain()} at the
 * end of the economy's server tick, so a listener never runs inside a half-finished operation
 * (a matching loop, a batch) and can safely make economy calls of its own. At most
 * {@link #MAX_PENDING} wait; more are dropped with one debug line.
 */
public final class EconomyEventBus<E> {
    /** The most listeners one event type holds; more are ignored with one warning. */
    public static final int MAX_LISTENERS = 256;
    /** The most events waiting for the end of the tick. */
    public static final int MAX_PENDING = 10_000;

    private static final ArrayDeque<Runnable> PENDING = new ArrayDeque<>();

    public static final EconomyEventBus<EconomyEvents.Sold> SOLD = new EconomyEventBus<>("sold");
    public static final EconomyEventBus<EconomyEvents.Bought> BOUGHT = new EconomyEventBus<>("bought");
    public static final EconomyEventBus<EconomyEvents.Filled> FILLED = new EconomyEventBus<>("filled");
    public static final EconomyEventBus<EconomyEvents.Dividends> DIVIDENDS = new EconomyEventBus<>("dividends");
    public static final EconomyEventBus<EconomyEvents.SiblingPaid> SIBLING_PAID = new EconomyEventBus<>("sibling_paid");

    private final String name;
    private final List<Consumer<E>> listeners = new CopyOnWriteArrayList<>();
    private final Set<Object> reported = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private volatile boolean fullReported;

    public EconomyEventBus(String name) { this.name = name; }

    public void add(Consumer<E> listener) {
        if (listener == null) return;
        if (listeners.size() >= MAX_LISTENERS) {
            if (!fullReported) NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Too many {} listeners; one was ignored.", name);
            fullReported = true;
            return;
        }
        listeners.add(listener);
    }

    /** Whether anyone listens: lets a caller skip building an event nobody will read. */
    public boolean active() { return !listeners.isEmpty(); }

    /**
     * Hands {@code event} to every listener. A listener that throws is reported once, at warn,
     * by its class name and never with the event, and stays registered.
     */
    public void fire(E event) {
        if (event == null) return;
        for (Consumer<E> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException | LinkageError failure) {
                if (reported.add(listener)) NeroEconomyCommon.LOGGER.warn(
                        "[NeroEconomy] A {} listener ({}) threw {}; it stays registered.", name,
                        listener.getClass().getName(), failure.getClass().getName());
            }
        }
    }

    /** How many listeners are registered; exists for tests. */
    public int size() { return listeners.size(); }

    /** Test seam: drops every listener. */
    public void clearForTest() {
        listeners.clear();
        reported.clear();
        fullReported = false;
    }

    // --- the queue -----------------------------------------------------------------------------

    private <T extends E> void queue(T event) {
        if (event == null || !active()) return;
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING) {
                NeroEconomyCommon.LOGGER.debug("[NeroEconomy] An economy event was dropped; the queue is full.");
                return;
            }
            PENDING.add(() -> fire(event));
        }
    }

    /** Hands every queued event to its listeners, in the order they happened. Server thread. */
    public static void drain() {
        for (int handed = 0; handed < MAX_PENDING; handed++) {
            Runnable next;
            synchronized (PENDING) {
                next = PENDING.poll();
            }
            if (next == null) return;
            next.run();
        }
    }

    /** Server stop: events of the world that stopped are not handed out in the next one. */
    public static void clearPending() {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    // --- the firing points ---------------------------------------------------------------------

    /** A sibling payout was applied. */
    public static void paid(UUID player, String modId, String reason, long amount, long at) {
        SIBLING_PAID.queue(new EconomyEvents.SiblingPaid(player, modId, reason, amount, at));
    }

    /** One player's side of a settled sale. A system account (a Merchant House) gets no event. */
    public static void sold(UUID seller, String item, long quantity, long unitPrice, EconomyEvents.Venue venue,
            long at) {
        if (seller != null && item != null && quantity > 0L)
            SOLD.queue(new EconomyEvents.Sold(seller, item, quantity, unitPrice, venue, at));
    }

    /** One player's side of a settled purchase. */
    public static void bought(UUID buyer, String item, long quantity, long unitPrice, EconomyEvents.Venue venue,
            long at) {
        if (buyer != null && item != null && quantity > 0L)
            BOUGHT.queue(new EconomyEvents.Bought(buyer, item, quantity, unitPrice, venue, at));
    }

    /** A resting order of a player filled. */
    public static void filled(UUID owner, String item, long quantity, long unitPrice, boolean buyOrder, long at) {
        if (owner != null && item != null && quantity > 0L)
            FILLED.queue(new EconomyEvents.Filled(owner, item, quantity, unitPrice, buyOrder, at));
    }

    /** One economy day's dividends. */
    public static void dividends(long day, long paid, double factor, int stocks) {
        DIVIDENDS.queue(new EconomyEvents.Dividends(day, paid, factor, stocks));
    }
}
