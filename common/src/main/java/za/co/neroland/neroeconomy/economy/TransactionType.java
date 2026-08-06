package za.co.neroland.neroeconomy.economy;

/** Auditable reasons for a balance mutation. Deferred systems can add types without changing Core. */
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
    CONTRACT_PAYOUT,
    BOUNTY_PAYOUT,
    AUCTION_BID,
    AUCTION_PAYOUT,
    CREDIT_CHIP_REDEMPTION
}
