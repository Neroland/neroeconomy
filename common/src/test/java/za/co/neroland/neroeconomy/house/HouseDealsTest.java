package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.obligation.AuctionTerms;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.FulfillResult;
import za.co.neroland.neroeconomy.obligation.HouseAuction;
import za.co.neroland.neroeconomy.obligation.HouseBid;
import za.co.neroland.neroeconomy.obligation.JobState;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationRules;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.simulation.SimContext;
import za.co.neroland.neroeconomy.simulation.SimTesting;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The Merchant Houses' auctions and jobs, on the real store with no game: an auction from a
 * House's stock through bids, outbids, anti-snipe and settlement (sold and unsold); a House's job
 * from posting under the system-job budget through fulfilment into the House's stock, and expiry;
 * the server's bounties; the per-day caps; and what the step itself decides.
 */
class HouseDealsTest {
    static final String EMERALD = "minecraft:emerald";
    static final String MOON_ROCK = "test:moon_rock";
    static final String COBBLE = "minecraft:cobblestone";
    static final AuctionTerms TERMS = AuctionTerms.DEFAULTS;

    final HouseWorld houses = new HouseWorld(List.of(HouseWorld.wheat(), emerald(), moonRock(), cobble()),
            List.of(HouseWorld.house("growers", 4_000L), HouseWorld.house("general", 10_000L),
                    HouseWorld.house("masons", 2_000L)));
    final TradeWorld world = houses.world;

    @AfterEach void close() { world.close(); }

    static CommodityDefinition emerald() {
        return new CommodityDefinition(EMERALD, CommodityDefinition.DIRECT_FILE, 80L, 32L, 32L, 8L, 8L, 0.8D, 0.01D,
                1_000, CommodityClass.RARE, "general", "", true, true);
    }

    static CommodityDefinition moonRock() {
        return new CommodityDefinition(MOON_ROCK, CommodityDefinition.DIRECT_FILE, 60L, 16L, 16L, 4L, 4L, 0.8D, 0.01D,
                1_000, CommodityClass.RARE, "general", "space", true, true);
    }

    static CommodityDefinition cobble() {
        return new CommodityDefinition(COBBLE, CommodityDefinition.DIRECT_FILE, 2L, 512L, 512L, 128L, 128L, 0.4D, 0.04D,
                1_000, CommodityClass.STAPLE, "masons", "building", true, true);
    }

    ObligationSavedData obligations() { return world.store.obligations(); }

    HouseDealsEnv env(HouseDealSettings settings) {
        return new HouseDealsEnv(houses.env(), TERMS, settings, BountyTable.EMPTY, "");
    }

    /** The step the clock is in now, as the simulation would run it. */
    SimContext context() {
        long now = world.now();
        return SimTesting.context(world.store, HouseDealsStep.DEALS, Math.floorDiv(now, HouseWorld.STEP), HouseWorld.STEP,
                now, true);
    }

    long stock(String key) {
        for (HouseSection.Position position : houses.section().positions())
            if (position.key().equals(key)) return position.stock();
        return -1L;
    }

    long goodsInStore(String item) {
        long[] total = new long[1];
        world.store.forEachLot(lot -> { if (lot.item().equals(item)) total[0] += lot.count(); });
        return total[0];
    }

    HouseAuction start(String key, String house, int count, boolean space) {
        long now = world.now();
        Lot lot = houses.section().takeStock(key, key, count);
        assertNotNull(lot);
        HouseAuction auction = obligations().startHouseAuction(house, lot, 4L * 72L, now + TradeWorld.DAY, space, now, 4);
        assertNotNull(auction);
        return auction;
    }

