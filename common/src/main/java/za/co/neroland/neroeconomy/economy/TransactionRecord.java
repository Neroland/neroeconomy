package za.co.neroland.neroeconomy.economy;

/**
 * Immutable audit record. Account references are one-way server-local pseudonyms, never names or
 * UUID strings; a system account appears under its stable name, such as {@code system:reserve}. An
 * empty source is a mint, an empty destination a burn, and either is also what an account that
 * opted out of logging leaves behind.
 */
public record TransactionRecord(TransactionType type, long amount, String currency, long timestamp,
        String sourceAccount, String destinationAccount) {

    public TransactionRecord {
        if (type == null || amount < 0L || currency == null || sourceAccount == null || destinationAccount == null) {
            throw new IllegalArgumentException("Invalid transaction record");
        }
    }
}
