package za.co.neroland.neroeconomy.economy;

/**
 * Auditable reasons for a balance mutation. Constants are only ever appended: saved rows are parsed
 * by name, so an existing one is never renamed, removed or reordered.
 *
 * <p>Only four reasons create credits ({@link #mints()}) and only two destroy them
 * ({@link #burns()}); the ledger refuses to mint or burn under any other reason. Everything else is
 * a transfer between two accounts.
 */
public enum TransactionType {
    STARTING_BALANCE,
    DEPOSIT,
    WITHDRAWAL,
    PLAYER_PAYMENT,
    ADMIN_GRANT,
    ADMIN_TAKE,
    ADMIN_SET,
    SHOP_PURCHASE,
    MARKET_PURCHASE,
    MARKET_ESCROW,
    ESCROW_REFUND,
    MARKET_PAYOUT,
    CONTRACT_PAYOUT,
    BOUNTY_PAYOUT,
    AUCTION_BID,
    AUCTION_PAYOUT,
    CREDIT_CHIP_REDEMPTION,
    /** The Reserve's initial fill and its daily top-up. */
    RESERVE_MINT,
    /** The one-time restoration of escrow that an older version had written off the ledger. */
    MIGRATION,
    /** An erased account's balance and escrow moving to the Reserve. */
    ERASURE_SWEEP,
    FEE_MARKET,
    FEE_LISTING,
    FEE_AUCTION,
    FEE_EXCHANGE,
    TRADE_TAX,
    /** The Reserve's side of a price modifier: a discount it pays, or a surcharge it receives. */
    PRICE_ADJUSTMENT,
    /** A deposit another mod made without a reason of its own; paid by the Reserve. */
    SIBLING_PAYOUT,
    /** A withdrawal another mod made without a reason of its own; paid to the Reserve. */
    SIBLING_CHARGE;

    /** Whether this reason may create credits. */
    public boolean mints() {
        return this == RESERVE_MINT || this == MIGRATION || this == ADMIN_GRANT || this == ADMIN_SET;
    }

    /** Whether this reason may destroy credits. */
    public boolean burns() {
        return this == ADMIN_TAKE || this == ADMIN_SET;
    }
}