    @Test void anAuctionFromStockSellsToTheHighestBidderAndPaysTheHouse() {
        houses.steps(6);
        long before = stock(EMERALD);
        long goods = goodsInStore(EMERALD);
        assertTrue(before >= 4L);
        HouseAuction auction = start(EMERALD, "general", 4, false);
        // The goods left the stock for the auction: still in the store, counted once.
        assertEquals(before - 4L, stock(EMERALD));
        assertEquals(goods, goodsInStore(EMERALD));
        UUID first = houses.player(1, 2_000L);
        UUID second = houses.player(2, 2_000L);
        long now = world.now();
        assertEquals(HouseBid.Result.TOO_LOW, obligations().bidHouseAuction(world.env, TERMS, first, auction.id(),
                auction.minimumBid() - 1L, now, true).result());
        assertTrue(obligations().bidHouseAuction(world.env, TERMS, first, auction.id(), auction.minimumBid(), now, true).ok());
        assertEquals(2_000L - auction.minimumBid(), world.balance(first));
        assertTrue(world.invariants());
        long next = ObligationRules.minimumNextBid(auction.minimumBid(), auction.minimumBid(), TERMS.minIncrementPercent());
        assertEquals(HouseBid.Result.TOO_LOW, obligations().bidHouseAuction(world.env, TERMS, second, auction.id(),
                next - 1L, now, true).result());
        HouseBid outbid = obligations().bidHouseAuction(world.env, TERMS, second, auction.id(), next, now, true);
        assertTrue(outbid.ok());
        assertEquals(first, outbid.displaced());
        // The displaced bid is owed back to its bidder; nothing of it was lost.
        assertEquals(auction.minimumBid(), world.claim(first));
        assertTrue(world.invariants());
        // Raising one's own bid pays only the difference.
        long raise = ObligationRules.minimumNextBid(auction.minimumBid(), next, TERMS.minIncrementPercent());
        HouseBid again = obligations().bidHouseAuction(world.env, TERMS, second, auction.id(), raise, now, true);
        assertTrue(again.ok());
        assertNull(again.displaced());
        assertEquals(2_000L - raise, world.balance(second));
        // Anti-snipe: a bid in the last minute pushes the end out.
        world.wall = auction.endsAt() - 30_000L;
        long late = world.now();
        long top = ObligationRules.minimumNextBid(auction.minimumBid(), raise, TERMS.minIncrementPercent());
        assertTrue(obligations().bidHouseAuction(world.env, TERMS, first, auction.id(), top, late, true).ok());
        assertEquals(late + TERMS.antiSnipeMillis(), obligations().houseAuction(auction.id()).endsAt());
        assertEquals(raise, world.claim(second));
        // Nothing settles before the end.
        assertTrue(obligations().settleDue(world.env, TERMS, late + 1_000L).isEmpty());
        long reserve = world.reserve();
        long house = houses.balance("general");
        List<UUID> winners = obligations().settleDue(world.env, TERMS, late + TERMS.antiSnipeMillis());
        assertEquals(List.of(first), winners);
        long fee = za.co.neroland.neroeconomy.economy.Fees.fee(top, TERMS.feePercent());
        assertEquals(reserve + fee, world.reserve());
        assertEquals(house + top - fee, houses.balance("general"));
        assertEquals(4L, world.held(first, EMERALD));
        assertEquals(JobState.FULFILLED, obligations().houseAuction(auction.id()).state());
        assertEquals(goods, goodsInStore(EMERALD));
        assertTrue(world.invariants());
        // Settled once only.
        assertTrue(obligations().settleDue(world.env, TERMS, late + TradeWorld.DAY).isEmpty());
        assertEquals(4L, world.held(first, EMERALD));
    }

    @Test void anUnsoldLotGoesBackToStockAndANewDayCountsAfresh() {
        houses.steps(6);
        long before = stock(EMERALD);
        HouseAuction auction = start(EMERALD, "general", 3, false);
        assertEquals(1, obligations().npcAuctionsOn(world.now()));
        assertTrue(obligations().settleDue(world.env, TERMS, auction.endsAt()).isEmpty());
        assertEquals(before, stock(EMERALD));
        assertEquals(JobState.FULFILLED, obligations().houseAuction(auction.id()).state());
        assertTrue(world.invariants());
        // The day's cap is server-wide and per economy-day.
        long now = world.now();
        for (int index = 0; index < 3; index++) start(EMERALD, "general", 1, false);
        assertNull(obligations().startHouseAuction("general", Lot.of(EMERALD, 1), 10L, now + 1_000L, false, now, 4));
        assertEquals(0, obligations().npcAuctionsOn(now + TradeWorld.DAY));
        assertNotNull(obligations().startHouseAuction("general", Lot.of(EMERALD, 1), 10L, now + TradeWorld.DAY * 2,
                false, now + TradeWorld.DAY, 4));
    }

