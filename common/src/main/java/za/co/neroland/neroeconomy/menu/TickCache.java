package za.co.neroland.neroeconomy.menu;

import java.util.function.Supplier;

/**
 * Remembers one computed value for the length of a server tick. A menu's data slots are each read
 * separately when the menu syncs, and one 64-bit value spans four of them, so without this a single
 * sync would ask the ledger or the pricing engine for the same answer several times over.
 */
public final class TickCache<T> {
    private boolean filled;
    private long tick;
    private T value;

    /** The value for {@code currentTick}, computed at most once per distinct tick. */
    public T get(long currentTick, Supplier<T> compute) {
        if (!filled || tick != currentTick) {
            value = compute.get();
            tick = currentTick;
            filled = true;
        }
        return value;
    }

    /** Forces the next read to recompute, for a caller that knows the value just changed. */
    public void invalidate() { filled = false; }
}
