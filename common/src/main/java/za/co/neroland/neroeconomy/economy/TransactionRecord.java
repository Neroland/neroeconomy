package za.co.neroland.neroeconomy.economy;

/**
 * Immutable audit record. Account references are one-way server-local pseudonyms, never names or
 * UUID strings. Empty source/destination denotes the server mint/burn account.
 */
public record TransactionRecord(TransactionType type, long amount, String currency, long timestamp,
        String sourceAccount, String destinationAccount) {

    public TransactionRecord {
        if (type == null || amount < 0L || currency == null || sourceAccount == null || destinationAccount == null) {
            throw new IllegalArgumentException("Invalid transaction record");
        }
    }
}
