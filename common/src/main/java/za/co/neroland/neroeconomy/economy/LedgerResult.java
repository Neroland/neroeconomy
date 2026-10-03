package za.co.neroland.neroeconomy.economy;

/** Why a ledger operation did or did not happen. Nothing moves unless the result is {@link #APPLIED}. */
public enum LedgerResult {
    APPLIED,
    /** A missing or negative value, an unknown account, or a reason the operation may not carry. */
    INVALID,
    /** The economy is frozen for this session; see {@link EconomyHealth}. */
    FROZEN,
    /** No server is bound, so there is no ledger to act on. */
    UNAVAILABLE,
    INSUFFICIENT_FUNDS,
    /** The payer has the credits, but part of them is a starting balance that has not vested yet. */
    FUNDS_LOCKED,
    /** The Reserve does not hold enough to make the payment. */
    RESERVE_SHORT,
    /** The Reserve's daily budget for this kind of payment is used up. */
    BUDGET_SPENT,
    /** The receiving balance, or the total supply, would pass the largest storable amount. */
    OVERFLOW;

    public boolean ok() { return this == APPLIED; }
}
