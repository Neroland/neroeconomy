package za.co.neroland.neroeconomy.api;

import java.util.Locale;

/**
 * What an {@link EconomyCaller} call did. Nothing moved unless the result is {@link #APPLIED};
 * {@link #DUPLICATE} means the same request was already applied and nothing moved this time.
 * Constants are only ever appended.
 */
public enum EconomyResult {
    /** The credits moved. */
    APPLIED,
    /** This request key was already applied in the last 24 economy-hours; nothing moved again. */
    DUPLICATE,
    /** A missing, malformed or out-of-range argument. */
    INVALID,
    /** The reason code is not one this caller claimed. */
    UNKNOWN_REASON,
    /** No server is bound, or the server passed in is not the running one. */
    UNAVAILABLE,
    /** The economy is frozen for this session; nothing moves until an operator acts. */
    FROZEN,
    /** The Reserve does not hold enough to make the payment. */
    RESERVE_SHORT,
    /** This caller's own daily payout limit is used up. */
    CALLER_BUDGET_SPENT,
    /** The daily payout limit all other mods share is used up. */
    SIBLING_BUDGET_SPENT,
    /** The player has had as much as other mods may pay one player in a day. */
    PLAYER_CAP_REACHED,
    /** The paying player does not have the credits. */
    INSUFFICIENT_FUNDS,
    /** The paying player has the credits, but part of them is a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** The receiving balance would pass the largest storable amount. */
    OVERFLOW,
    /** This caller is making calls faster than allowed. */
    RATE_LIMITED;

    /** Whether the request stands as applied: {@link #APPLIED} or {@link #DUPLICATE}. */
    public boolean ok() { return this == APPLIED || this == DUPLICATE; }

    /** The lang key of a short, plain explanation, for a caller that shows the outcome to a player. */
    public String translationKey() { return "economy_result.neroeconomy." + name().toLowerCase(Locale.ROOT); }
}
