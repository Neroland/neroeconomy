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
    SIBLING_CHARGE,
    /** The principal of a share or index purchase, moving into the exchange account. */
    SHARE_BUY,
    /** Principal coming back out of the exchange account when shares or index units are sold. */
    SHARE_SELL,
    /** What a sale fetched above what the units cost; paid by the Reserve within the exchange budget. */
    CAPITAL_GAIN,
    /** What a sale fetched below what the units cost; moves from the exchange account to the Reserve. */
    CAPITAL_LOSS,
    /** A day's dividend on steady shares; paid by the Reserve within the exchange budget. */
    DIVIDEND,
    /** The principal of a Savings Bond, moving into the exchange account. */
    BOND_BUY,
    /** A Savings Bond's principal coming back, at maturity or when it is cashed in early. */
    BOND_REDEEM,
    /** A Savings Bond's interest, paid out of the exchange account at maturity. */
    BOND_INTEREST,
    /** A Savings Bond's interest set aside when it is bought: Reserve to exchange account, within the exchange budget. */
    BOND_INTEREST_RESERVED,
    /** Interest set aside for a bond that was cashed in early, going back to the Reserve. */
    BOND_INTEREST_RELEASED,
    /** A Merchant House's working capital topped up from the Reserve, within the NPC budget. */
    HOUSE_FUNDING,
    /** What a Merchant House holds above its working capital, swept back to the Reserve. */
    HOUSE_SWEEP;

    /** Whether this reason may create credits. */
    public boolean mints() {
        return this == RESERVE_MINT || this == MIGRATION || this == ADMIN_GRANT || this == ADMIN_SET;
    }

    /** Whether this reason may destroy credits. */
    public boolean burns() {
        return this == ADMIN_TAKE || this == ADMIN_SET;
    }
}
