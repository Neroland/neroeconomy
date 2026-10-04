package za.co.neroland.neroeconomy.exchange;

import java.util.HashMap;
import java.util.Map;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * The per-account rate limit on exchange actions: at most so many buys, sells and bond actions in
 * any one minute of economy time, counted from the account's first action of that minute. Quotes
 * and reads are not counted.
 *
 * <p>It is handed to the rules by whoever calls them and is never saved: the game keeps one for
 * real trades and one for practice, drops an account's entry on erasure and everything when the
 * server stops. The account id is a key held in memory only.
 */
public final class ActionLimiter {
    public static final long WINDOW_MILLIS = 60_000L;
    /** Most accounts tracked at once; beyond it, windows that have run out are dropped first. */
    static final int MAX_TRACKED = 4_096;

    private final Map<AccountId, long[]> windows = new HashMap<>();

    /**
     * Counts one action. @return false when the account has already made {@code perMinute} of
     * them in the minute that began with its first
     */
    public synchronized boolean allow(AccountId account, long now, int perMinute) {
        if (account == null) return false;
        long[] window = windows.get(account);
        // A clock that somehow stepped back starts a new window rather than locking the account out.
        if (window == null || now - window[0] >= WINDOW_MILLIS || now < window[0]) {
            if (window == null && windows.size() >= MAX_TRACKED) {
                windows.values().removeIf(old -> now - old[0] >= WINDOW_MILLIS || now < old[0]);
                // Still full of live windows: this account waits, like one over its own limit.
                if (windows.size() >= MAX_TRACKED) return false;
            }
            windows.put(account, new long[] {now, 1L});
            return perMinute >= 1;
        }
        if (window[1] >= perMinute) return false;
        window[1]++;
        return true;
    }

    /** Erasure: drops what is held for one account. */
    public synchronized void forget(AccountId account) {
        if (account != null) windows.remove(account);
    }

    /** Server stop: drops everything. */
    public synchronized void clear() { windows.clear(); }

    /** How many accounts have a window; exists so the clearing rules can be tested. */
    public synchronized int tracked() { return windows.size(); }
}
