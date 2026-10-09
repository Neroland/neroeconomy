package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.obligation.AuctionTerms;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.FulfillResult;
import za.co.neroland.neroeconomy.obligation.HouseAuction;
import za.co.neroland.neroeconomy.obligation.HouseBid;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * While an operator has closed the market, auctions and jobs take nothing new: no auction starts, no
 * bid is placed, no job is posted, claimed or fulfilled and no kill completes a bounty. What is
 * already running winds down as usual: auctions settle, jobs expire back to whoever funded them, a
 * claim can be given back and refunds are booked.
 */
class DealsMarketFreezeTest {
    static final AuctionTerms TERMS = AuctionTerms.DEFAULTS;

    final HouseWorld houses = new HouseWorld(List.of(HouseWorld.wheat(), HouseDealsTest.emerald()),
            List.of(HouseWorld.house("growers", 4_000L), HouseWorld.house("general", 10_000L)));
    final TradeWorld world = houses.world;

    @AfterEach void close() {
        assertTrue(world.invariants());
        world.close();
    }

    ObligationSavedData obligations() { return world.store.obligations(); }

    void closeMarket(boolean closed) { world.store.admin().setMarketFrozen(closed); }

    @Test void aClosedMarketStartsNoAuctionAndTakesNoBidButSettlesTheRunningOne() {
        houses.steps(6);
        long now = world.now();
        Lot lot = houses.section().takeStock(HouseDealsTest.EMERALD, HouseDealsTest.EMERALD, 2);
        assertNotNull(lot);
        HouseAuction auction = obligations().startHouseAuction("general", lot, 100L, now + TradeWorld.DAY, false, now, 4);
        assertNotNull(auction);
        UUID first = houses.player(1, 2_000L);
        UUID second = houses.player(2, 2_000L);
        assertTrue(obligations().bidHouseAuction(world.env, TERMS, first, auction.id(), 100L, now, true).ok());

        closeMarket(true);
        Lot more = houses.section().takeStock(HouseDealsTest.EMERALD, HouseDealsTest.EMERALD, 1);
        assertNotNull(more);
        assertNull(obligations().startHouseAuction("general", more, 100L, now + TradeWorld.DAY, false, now, 4));
        houses.section().returnStock("general", more);
        assertEquals(HouseBid.Result.MARKET_CLOSED, obligations().bidHouseAuction(world.env, TERMS, second,
                auction.id(), 500L, now, true).result());
        assertEquals(2_000L, world.balance(second), "a refused bid moved credits");
        assertEquals(1, obligations().houseAuctions(true).size());

        // The running auction still ends and settles: the lot goes to its bidder, the House is paid.
        List<UUID> winners = obligations().settleDue(world.env, TERMS, auction.endsAt() + TERMS.antiSnipeMillis());
        assertEquals(List.of(first), winners);
        assertEquals(2L, world.held(first, HouseDealsTest.EMERALD));

        // Opened again, bidding works as before.
        closeMarket(false);
        Lot again = houses.section().takeStock(HouseDealsTest.EMERALD, HouseDealsTest.EMERALD, 1);
        long later = world.now();
        HouseAuction next = obligations().startHouseAuction("general", again, 50L, later + TradeWorld.DAY, false, later, 4);
        assertNotNull(next);
        assertTrue(obligations().bidHouseAuction(world.env, TERMS, second, next.id(), 50L, later, true).ok());
    }

    @Test void aClosedMarketPostsClaimsAndFulfilsNoJobButReleasesAndExpiresThem() {
        long now = world.now();
        EconomyJob claimed = obligations().postHouseJob("growers", JobType.CONTRACT, HouseWorld.WHEAT, 4, "", 10L,
                now + TradeWorld.DAY, now, true, 4);
        EconomyJob open = obligations().postHouseJob("general", JobType.CONTRACT, HouseDealsTest.EMERALD, 2, "", 12L,
                now + TradeWorld.DAY, now, true, 4);
        EconomyJob bounty = obligations().postSystemBounty("minecraft:zombie", 1, 20L, now + TradeWorld.DAY, now, true, 2);
        assertNotNull(claimed);
        assertNotNull(open);
        assertNotNull(bounty);
        UUID worker = houses.player(3, 0L);
        UUID hunter = houses.player(4, 0L);
        assertTrue(obligations().claim(claimed.id(), worker, now));
        long reserve = world.reserve();

        closeMarket(true);
        assertNull(obligations().postHouseJob("growers", JobType.DELIVERY, "minecraft:bread", 4, "", 10L,
                now + TradeWorld.DAY, now, true, 4));
        assertNull(obligations().postSystemBounty("minecraft:skeleton", 1, 20L, now + TradeWorld.DAY, now, true, 2));
        assertFalse(obligations().claim(open.id(), worker, now));
        assertEquals(FulfillResult.MARKET_CLOSED, obligations().fulfilHouseJob(claimed.id(), worker,
                List.of(Lot.of(HouseWorld.WHEAT, 4))));
        assertEquals(0, obligations().recordKill(world.store.market(), hunter, "minecraft:zombie", "", now));
        assertEquals(0L, world.claim(worker));
        assertEquals(0L, world.claim(hunter));
        assertEquals(reserve, world.reserve(), "a closed market moved a job's payout");
        assertEquals(EconomyHealth.MARKET_CLOSED_KEY, FulfillResult.MARKET_CLOSED.translationKey());

        // Giving a claim back still works, and every open job expires back into the Reserve.
        assertTrue(obligations().release(claimed.id(), worker));
        long held = claimed.payout() + open.payout() + bounty.payout();
        obligations().settleDue(world.env, TERMS, now + 2L * TradeWorld.DAY);
        assertEquals(reserve + held, world.reserve());
        assertTrue(obligations().systemJobs(null).isEmpty());

        // Opened again, a job is posted and claimed as usual.
        closeMarket(false);
        long later = world.now() + 2L * TradeWorld.DAY;
        EconomyJob fresh = obligations().postHouseJob("growers", JobType.CONTRACT, HouseWorld.WHEAT, 4, "", 10L,
                later + TradeWorld.DAY, later, true, 4);
        assertNotNull(fresh);
        assertTrue(obligations().claim(fresh.id(), worker, later));
    }
}