    @Test void anInterplanetaryLotNeedsOrbitAndAFullVaultRefuses() {
        houses.steps(6);
        HouseAuction auction = start(MOON_ROCK, "general", 2, true);
        UUID bidder = houses.player(3, 5_000L);
        long now = world.now();
        assertEquals(HouseBid.Result.ORBIT, obligations().bidHouseAuction(world.env, TERMS, bidder, auction.id(),
                auction.minimumBid(), now, false).result());
        assertTrue(obligations().houseAuctions(false).stream().noneMatch(value -> value.id().equals(auction.id())));
        assertTrue(obligations().houseAuctions(true).stream().anyMatch(value -> value.id().equals(auction.id())));
        UUID full = houses.player(4, 5_000L);
        for (int index = 0; index < world.env.settings().vaultTradeCap(); index++)
            world.stock(full, Lot.of("test:filler_" + index, 1));
        assertEquals(HouseBid.Result.VAULT_FULL, obligations().bidHouseAuction(world.env, TERMS, full, auction.id(),
                auction.minimumBid(), now, true).result());
        assertEquals(5_000L, world.balance(full));
        assertEquals(HouseBid.Result.FUNDS, obligations().bidHouseAuction(world.env, TERMS, houses.player(5, 10L),
                auction.id(), auction.minimumBid(), now, true).result());
        assertTrue(world.invariants());
    }

    @Test void aRunningHouseAuctionSurvivesAReloadAndAnErasure() {
        houses.steps(6);
        HouseAuction auction = start(EMERALD, "general", 2, false);
        UUID bidder = houses.player(6, 3_000L);
        assertTrue(obligations().bidHouseAuction(world.env, TERMS, bidder, auction.id(), auction.minimumBid(), world.now(),
                true).ok());
        long goods = goodsInStore(EMERALD);
        world.reload();
        assertEquals(auction.minimumBid(), obligations().houseAuction(auction.id()).highestBid());
        assertEquals(goods, goodsInStore(EMERALD));
        assertTrue(world.invariants());
        assertEquals(1, obligations().exportFor(bidder).getAsJsonArray("house_auctions").size());
        long reserve = world.reserve();
        obligations().forgetPlayer(bidder);
        world.store.ledger().forgetPlayer(bidder);
        // The erased bidder's own escrow went to the Reserve; the lot stays up with no bid.
        assertTrue(world.reserve() >= reserve + auction.minimumBid());
        assertNull(obligations().houseAuction(auction.id()).bidderId());
        assertEquals(0, obligations().exportFor(bidder).getAsJsonArray("house_auctions").size());
        assertTrue(world.invariants());
    }

