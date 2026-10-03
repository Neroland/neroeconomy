package za.co.neroland.neroeconomy.market;

/**
 * The outcome of posting a market row: the reason, the row itself when it was posted, and the
 * listing fee that was charged for it.
 *
 * @param row        the new listing or buy offer; null unless {@code result} is {@link PostResult#SUCCESS}
 * @param feeCharged the listing fee the poster paid; 0 when nothing was posted or no fee applies
 */
public record Posted<T>(PostResult result, T row, long feeCharged) {
    static <T> Posted<T> refused(PostResult result) { return new Posted<>(result, null, 0L); }
    static <T> Posted<T> success(T row, long feeCharged) { return new Posted<>(PostResult.SUCCESS, row, feeCharged); }
}
