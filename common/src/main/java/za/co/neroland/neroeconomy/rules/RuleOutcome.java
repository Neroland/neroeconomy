package za.co.neroland.neroeconomy.rules;

/**
 * What a change to a rule did.
 *
 * @param result          why it did or did not happen
 * @param rule            the rule as it is now (as it was, for a delete); null unless the result is OK
 * @param escrowed        credits moved into escrow for the rule's budget by this change
 * @param refunded        credits that came back out of escrow by this change
 * @param refundedToClaim true when the refund could not go straight to the balance and is waiting
 *                        to be collected instead
 */
public record RuleOutcome(RuleResult result, TradeRule rule, long escrowed, long refunded, boolean refundedToClaim) {
    static RuleOutcome refused(RuleResult result) { return new RuleOutcome(result, null, 0L, 0L, false); }

    static RuleOutcome of(TradeRule rule) { return new RuleOutcome(RuleResult.OK, rule, 0L, 0L, false); }

    public boolean ok() { return result.ok(); }
}
