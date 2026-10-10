package za.co.neroland.neroeconomy.network;

/**
 * The pure half of the shared intent guard: given what the server observed about a player and the
 * block their menu belongs to, may the menu stay open, and may the player act through it. Kept free
 * of the game so the rules are unit tested directly; {@link IntentGuard} supplies the observations.
 */
public final class IntentRules {
    /** Eight blocks, squared: how far a player may stand from the block their menu belongs to. */
    public static final double MAX_DISTANCE_SQR = 64.0D;

    private IntentRules() { }

    /**
     * Whether a menu is still attached to its block: the block entity has not been removed, it is in
     * the level the player is in now (the same coordinates in another dimension do not count), and
     * the player is within {@link #MAX_DISTANCE_SQR} of it.
     */
    public static boolean anchored(boolean anchorPresent, boolean sameLevel, double distanceSqr) {
        return anchorPresent && sameLevel && distanceSqr >= 0.0D && distanceSqr <= MAX_DISTANCE_SQR;
    }

    /**
     * Whether a player may act through a menu: alive, not a spectator, and the menu they named is
     * the one the server has open for them and is still {@link #anchored}.
     */
    public static boolean mayAct(boolean alive, boolean spectator, boolean menuOpen, boolean anchored) {
        return alive && !spectator && menuOpen && anchored;
    }

    /**
     * Whether a payload is addressed to the menu the player has open: that menu is of the kind the
     * payload acts through, and the container id the client sent is the open menu's, not a stale one.
     */
    public static boolean addressed(boolean openMenuOfType, int openContainerId, int sentContainerId) {
        return openMenuOfType && openContainerId == sentContainerId;
    }
}
