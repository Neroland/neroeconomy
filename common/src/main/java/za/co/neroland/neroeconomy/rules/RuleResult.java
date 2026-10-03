package za.co.neroland.neroeconomy.rules;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/** Why a change to a rule did or did not happen. Nothing has moved unless the result is {@link #OK}. */
public enum RuleResult {
    OK,
    /** A price, quantity or total the rules do not accept. */
    INVALID,
    /** The request id was already acted on. */
    STALE_REQUEST,
    /** The account already has as many rules as the server allows. */
    LIMIT_REACHED,
    /** The item id names nothing. */
    UNKNOWN_ITEM,
    /** The tag id names no item tag with anything in it. */
    UNKNOWN_TAG,
    /** The item is in {@code neroeconomy:untradeable}. */
    UNTRADEABLE,
    /** The inventory the rule is to work from is not one anything knows. */
    UNKNOWN_BINDING,
    /** The owner does not have the credits for the budget. */
    INSUFFICIENT_FUNDS,
    /** The owner has the credits, but part of them is a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** No rule of the caller's has that id. */
    NOT_FOUND,
    /** The rule's budget could not be paid back; it stays, paused, until it can be. */
    REFUND_BLOCKED,
    /** The economy is frozen for this session. */
    FROZEN;

    public boolean ok() { return this == OK; }

    /** Lang key of the message shown to the player. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        return "message.neroeconomy.rule." + name().toLowerCase(Locale.ROOT);
    }
}
