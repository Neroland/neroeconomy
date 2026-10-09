package za.co.neroland.neroeconomy.economy;

import java.util.List;
import java.util.UUID;

/**
 * Immutable ledger part of a data-subject export. {@code accountReference} is the subject's own
 * pseudonym as it appears in {@code transactions}; no other account's pseudonym is carried.
 * {@code chipDay}/{@code chipCount} are the Credit Chip counter (both zero when none is stored).
 * {@code lockedStartingBalance} is what is left of a starting grant that has not vested yet, and
 * {@code vested} whether the account has reached the play time that ends that lock.
 * {@code handbookGiven} is whether the account was given the Trader's Handbook on joining.
 */
public record PlayerLedgerExport(UUID player, long balance, String currency, boolean transactionLoggingOptOut,
        List<TransactionRecord> transactions, String accountReference, long chipDay, int chipCount,
        long lockedStartingBalance, boolean vested, boolean handbookGiven) {
    public PlayerLedgerExport { transactions = List.copyOf(transactions); }

    /** An export for an account that was not given the Trader's Handbook. */
    public PlayerLedgerExport(UUID player, long balance, String currency, boolean transactionLoggingOptOut,
            List<TransactionRecord> transactions, String accountReference, long chipDay, int chipCount,
            long lockedStartingBalance, boolean vested) {
        this(player, balance, currency, transactionLoggingOptOut, transactions, accountReference, chipDay,
                chipCount, lockedStartingBalance, vested, false);
    }

    /** An export for an account with nothing locked. */
    public PlayerLedgerExport(UUID player, long balance, String currency, boolean transactionLoggingOptOut,
            List<TransactionRecord> transactions, String accountReference, long chipDay, int chipCount) {
        this(player, balance, currency, transactionLoggingOptOut, transactions, accountReference, chipDay,
                chipCount, 0L, true, false);
    }
}
