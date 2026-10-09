package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.house.BountyTable;
import za.co.neroland.neroeconomy.house.HouseDeals;
import za.co.neroland.neroeconomy.house.HouseSection;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.FulfillResult;
import za.co.neroland.neroeconomy.obligation.HouseAuction;
import za.co.neroland.neroeconomy.obligation.HouseBid;
import za.co.neroland.neroeconomy.obligation.JobState;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationRules;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/**
 * The fuzzer's operations on the Merchant Houses' auctions and jobs and the server's bounties,
 * against the real obligation section. The {@code house_deals} step starts auctions and posts jobs
 * and bounties on its own (see {@link FuzzWorld#dealsEnv}) and {@code house_deals_settle} settles
 * them; here a House also starts and posts directly — sometimes with arguments that must be
 * refused — players bid, claim, release, fulfil and kill, and the server tick settles what fell due.
 *
 * <p>Nothing here needs a model of its own: a House auction's lot is a lot the store holds (the
 * section is a {@code LotHolder}), a standing bid and a job's payout are escrow the section records
 * (it is an {@code EscrowHolder}), and House stock is counted with the store. Invariants A, B and C
 * and item conservation cover every step as they are. The only goods that cross the store's edge
 * are what a worker hands over for a House's job: from outside into the House's stock.
 */
