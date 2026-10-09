package za.co.neroland.neroeconomy.obligation;

import java.util.UUID;

/**
 * What a bid on a Merchant House's auction did.
 *
 * @param displaced the bidder whose standing bid this one beat, now owed it back as a credit claim;
 *                  null when there was none or it was the same bidder
 */
public record HouseBid(Result result, UUID displaced) {
    public enum Result {
        PLACED, FROZEN, NOT_FOUND, ENDED, TOO_LOW, ORBIT, VAULT_FULL, REFUND_BLOCKED, FUNDS,
        /** An operator has closed the market. */
        MARKET_CLOSED
    }

    public static HouseBid refused(Result result) { return new HouseBid(result, null); }

    public boolean ok() { return result == Result.PLACED; }
}
