package za.co.neroland.neroeconomy.obligation;

import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * The configured terms every auction runs under, read once per operation and handed to the pure
 * rules. The game builds them with {@link #fromConfig()}; a test builds its own.
 *
 * @param feePercent          {@code auctionFeePercent}: on the winning bid, out of the seller's proceeds
 * @param minIncrementPercent {@code auctionMinIncrementPercent}: how far a bid must beat the standing one
 * @param antiSnipeMillis     {@code auctionAntiSnipeSeconds}: a bid this close to the end extends it to this
 */
public record AuctionTerms(double feePercent, int minIncrementPercent, long antiSnipeMillis) {
    public static final AuctionTerms DEFAULTS = new AuctionTerms(5.0D, 5, 60_000L);

    public AuctionTerms {
        if (!Double.isFinite(feePercent) || feePercent < 0.0D || minIncrementPercent < 0 || antiSnipeMillis < 0L)
            throw new IllegalArgumentException("auction terms");
    }

    public static AuctionTerms fromConfig() {
        return new AuctionTerms(EconomyConfig.AUCTION_FEE_PERCENT.get(), EconomyConfig.AUCTION_MIN_INCREMENT_PERCENT.get(),
                EconomyConfig.AUCTION_ANTI_SNIPE_SECONDS.get() * 1000L);
    }
}
