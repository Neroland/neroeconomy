package za.co.neroland.neroeconomy.house;

import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * The configured numbers of the Merchant Houses' auctions and jobs and of the server-funded
 * bounties, read once per step and handed to the pure rules ({@link HouseDeals}). The game builds
 * one with {@link #fromConfig()}; a test builds one with the numbers it wants.
 *
 * @param auctionsPerDay   {@code npcAuctionsPerDay}: auctions the Houses start per economy-day, server-wide
 * @param auctionMillis    {@code npcAuctionHours}: how long one runs
 * @param jobsEnabled      {@code systemJobsEnabled}: whether any server-funded job may be posted
 * @param jobPremiumBps    {@code houseJobPremiumPercent}, in hundredths of a percent: what a House's
 *                         job pays above its current bid for the goods
 * @param jobsPerHouse     {@code houseJobsPerHouse}: open jobs one House may have
 * @param jobMillis        {@code houseJobHours}: how long a House's job stays open
 * @param bountiesPerDay   {@code systemBountiesPerDay}: server-funded bounties per economy-day, and open at once
 * @param bountyMillis     how long a server-funded bounty stays open: the same as a House's job
 */
public record HouseDealSettings(int auctionsPerDay, long auctionMillis, boolean jobsEnabled, int jobPremiumBps,
        int jobsPerHouse, long jobMillis, int bountiesPerDay, long bountyMillis) {
    private static final long HOUR = 3_600_000L;
    public static final HouseDealSettings DEFAULTS = new HouseDealSettings(4, 24L * HOUR, true, 1_500, 2, 24L * HOUR, 2,
            24L * HOUR);

    public HouseDealSettings {
        if (auctionsPerDay < 0 || auctionMillis < 1L || jobPremiumBps < 0 || jobsPerHouse < 0 || jobMillis < 1L
                || bountiesPerDay < 0 || bountyMillis < 1L) throw new IllegalArgumentException("house deal settings");
    }

    /** The settings in effect now; read on every call, so a configuration reload applies at the next step. */
    public static HouseDealSettings fromConfig() {
        long jobs = EconomyConfig.HOUSE_JOB_HOURS.get() * HOUR;
        return new HouseDealSettings(EconomyConfig.NPC_AUCTIONS_PER_DAY.get(), EconomyConfig.NPC_AUCTION_HOURS.get() * HOUR,
                EconomyConfig.SYSTEM_JOBS_ENABLED.get(),
                (int) Math.round(EconomyConfig.HOUSE_JOB_PREMIUM_PERCENT.get() * 100.0D),
                EconomyConfig.HOUSE_JOBS_PER_HOUSE.get(), jobs, EconomyConfig.SYSTEM_BOUNTIES_PER_DAY.get(), jobs);
    }
}
