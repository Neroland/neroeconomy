package za.co.neroland.neroeconomy.economy;

import java.util.List;
import java.util.UUID;

/** Immutable data-subject export payload. */
public record PlayerLedgerExport(UUID player, long balance, String currency, boolean transactionLoggingOptOut,
        List<TransactionRecord> transactions) {
    public PlayerLedgerExport { transactions = List.copyOf(transactions); }
}