final class DealOperations {
    private DealOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new StartAuction(), new Bid(), new Settle(), new PostJob(), new Claim(), new Release(),
                new Fulfil(), new PostBounty(), new Kill());
    }

    /** A House starts an auction straight from its stock, as the step does, and gives the lot back when refused. */
    static final class StartAuction implements FuzzOperation {
        @Override public String name() { return "deal_auction_start"; }
        @Override public int weight() { return 4; }
        @Override public long run(FuzzWorld world) {
            HouseSection houses = world.store.houses();
            List<HouseSection.Position> stocked = new ArrayList<>();
            for (HouseSection.Position position : houses.positions()) if (position.stock() > 0L) stocked.add(position);
            if (stocked.isEmpty()) return 0L;
            HouseSection.Position position = stocked.get(world.random.nextInt(stocked.size()));
            long now = world.ledger().now();
            Lot lot = houses.takeStock(position.key(), position.item(),
                    1 + world.random.nextInt((int) Math.min(position.stock(), 4L)));
            assertNotNull(lot);
            long minimum = world.random.nextInt(10) == 0 ? 0L : Math.max(1L, position.bidAtReference()) * lot.count();
            long endsAt = world.random.nextInt(12) == 0 ? now - 1L : now + 60_000L + world.random.nextInt(4 * 3_600_000);
            HouseAuction auction = world.obligations().startHouseAuction(position.house(), lot, minimum, endsAt,
                    world.random.nextInt(4) == 0, now, FuzzWorld.DEAL_SETTINGS.auctionsPerDay());
            if (auction == null) {
                houses.returnStock(position.house(), lot);
                return 0L;
            }
            assertEquals(lot, auction.lot());
            assertNull(auction.bidderId());
            return lot.count();
        }
    }

    /** A player bids on a House's lot: mostly enough, sometimes too little, too much or on a lot that ended. */
    static final class Bid implements FuzzOperation {
        @Override public String name() { return "deal_auction_bid"; }
        @Override public int weight() { return 40; }
        @Override public long run(FuzzWorld world) {
            ObligationSavedData obligations = world.obligations();
            List<HouseAuction> pool = world.random.nextInt(10) == 0 ? obligations.houseAuctionHistory()
                    : obligations.houseAuctions(true);
            UUID id = pool.isEmpty() ? new UUID(7L, world.random.nextInt(4)) : pool.get(world.random.nextInt(pool.size())).id();
            HouseAuction before = obligations.houseAuction(id);
            UUID bidder = world.player();
            long next = before == null ? 1L : ObligationRules.minimumNextBid(before.minimumBid(), before.highestBid(),
                    FuzzWorld.AUCTION_TERMS.minIncrementPercent());
            long amount = switch (world.random.nextInt(8)) {
                case 0 -> next - 1L;
                case 1 -> next + world.random.nextInt(2_000);
                case 2 -> 1_000_000L + world.random.nextInt(1_000);
                default -> next + world.random.nextInt(20);
            };
            UUID standing = before == null ? null : before.bidderId();
            long standingClaim = standing == null ? 0L : world.store.market().creditClaimTotal(standing);
            HouseBid bid = obligations.bidHouseAuction(world.env, FuzzWorld.AUCTION_TERMS, bidder, id, amount,
                    world.ledger().now(), world.random.nextInt(3) != 0);
            HouseAuction after = obligations.houseAuction(id);
            if (!bid.ok()) {
                assertNull(bid.displaced());
                assertEquals(before, after, "a refused bid changed the lot");
                return 0L;
            }
            assertEquals(amount, after.highestBid());
            assertEquals(bidder, after.bidderId());
            assertTrue(after.endsAt() >= before.endsAt());
            if (standing != null && !standing.equals(bidder)) {
                // The bid it beat is owed back to its bidder, in full.
                assertEquals(standing, bid.displaced());
                assertEquals(standingClaim + before.highestBid(), world.store.market().creditClaimTotal(standing));
            } else assertNull(bid.displaced());
            return amount;
        }
    }

    /**
     * The server tick's settlement of what fell due: ended House auctions go to their winner (fee
     * to the Reserve, the rest to the House) or back to the House's stock, and server-funded jobs
     * past their expiry hand their escrow back to the Reserve. The step does the same each step.
     */
    static final class Settle implements FuzzOperation {
        @Override public String name() { return "deal_settle"; }
        @Override public int weight() { return 15; }
        @Override public long run(FuzzWorld world) {
            ObligationSavedData obligations = world.obligations();
            long now = world.ledger().now();
            List<HouseAuction> due = new ArrayList<>();
            for (HouseAuction auction : obligations.houseAuctions(true)) if (auction.endsAt() <= now) due.add(auction);
            List<EconomyJob> expiring = new ArrayList<>();
            for (EconomyJob job : obligations.systemJobs(null)) if (job.expiresAt() <= now) expiring.add(job);
            List<UUID> winners = obligations.settleDue(world.env, FuzzWorld.AUCTION_TERMS, now);
            long sold = 0L;
            for (HouseAuction auction : due) {
                HouseAuction after = obligations.houseAuction(auction.id());
                if (after == null || after.open()) continue;
                if (auction.bidderId() == null) world.houseLotsReturned++;
                else {
                    assertTrue(winners.contains(auction.bidderId()));
                    world.houseLotsSold++;
                    sold++;
                }
            }
            assertEquals(sold, winners.size());
            long expired = 0L;
            for (EconomyJob job : expiring) {
                EconomyJob after = obligations.job(job.id());
                if (after != null && after.state() == JobState.EXPIRED) expired++;
            }
            world.systemJobsExpired += expired;
            return due.size() * 1_000L + sold * 31L + expired;
        }
    }

    /**
     * A House posts a job for goods it still buys today, no more than it still buys, funded from the
     * Reserve under the system-job budget. Looser than the step's "well under target" rule (which
     * {@code HouseDealsTest} covers), so posts are frequent here. Sometimes with arguments that must be refused.
     */
    static final class PostJob implements FuzzOperation {
        @Override public String name() { return "deal_job_post"; }
        @Override public int weight() { return 8; }
        @Override public long run(FuzzWorld world) {
            List<HouseSection.Position> buying = new ArrayList<>();
            for (HouseSection.Position position : world.store.houses().positions())
                if (position.buys() && position.demandLeft() > 0L)
                    buying.add(position);
            if (buying.isEmpty()) return 0L;
            HouseSection.Position position = buying.get(world.random.nextInt(buying.size()));
            long most = Math.min(position.demandLeft(), HouseDeals.JOB_LOT);
            int quantity = 1 + world.random.nextInt((int) Math.max(1L, most));
            long payout = world.random.nextInt(12) == 0 ? 0L
                    : HouseDeals.payout(quantity, Math.max(1L, position.bid()), FuzzWorld.DEAL_SETTINGS.jobPremiumBps());
            JobType type = switch (world.random.nextInt(9)) {
                case 0 -> JobType.BOUNTY;
                case 1, 2, 3 -> JobType.DELIVERY;
                default -> JobType.CONTRACT;
            };
            long now = world.ledger().now();
            long budget = world.ledger().budgetUsed(BudgetKind.SYSTEM_JOB);
            EconomyJob job = world.obligations().postHouseJob(position.house(), type, position.item(), quantity,
                    type == JobType.DELIVERY ? FuzzWorld.DESTINATION : "", payout,
                    now + 60_000L + world.random.nextInt(4 * 3_600_000), now, world.random.nextInt(10) != 0,
                    FuzzWorld.DEAL_SETTINGS.jobsPerHouse());
            if (job == null) {
                assertEquals(budget, world.ledger().budgetUsed(BudgetKind.SYSTEM_JOB));
                return 0L;
            }
            assertEquals(position.house(), world.obligations().houseOf(job.id()));
            assertEquals(ObligationSavedData.SYSTEM_ACCOUNT, job.issuer());
            assertEquals(budget + payout, world.ledger().budgetUsed(BudgetKind.SYSTEM_JOB));
            return payout;
        }
    }

    /** A player claims an open server-funded job: a House's contract or delivery, or a bounty. */
    static final class Claim implements FuzzOperation {
        @Override public String name() { return "deal_job_claim"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            List<EconomyJob> open = world.obligations().systemJobs(null);
            if (open.isEmpty()) return 0L;
            EconomyJob job = open.get(world.random.nextInt(open.size()));
            UUID player = world.player();
            boolean claimed = world.obligations().claim(job.id(), player, world.ledger().now());
            if (claimed) assertEquals(player, world.obligations().job(job.id()).claimantId());
            return claimed ? 1L : 0L;
        }
    }

    /** A claimant hands a job back (or somebody else tries to). */
    static final class Release implements FuzzOperation {
        @Override public String name() { return "deal_job_release"; }
        @Override public int weight() { return 4; }
        @Override public long run(FuzzWorld world) {
            List<EconomyJob> claimed = new ArrayList<>();
            for (EconomyJob job : world.obligations().systemJobs(null)) if (job.state() == JobState.CLAIMED) claimed.add(job);
            if (claimed.isEmpty()) return 0L;
            EconomyJob job = claimed.get(world.random.nextInt(claimed.size()));
            UUID player = world.random.nextInt(6) == 0 ? world.player() : job.claimantId();
            boolean released = world.obligations().release(job.id(), player);
            assertEquals(player.equals(job.claimantId()), released);
            return released ? 1L : 0L;
        }
    }

    /**
     * The claimant of a House's job hands over the goods — gathering what they lack first — and the
     * goods go into the House's stock (destroyed and counted if the House no longer trades them),
     * while the payout becomes the worker's credit claim. Sometimes the wrong worker or the wrong count.
     */
    static final class Fulfil implements FuzzOperation {
        @Override public String name() { return "deal_job_fulfil"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            ObligationSavedData obligations = world.obligations();
            List<EconomyJob> claimed = new ArrayList<>();
            for (EconomyJob job : obligations.systemJobs(null))
                if (job.state() == JobState.CLAIMED && obligations.houseOf(job.id()) != null) claimed.add(job);
            if (claimed.isEmpty()) return 0L;
            EconomyJob job = claimed.get(world.random.nextInt(claimed.size()));
            UUID worker = world.random.nextInt(8) == 0 ? world.player() : job.claimantId();
            int count = world.random.nextInt(8) == 0 ? Math.max(1, job.quantity() + world.random.nextInt(3) - 1)
                    : job.quantity();
            Lot.Key kind = new Lot.Key(job.target(), Lot.PRISTINE);
            long missing = count - world.outside(kind);
            if (missing > 0L) world.create(kind, missing);
            List<Lot> goods = new ArrayList<>();
            int limit = FuzzWorld.LIMIT.limit(kind.item(), kind.payload());
            for (int left = count; left > 0; left -= limit) goods.add(Lot.of(kind.item(), Math.min(limit, left)));
            long claim = world.store.market().creditClaimTotal(worker);
            FulfillResult result = obligations.fulfilHouseJob(job.id(), worker, goods);
            if (result != FulfillResult.SUCCESS) {
                assertEquals(job, obligations.job(job.id()), "a refused fulfilment changed the job");
                return 0L;
            }
            assertEquals(job.quantity(), count);
            assertEquals(worker, job.claimantId());
            world.intoStore(kind, count);
            assertEquals(JobState.FULFILLED, obligations.job(job.id()).state());
            assertEquals(claim + job.payout(), world.store.market().creditClaimTotal(worker));
            return job.payout();
        }
    }

    /** The server posts a bounty on a mob of its table, funded like any system job; sometimes on one it may not. */
    static final class PostBounty implements FuzzOperation {
        @Override public String name() { return "deal_bounty_post"; }
        @Override public int weight() { return 5; }
        @Override public long run(FuzzWorld world) {
            List<BountyTable.Entry> table = FuzzWorld.BOUNTIES.entries();
            BountyTable.Entry entry = table.get(world.random.nextInt(table.size()));
            String entity = switch (world.random.nextInt(10)) {
                case 0 -> "minecraft:player";
                case 1 -> "#minecraft:skeletons";
                default -> entry.entity();
            };
            long now = world.ledger().now();
            EconomyJob bounty = world.obligations().postSystemBounty(entity, entry.quantity(), entry.payout(),
                    now + 60_000L + world.random.nextInt(4 * 3_600_000), now, world.random.nextInt(10) != 0,
                    FuzzWorld.DEAL_SETTINGS.bountiesPerDay());
            if (bounty == null) return 0L;
            assertEquals(entry.entity(), bounty.target());
            assertTrue(world.obligations().bountiesOn(now) <= FuzzWorld.DEAL_SETTINGS.bountiesPerDay());
            return bounty.payout();
        }
    }

    /** A player kills a mob: every bounty on it they may work counts the kill, and a complete one pays. */
    static final class Kill implements FuzzOperation {
        @Override public String name() { return "deal_bounty_kill"; }
        @Override public int weight() { return 25; }
        @Override public long run(FuzzWorld world) {
            List<BountyTable.Entry> table = FuzzWorld.BOUNTIES.entries();
            String entity = world.random.nextInt(6) == 0 ? "minecraft:pig"
                    : table.get(world.random.nextInt(table.size())).entity();
            UUID killer = world.player();
            long claim = world.store.market().creditClaimTotal(killer);
            List<EconomyJob> before = world.obligations().systemJobs(JobType.BOUNTY);
            int completed = world.obligations().recordKill(world.store.market(), killer, entity, "", world.ledger().now());
            long paid = 0L;
            for (EconomyJob job : before) {
                EconomyJob after = world.obligations().job(job.id());
                if (after != null && after.state() == JobState.FULFILLED) {
                    assertEquals(killer, after.claimantId());
                    paid += job.payout();
                }
            }
            assertEquals(claim + paid, world.store.market().creditClaimTotal(killer));
            return completed == 0 ? 0L : paid;
        }
    }

    /** The erasure of {@code player} from the obligations, as the fuzz erase operation runs it. */
    static void erased(FuzzWorld world, UUID player) {
        ObligationSavedData obligations = world.obligations();
        long escrow = world.ledger().balance(za.co.neroland.neroeconomy.api.SystemAccountId.ESCROW);
        long standing = 0L;
        for (HouseAuction auction : obligations.houseAuctions(true))
            if (player.equals(auction.bidderId())) standing += auction.highestBid();
        obligations.forgetPlayer(player);
        // Their standing bids left escrow (for the Reserve), and nothing about them is left in a row.
        assertEquals(escrow - standing, world.ledger().balance(za.co.neroland.neroeconomy.api.SystemAccountId.ESCROW));
        for (HouseAuction auction : obligations.houseAuctionHistory()) assertFalse(player.equals(auction.bidderId()));
        for (EconomyJob job : obligations.systemJobHistory()) assertFalse(player.equals(job.claimantId()));
    }
}
