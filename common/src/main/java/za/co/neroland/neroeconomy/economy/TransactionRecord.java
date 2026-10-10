package za.co.neroland.neroeconomy.economy;

/**
 * Immutable audit record. Account references are one-way server-local pseudonyms, never names or
 * UUID strings; a system account appears under its stable name, such as {@code system:reserve}. An
 * empty source is a mint, an empty destination a burn, and either is also what an account that
 * opted out of logging leaves behind.
 */
public record TransactionRecord(TransactionType type, long amount, String currency, long timestamp,
        String sourceAccount, String destinationAccount, String origin) {

    /**
     * @param origin which other mod's call made the row and why, as {@code <modid>/<reason>} (see
     *               {@code SiblingRules.validOrigin}); empty for every row this mod makes on its own
     *               and for every row saved before the field existed. Never personal.
     */
    public TransactionRecord {
        if (type == null || amount < 0L || currency == null || sourceAccount == null || destinationAccount == null) {
            throw new IllegalArgumentException("Invalid transaction record");
        }
        if (origin == null) origin = "";
    }

    /** A row with no origin: everything this mod records on its own. */
    public TransactionRecord(TransactionType type, long amount, String currency, long timestamp,
            String sourceAccount, String destinationAccount) {
        this(type, amount, currency, timestamp, sourceAccount, destinationAccount, "");
    }
}
