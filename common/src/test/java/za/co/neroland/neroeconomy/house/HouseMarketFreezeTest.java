package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/** While an operator has closed the market the Merchant Houses neither quote nor trade. */
class HouseMarketFreezeTest {
    private final HouseWorld houses = HouseWorld.standard();

    @AfterEach void close() {
        assertTrue(houses.world.invariants());
        houses.world.close();
    }

    @Test void aClosedMarketStopsTheHouseStepAndTradingWithTheHouses() {
        houses.world.store.admin().setMarketFrozen(true);
        long reserve = houses.world.reserve();
        houses.steps(3);
        assertNull(houses.ask(HouseWorld.WHEAT));
        assertNull(houses.bid(HouseWorld.DIAMOND));
        assertEquals(0, houses.section().requoteDirty(houses.env(), 100));
        assertEquals(reserve, houses.world.reserve(), "a closed market funded a House");

        // Opened, the next step quotes as usual; closed again, nobody can trade with what rests on the book.
        houses.world.store.admin().setMarketFrozen(false);
        houses.step();
        SellOrder ask = houses.ask(HouseWorld.WHEAT);
        BuyOrder bid = houses.bid(HouseWorld.DIAMOND);
        assertNotNull(ask);
        assertNotNull(bid);
        houses.world.store.admin().setMarketFrozen(true);
        java.util.UUID player = houses.world.player(1, 1_000L);
        houses.world.stock(player, Lot.of(HouseWorld.DIAMOND, 1));
        assertEquals(BookResult.MARKET_CLOSED, houses.world.store.book().buyFrom(houses.world.env,
                TradeWorld.account(player), ask.id(), 1, ask.unitPrice(), VaultGoods.receiver(houses.world.store,
                        houses.world.env, TradeWorld.account(player))).result());
        assertEquals(BookResult.MARKET_CLOSED, houses.world.store.book().sellTo(houses.world.env,
                TradeWorld.account(player), bid.id(), Lot.of(HouseWorld.DIAMOND, 1), VaultGoods.origin(
                        houses.world.store, houses.world.env, TradeWorld.account(player))).result());
        assertEquals(1L, houses.world.held(player, HouseWorld.DIAMOND));
    }
}
