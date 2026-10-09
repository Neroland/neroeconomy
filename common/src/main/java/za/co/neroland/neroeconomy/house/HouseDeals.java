package za.co.neroland.neroeconomy.house;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.HouseAuction;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimRandom;

/**
 * The Merchant Houses' auctions and jobs, decided once per simulation step. Pure: it reads the
 * store and the step, takes its randomness from the step's streams and acts through the obligation
 * section's rules, so a replayed step does exactly what a live one would have.
 *
 * <ul>
 *   <li><b>Auctions.</b> Occasionally — on average {@code npcAuctionsPerDay} times an economy-day,
 *       never more, server-wide — a House auctions a lot from its own stock: a few units of a rare or
 *       valuable commodity, or a bundle (up to a stack) of a staple. The minimum bid is what the
 *       House would pay for the lot at its reference price; duration, fee, minimum increment and
 *       anti-snipe are an ordinary auction's. The goods leave the stock when the auction starts and
 *       come back if nobody bids. A lot of space goods is interplanetary.</li>
 *   <li><b>Jobs.</b> A House whose stock of a commodity it buys has fallen under a quarter of a
 *       day's supply, while it still buys that commodity today, posts a contract (or, where the
 *       world has a delivery point, sometimes a delivery) for what it is short of: the quantity
 *       times its current bid plus {@code houseJobPremiumPercent}, escrowed from the Reserve under
 *       the system-job budget. At most {@code houseJobsPerHouse} open per House, one per commodity.</li>
 *   <li><b>Bounties.</b> On average {@code systemBountiesPerDay} times a day, never more, a
 *       server-funded bounty on one of the hostile mobs of the bounty table, likewise escrowed.</li>
 * </ul>
 */
public final class HouseDeals {
    /** The most units of a rare commodity in one lot. */
    public static final int RARE_LOT = 16;
    /** A bundle of a staple: at most a stack. */
    public static final int STAPLE_LOT = 64;
    /** A staple is only auctioned when the House holds at least this many of it. */
    public static final int STAPLE_MIN_STOCK = 32;
    /** A commodity at or above this reference price, in credits, counts as valuable. */
    public static final long VALUABLE_PRICE = 50L;
    /** The most units one House job asks for. */
    public static final int JOB_LOT = 64;
    private static final long DAY = 86_400_000L;
    private static final long BPS = 10_000L;

    private HouseDeals() { }

    /** What one step did. */
    public record Report(HouseAuction auction, List<EconomyJob> jobs, EconomyJob bounty) {
        public static final Report NONE = new Report(null, List.of(), null);
    }

    /** One step of the Houses' auctions and jobs. Does nothing while the economy is frozen. */
    public static Report step(SimContext context, HouseDealsEnv env) {
        EconomyStore store = context.store();
        if (env == null || EconomyHealth.frozen() || store == null || store.marketClosed()) return Report.NONE;
        long now = context.deadlineTime();
        long stepsPerDay = Math.max(1L, DAY / Math.max(1L, context.stepMillis()));
        HouseAuction auction = auction(store, env, now, stepsPerDay, context.random("auction"));
        List<EconomyJob> jobs = jobs(store, env, now, context);
        EconomyJob bounty = bounty(store, env, now, stepsPerDay, context.random("bounty"));
        return new Report(auction, jobs, bounty);
    }

    /** Settles what fell due: House auctions that ended, jobs past expiry. Housekeeping: always runs. */
    public static List<UUID> settle(SimContext context, HouseDealsEnv env) {
        if (env == null) return List.of();
        List<UUID> winners = context.obligations().settleDue(env.houses().trade(), env.terms(), context.deadlineTime());
        for (UUID winner : winners)
            env.houses().trade().alerts().raise(new PlayerAccountId(winner), EconomyAlerts.Kind.AUCTION_WON);
        return winners;
    }

    // --- auctions ----------------------------------------------------------------------------

    /** A lot a House could auction now. */
    record Candidate(HouseSection.Position position, int count) { }

