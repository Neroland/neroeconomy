package za.co.neroland.neroeconomy.economy;

/**
 * What a payment out of the Reserve is counted against. Each kind except {@link #UNBUDGETED} has a
 * daily limit (see {@code EconomyConfig}); the day's use is persisted with the ledger. Constants are
 * only ever appended: the name is the saved form.
 */
public enum BudgetKind {
    /** Merchant House payouts to players. */
    NPC,
    /** Exchange gains, dividends and newly reserved bond interest. */
    EXCHANGE,
    /** Deposits another mod makes without a reason of its own. */
    SIBLING,
    /** Payouts of server-funded jobs, counted when the job is posted. */
    SYSTEM_JOB,
    /** Starting balances, Credit Chips and price discounts: limited only by the Reserve's balance. */
    UNBUDGETED
}