    @Test void aHouseShortOfGoodsPostsAJobThatFillsItsStockOrRefundsTheReserve() {
        houses.step();
        HouseDealSettings settings = new HouseDealSettings(0, TradeWorld.DAY, true, 1_500, 2, TradeWorld.DAY, 0,
                TradeWorld.DAY);
        long reserve = world.reserve();
        HouseDeals.Report report = HouseDeals.step(context(), env(settings));
        EconomyJob wheat = report.jobs().stream().filter(job -> job.target().equals(HouseWorld.WHEAT)).findFirst()
                .orElseThrow();
        assertEquals("growers", obligations().houseOf(wheat.id()));
        assertEquals(JobType.CONTRACT, wheat.type());
        assertEquals(ObligationSavedData.SYSTEM_ACCOUNT, wheat.issuer());
        // quantity × the House's bid, plus 15 %, rounded up — all of it escrowed from the Reserve.
        long bid = 0L;
        for (HouseSection.Position position : houses.section().positions())
            if (position.key().equals(HouseWorld.WHEAT)) bid = position.bid();
        assertEquals(HouseDeals.payout(wheat.quantity(), bid, 1_500), wheat.payout());
        long posted = report.jobs().stream().mapToLong(EconomyJob::payout).sum();
        assertEquals(reserve - posted, world.reserve());
        assertEquals(posted, world.ledger().budgetUsed(BudgetKind.SYSTEM_JOB));
        assertTrue(world.invariants());
        // No second job for the same goods while one is open.
        assertTrue(HouseDeals.step(context(), env(settings)).jobs().stream()
                .noneMatch(job -> job.target().equals(HouseWorld.WHEAT)));
        // A worker delivers: paid by claim, and the goods are the House's stock, not destroyed.
        UUID worker = houses.player(7, 0L);
        long now = world.now();
        assertEquals(FulfillResult.NOT_CLAIMED, obligations().fulfilHouseJob(wheat.id(), worker,
                List.of(Lot.of(HouseWorld.WHEAT, wheat.quantity()))));
        assertTrue(obligations().claim(wheat.id(), worker, now));
        assertEquals(FulfillResult.MISSING_ITEMS, obligations().fulfilHouseJob(wheat.id(), worker,
                List.of(Lot.of(HouseWorld.WHEAT, wheat.quantity() - 1))));
        assertEquals(FulfillResult.MISSING_ITEMS, obligations().fulfilHouseJob(wheat.id(), worker,
                List.of(new Lot(HouseWorld.WHEAT, wheat.quantity(), 3))));
        long stock = stock(HouseWorld.WHEAT);
        long destroyed = houses.section().destroyed(HouseWorld.WHEAT);
        assertEquals(FulfillResult.SUCCESS, obligations().fulfilHouseJob(wheat.id(), worker,
                List.of(Lot.of(HouseWorld.WHEAT, wheat.quantity()))));
        assertEquals(stock + wheat.quantity(), stock(HouseWorld.WHEAT));
        assertEquals(destroyed, houses.section().destroyed(HouseWorld.WHEAT));
        assertEquals(wheat.payout(), world.claim(worker));
        assertTrue(world.invariants());
        // Any other open House job expires back into the Reserve.
        List<EconomyJob> open = obligations().systemJobs(null);
        long held = open.stream().mapToLong(EconomyJob::payout).sum();
        long before = world.reserve();
        obligations().settleDue(world.env, TERMS, now + 2L * TradeWorld.DAY);
        assertEquals(before + held, world.reserve());
        assertTrue(obligations().systemJobs(null).isEmpty());
        assertTrue(world.invariants());
    }

    @Test void jobsRespectTheSwitchTheBudgetAndThePerHouseCap() {
        long now = world.now();
        assertNull(obligations().postHouseJob("growers", JobType.CONTRACT, HouseWorld.WHEAT, 4, "", 10L, now + 1_000L,
                now, false, 2));
        // More than the day's system-job budget: refused before anything moves.
        long reserve = world.reserve();
        assertNull(obligations().postHouseJob("growers", JobType.CONTRACT, HouseWorld.WHEAT, 4, "", 1_000_000L,
                now + 1_000L, now, true, 2));
        assertEquals(reserve, world.reserve());
        assertNotNull(obligations().postHouseJob("growers", JobType.CONTRACT, HouseWorld.WHEAT, 4, "", 10L,
                now + 1_000L, now, true, 2));
        assertNotNull(obligations().postHouseJob("growers", JobType.DELIVERY, "minecraft:bread", 4,
                "minecraft:overworld|0|64|0", 10L, now + 1_000L, now, true, 2));
        assertNull(obligations().postHouseJob("growers", JobType.CONTRACT, "minecraft:carrot", 4, "", 10L,
                now + 1_000L, now, true, 2));
        assertNotNull(obligations().postHouseJob("general", JobType.CONTRACT, "minecraft:carrot", 4, "", 10L,
                now + 1_000L, now, true, 2));
        assertNull(obligations().postHouseJob("general", JobType.BOUNTY, "minecraft:zombie", 4, "", 10L,
                now + 1_000L, now, true, 2));
        assertTrue(world.invariants());
        world.reload();
        assertEquals(3, obligations().systemJobs(null).size());
        assertEquals(2L, obligations().systemJobs(null).stream().filter(job -> "growers".equals(obligations().houseOf(job.id())))
                .count());
    }

