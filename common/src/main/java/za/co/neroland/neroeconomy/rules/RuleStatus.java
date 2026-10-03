package za.co.neroland.neroeconomy.rules;

import java.util.Locale;

import za.co.neroland.neroeconomy.link.EconomyAlerts;

/**
 * The one thing a rule says about itself. Constants are only ever appended: the name is the saved
 * form.
 */
public enum RuleStatus {
    /** Working: an order is on the book, or today's quantity is done and it carries on tomorrow. */
    RUNNING,
    /** A sell rule that has sold its whole quantity. */
    FILLED,
    /** A buy rule whose budget cannot buy another unit, or a rule that cannot pay the listing fee. */
    OUT_OF_BUDGET,
    /** A sell rule whose inventory holds none of what it sells. */
    NOTHING_TO_SELL,
    /** The inventory the rule works from cannot be reached right now (its chunk is not loaded). */
    UNREACHABLE,
    /** The rule's order is waiting: the other side of the book is there, but not at this price. */
    PRICE_NOT_MET,
    /** Paused by its owner, or by their "pause all my rules" switch. */
    PAUSED,
    /** The account's rules have filled as much value today as {@code ruleDailyValueCap} allows. */
    DAILY_CAP,
    /** A buy rule whose inventory has no room for what it buys. */
    STORAGE_FULL,
    /** The account already has as many open orders as the server allows, so the rule cannot place its own. */
    ORDER_LIMIT;

    /** Lang key of the wording shown to the owner. */
    public String translationKey() { return "message.neroeconomy.rule.status." + name().toLowerCase(Locale.ROOT); }

    /** Whether the rule stopped by itself and needs its owner to do something, or to wait. */
    public boolean stopped() {
        return this == OUT_OF_BUDGET || this == NOTHING_TO_SELL || this == UNREACHABLE || this == DAILY_CAP
                || this == STORAGE_FULL || this == ORDER_LIMIT;
    }

    /** The alert raised when a rule enters this status, or null for none. */
    public EconomyAlerts.Kind alert() {
        if (this == FILLED) return EconomyAlerts.Kind.RULE_FILLED;
        return stopped() ? EconomyAlerts.Kind.RULE_PAUSED : null;
    }
}
