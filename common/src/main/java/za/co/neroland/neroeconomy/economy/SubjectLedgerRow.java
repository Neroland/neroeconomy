package za.co.neroland.neroeconomy.economy;

/**
 * One audit row as its own data subject may be shown it: the reason, the amount, which way it moved
 * and when. It carries no account reference of any kind, so the other party of a two-sided row —
 * and even the subject's own pseudonym — cannot leave the ledger through it.
 *
 * @param type      the audit reason
 * @param amount    the unsigned amount, as stored
 * @param incoming  true when the subject received the amount, false when they paid it
 * @param currency  the currency id
 * @param timestamp economy time; convert with {@code CURRENCY_PROVIDER.toWallMillis} before it leaves
 *                  the server
 */
public record SubjectLedgerRow(TransactionType type, long amount, boolean incoming, String currency,
        long timestamp) {
    public SubjectLedgerRow {
        if (type == null || amount < 0L || currency == null) throw new IllegalArgumentException("Invalid ledger row");
    }

    /** The amount from the subject's side: positive when received, negative when paid. */
    public long signedAmount() { return incoming ? amount : -amount; }
}