    @Test void serverBountiesAreCappedPerDayAndPaidFromTheReserve() {
        long now = world.now();
        long reserve = world.reserve();
        assertNotNull(obligations().postSystemBounty("minecraft:zombie", 8, 40L, now + 1_000L, now, true, 2));
        assertNotNull(obligations().postSystemBounty("minecraft:skeleton", 8, 45L, now + 1_000L, now, true, 2));
        assertNull(obligations().postSystemBounty("minecraft:creeper", 5, 50L, now + 1_000L, now, true, 2));
        assertNull(obligations().postSystemBounty("minecraft:player", 1, 50L, now + 1_000L, now + TradeWorld.DAY, true, 2));
        assertEquals(reserve - 85L, world.reserve());
        assertEquals(2, obligations().bountiesOn(now));
        assertEquals(2, obligations().systemJobs(JobType.BOUNTY).size());
        // Open ones count against the cap even on a new day; once expired, a new one may go up.
        assertNull(obligations().postSystemBounty("minecraft:creeper", 5, 50L, now + TradeWorld.DAY * 2,
                now + TradeWorld.DAY, true, 2));
        obligations().settleDue(world.env, TERMS, now + 2_000L);
        assertEquals(reserve, world.reserve());
        assertNotNull(obligations().postSystemBounty("minecraft:creeper", 5, 50L, now + TradeWorld.DAY * 2,
                now + TradeWorld.DAY, true, 2));
        assertTrue(world.invariants());
    }

    @Test void theStepAuctionsRareGoodsOrStapleBundlesAtMostPerDayAndPostsBounties() {
        HouseDealSettings settings = new HouseDealSettings(4, TradeWorld.DAY, true, 1_500, 2, TradeWorld.DAY, 2,
                TradeWorld.DAY);
        HouseDealsEnv env = new HouseDealsEnv(houses.env(), TERMS, settings, BountyTable.of(List.of(
                new BountyTable.Entry("minecraft:zombie", 8, 40L))), "minecraft:overworld|0|64|0");
        // A known secret: the step's rolls are then the same on every run, so a run that happens to
        // roll no bounty in three days (about one in four hundred with a random secret) cannot occur.
        SimTesting.fixSecret(world.store, SimTesting.secret(7));
        int auctions = 0;
        int bounties = 0;
        long firstDay = houses.day();
        Map<Long, Integer> perDay = new java.util.TreeMap<>();
        for (long step = 0L; step < 3L * 144L; step++) {
            houses.section().step(houses.env(), houses.day(), HouseWorld.STEP);
            HouseDeals.Report report = HouseDeals.step(context(), env);
            if (report.auction() != null) {
                auctions++;
                perDay.merge(houses.day(), 1, Integer::sum);
                HouseAuction auction = report.auction();
                assertTrue(List.of(EMERALD, MOON_ROCK, COBBLE).contains(auction.lot().item()));
                assertEquals(MOON_ROCK.equals(auction.lot().item()), auction.interplanetary());
                if (COBBLE.equals(auction.lot().item())) assertTrue(auction.lot().count() <= HouseDeals.STAPLE_LOT);
                else assertTrue(auction.lot().count() <= HouseDeals.RARE_LOT);
                assertTrue(auction.minimumBid() >= 1L);
            }
            if (report.bounty() != null) bounties++;
            HouseDeals.settle(context(), env);
            world.wall += HouseWorld.STEP;
            assertTrue(world.invariants());
        }
        assertTrue(auctions > 0, "no auction in three days");
        perDay.values().forEach(count -> assertTrue(count <= 4));
        assertTrue(bounties > 0 && bounties <= 6, "bounties " + bounties);
        assertTrue(houses.day() > firstDay);
        assertTrue(world.store.ledger().balance(SystemAccountId.ESCROW) >= 0L);
    }
}