    static List<Candidate> candidates(EconomyStore store, HouseDealsEnv env) {
        List<Candidate> out = new ArrayList<>();
        for (HouseSection.Position position : store.houses().positions()) {
            CommodityDefinition commodity = env.houses().commodities().get(position.key());
            if (commodity == null || position.stock() < 1L || env.houses().houses().get(position.house()) == null) continue;
            boolean valuable = commodity.commodityClass() == CommodityClass.RARE
                    || commodity.referencePrice() >= VALUABLE_PRICE;
            if (valuable) out.add(new Candidate(position, (int) Math.min(position.stock(),
                    Math.max(1L, Math.min(RARE_LOT, position.supply() / 4L)))));
            else if (commodity.commodityClass() == CommodityClass.STAPLE && position.stock() >= STAPLE_MIN_STOCK)
                out.add(new Candidate(position, (int) Math.min(STAPLE_LOT, position.stock() / 2L)));
        }
        return out;
    }

    private static HouseAuction auction(EconomyStore store, HouseDealsEnv env, long now, long stepsPerDay,
            SimRandom random) {
        int perDay = env.settings().auctionsPerDay();
        ObligationSavedData obligations = store.obligations();
        if (perDay <= 0 || obligations.npcAuctionsOn(now) >= perDay) return null;
        // On average perDay a day: each step starts one with that share of a day's steps.
        if (random.nextLong(stepsPerDay) >= perDay) return null;
        List<Candidate> candidates = candidates(store, env);
        if (candidates.isEmpty()) return null;
        Candidate pick = candidates.get(random.nextInt(candidates.size()));
        HouseSection.Position position = pick.position();
        Lot lot = store.houses().takeStock(position.key(), position.item(), pick.count());
        if (lot == null) return null;
        long minimum = Math.max(1L, multiply(Math.max(1L, position.bidAtReference()), lot.count()));
        CommodityDefinition commodity = env.houses().commodities().get(position.key());
        boolean space = commodity != null && "space".equals(commodity.sector());
        HouseAuction auction = obligations.startHouseAuction(position.house(), lot, minimum,
                now + env.settings().auctionMillis(), space, now, perDay);
        if (auction == null) store.houses().returnStock(position.house(), lot);
        return auction;
    }

    // --- jobs --------------------------------------------------------------------------------

    private static List<EconomyJob> jobs(EconomyStore store, HouseDealsEnv env, long now, SimContext context) {
        HouseDealSettings settings = env.settings();
        if (!settings.jobsEnabled() || settings.jobsPerHouse() <= 0) return List.of();
        List<EconomyJob> posted = new ArrayList<>();
        for (HouseSection.Position position : store.houses().positions()) {
            if (!position.buys() || position.bid() < 1L || position.supply() <= 0L || position.demandLeft() <= 0L) continue;
            // Well under target: under a quarter of a day's supply in stock and on the book.
            if (position.held() * 4L >= position.supply()) continue;
            if (env.houses().houses().get(position.house()) == null) continue;
            long quantity = Math.min(Math.min(position.demandLeft(), position.supply() - position.held()), JOB_LOT);
            if (quantity < 1L) continue;
            long payout = payout(quantity, position.bid(), settings.jobPremiumBps());
            JobType type = !env.destination().isEmpty() && context.random("job:" + position.key()).nextBoolean()
                    ? JobType.DELIVERY : JobType.CONTRACT;
            EconomyJob job = store.obligations().postHouseJob(position.house(), type, position.item(), (int) quantity,
                    type == JobType.DELIVERY ? env.destination() : "", payout, now + settings.jobMillis(), now, true,
                    settings.jobsPerHouse());
            if (job != null) posted.add(job);
        }
        return posted;
    }

    /** {@code quantity × bid}, plus the premium, rounded up; exact. */
    public static long payout(long quantity, long bid, int premiumBps) {
        long base = multiply(quantity, bid);
        long premium = Math.ceilDiv(multiply(base, Math.max(0, premiumBps)), BPS);
        return base > Long.MAX_VALUE - premium ? Long.MAX_VALUE : base + premium;
    }

    // --- bounties ----------------------------------------------------------------------------

    private static EconomyJob bounty(EconomyStore store, HouseDealsEnv env, long now, long stepsPerDay,
            SimRandom random) {
        int perDay = env.settings().bountiesPerDay();
        List<BountyTable.Entry> table = env.bounties().entries();
        if (perDay <= 0 || !env.settings().jobsEnabled() || table.isEmpty()
                || store.obligations().bountiesOn(now) >= perDay) return null;
        if (random.nextLong(stepsPerDay) >= perDay) return null;
        BountyTable.Entry entry = table.get(random.nextInt(table.size()));
        return store.obligations().postSystemBounty(entry.entity(), entry.quantity(), entry.payout(),
                now + env.settings().bountyMillis(), now, true, perDay);
    }

    private static long multiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
