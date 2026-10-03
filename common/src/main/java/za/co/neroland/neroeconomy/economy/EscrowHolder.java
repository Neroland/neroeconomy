package za.co.neroland.neroeconomy.economy;

/**
 * A section of the economy store that records credits held in the ledger's escrow account.
 * Invariant B — the escrow balance equals the sum of every recorded escrow obligation — is checked
 * over every section that implements this, so a new section that escrows credits (the order book,
 * offers, auto-trade rules) implements it and is listed in {@code EconomyStore.sections()}.
 */
@FunctionalInterface
public interface EscrowHolder {
    /** Every credit this section records as owed and held in the escrow account. Never negative. */
    long escrowObligations();
}
