package za.co.neroland.neroeconomy.economy;

/**
 * Whether the economy may move value in this session. Every value-moving entry point — the
 * currency provider, the market, jobs and auctions, shops, Credit Chips, the Bank Terminal, link
 * actions — asks this one class before it does anything.
 *
 * <p>The economy <b>freezes</b> when the world's marker says an economy exists but its store came
 * back empty: carrying on would hand every player a fresh balance on top of whatever is still in
 * the unreadable file. While frozen, reads still work and nothing is written; an operator either
 * restores the store and restarts, or accepts a fresh start with {@code /eco admin health accept}.
 *
 * <p>The flag is process-wide because one server runs at a time; it is cleared when the server
 * unbinds. It holds no player data.
 */
public final class EconomyHealth {
    /** Lang key of the one message a player or operator is shown for a refused operation. */
    public static final String FROZEN_KEY = "message.neroeconomy.frozen";

    private static volatile boolean frozen;

    private EconomyHealth() { }

    public static boolean frozen() { return frozen; }

    /** Refuses every value-moving operation until {@link #clear()}. */
    public static void freeze() { frozen = true; }

    /** Back to normal: the store is present, an operator accepted a fresh start, or the server stopped. */
    public static void clear() { frozen = false; }
}
