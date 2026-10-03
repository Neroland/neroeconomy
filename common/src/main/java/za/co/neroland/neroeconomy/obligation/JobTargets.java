package za.co.neroland.neroeconomy.obligation;

import java.util.UUID;

/**
 * The stored form of a job target and what a given viewer may be shown of it.
 *
 * <p>A target is one of: an item id ({@code namespace:path}), an item tag ({@code #namespace:path}),
 * an entity type id (bounties), or {@code player:<uuid>} for a player-target bounty. The last form
 * carries another player's id, so it is never shown or sent as stored: {@link #visibleTo} replaces
 * it with a fixed token that names nobody. Pure Java so the rules are unit tested directly.
 */
public final class JobTargets {
    /** Longest target a job may be posted with. Older rows may be longer; they still load and expire. */
    public static final int MAX_LENGTH = 64;
    public static final String TAG_PREFIX = "#";
    public static final String PLAYER_PREFIX = "player:";
    /**
     * The entity type id of a player. A bounty on it would be a bounty on every player, so it is
     * never accepted as an entity target; player bounties go through {@link #player} and their gate.
     */
    public static final String PLAYER_ENTITY = "minecraft:player";
    /** Shown to anyone who is neither the issuer nor the target of a player bounty. */
    public static final String PLAYER_HIDDEN = "player";
    /** Shown to the player a bounty is on. */
    public static final String PLAYER_YOU = "player:you";
    /** Shown to the issuer of a player bounty, who chose the target and needs no id repeated. */
    public static final String PLAYER_YOURS = "player:yours";
    private static final String LABEL_PLAYER = "gui.neroeconomy.target.player";
    private static final String LABEL_PLAYER_YOU = "gui.neroeconomy.target.player_you";
    private static final String LABEL_PLAYER_YOURS = "gui.neroeconomy.target.player_yours";

    private JobTargets() { }

    /** Whether a target is acceptable to post: present, not blank and within {@link #MAX_LENGTH}. */
    public static boolean wellFormed(String target) {
        return target != null && !target.isBlank() && target.length() <= MAX_LENGTH;
    }

    public static boolean isPlayer(String target) { return target != null && target.startsWith(PLAYER_PREFIX); }

    public static boolean isTag(String target) { return target != null && target.startsWith(TAG_PREFIX); }

    /** The stored target of a bounty on {@code player}. */
    public static String player(UUID player) { return PLAYER_PREFIX + player; }

    /**
     * The target as {@code viewer} may see it. Anything that is not a player target is public and
     * returned unchanged; a player target becomes {@link #PLAYER_YOURS} for the issuer,
     * {@link #PLAYER_YOU} for the player it names, and {@link #PLAYER_HIDDEN} for everyone else
     * (including a null viewer such as the console).
     */
    public static String visibleTo(String target, UUID issuer, UUID viewer) {
        if (!isPlayer(target)) return target;
        if (viewer != null && viewer.equals(issuer)) return PLAYER_YOURS;
        if (viewer != null && target.equals(player(viewer))) return PLAYER_YOU;
        return PLAYER_HIDDEN;
    }

    /**
     * The lang key for a target as {@link #visibleTo} returned it, or null when it is an ordinary id
     * that is shown as it stands. Every player form maps to a phrase, including one this method was
     * never meant to receive (a raw {@code player:<uuid>}), so a player id can never be displayed by
     * falling through.
     */
    public static String playerLabelKey(String shown) {
        if (shown == null) return null;
        if (PLAYER_YOU.equals(shown)) return LABEL_PLAYER_YOU;
        if (PLAYER_YOURS.equals(shown)) return LABEL_PLAYER_YOURS;
        return PLAYER_HIDDEN.equals(shown) || isPlayer(shown) ? LABEL_PLAYER : null;
    }
}
