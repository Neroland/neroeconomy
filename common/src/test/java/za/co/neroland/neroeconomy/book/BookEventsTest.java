package za.co.neroland.neroeconomy.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.EconomyEvents;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.sibling.EconomyEventBus;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/** The cross-mod economy events of the order book: one per side per settled fill, none on refusal. */
class BookEventsTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private final TradeWorld world = new TradeWorld();
    private final List<EconomyEvents.Sold> sold = new ArrayList<>();
    private final List<EconomyEvents.Bought> bought = new ArrayList<>();
    private final List<EconomyEvents.Filled> filled = new ArrayList<>();

    @BeforeEach void listen() {
        clear();
        EconomyEventBus.SOLD.add(event -> { throw new IllegalStateException("a broken listener"); });
        EconomyEventBus.SOLD.add(sold::add);
        EconomyEventBus.BOUGHT.add(bought::add);
        EconomyEventBus.FILLED.add(filled::add);
    }

    @AfterEach void done() {
        clear();
        assertTrue(world.invariants());
        world.close();
    }

    private static void clear() {
        EconomyEventBus.SOLD.clearForTest();
        EconomyEventBus.BOUGHT.clearForTest();
        EconomyEventBus.FILLED.clearForTest();
        EconomyEventBus.clearPending();
    }

    private Placed sell(UUID seller, int count, long price) {
        AccountId account = TradeWorld.account(seller);
        return world.store.book().placeSell(world.env, account, new Lot(DIAMOND, count, Lot.PRISTINE), price,
                OrderSource.VAULT, VaultGoods.origin(world.store, world.env, account), null);
    }

    private Placed buy(UUID buyer, int count, long limit) {
        AccountId account = TradeWorld.account(buyer);
        return world.store.book().placeBuy(world.env, account, DIAMOND, count, limit, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, account), null);
    }

    @Test void aFillTellsEachSideAloneAndTheRestingOwnerThatItFilled() {
        UUID seller = world.player(1, 100L);
        world.stock(seller, Lot.of(DIAMOND, 5));
        assertTrue(sell(seller, 5, 7L).ok());
        UUID buyer = world.player(2, 500L);
        assertTrue(buy(buyer, 3, 9L).ok());
        assertTrue(sold.isEmpty(), "queued until the end of the tick");
        EconomyEventBus.drain();
        assertEquals(1, sold.size());
        assertEquals(1, bought.size());
        assertEquals(1, filled.size());
        EconomyEvents.Sold sale = sold.get(0);
        assertEquals(seller, sale.seller());
        assertEquals(DIAMOND, sale.itemId());
        assertEquals(3L, sale.quantity());
        assertEquals(7L, sale.unitPrice());
        assertEquals(EconomyEvents.Venue.BOARD, sale.venue());
        assertEquals(buyer, bought.get(0).buyer());
        assertEquals(7L, bought.get(0).unitPrice());
        assertEquals(new EconomyEvents.Filled(seller, DIAMOND, 3L, 7L, false, filled.get(0).at()), filled.get(0));
        assertEquals(2, EconomyEventBus.SOLD.size(), "the throwing listener stays registered");
    }

    @Test void aRestingBuyOrderIsTheOneToldItFilled() {
        UUID buyer = world.player(1, 500L);
        assertTrue(buy(buyer, 4, 10L).ok());
        UUID seller = world.player(2, 100L);
        world.stock(seller, Lot.of(DIAMOND, 4));
        assertTrue(sell(seller, 4, 6L).ok());
        EconomyEventBus.drain();
        assertEquals(1, filled.size());
        assertEquals(buyer, filled.get(0).owner());
        assertTrue(filled.get(0).buyOrder());
        assertEquals(10L, sold.get(0).unitPrice(), "the resting order's price");
    }

    @Test void nothingIsToldForATradeThatDidNotHappen() {
        UUID seller = world.player(1, 100L);
        world.stock(seller, Lot.of(DIAMOND, 5));
        assertTrue(sell(seller, 5, 50L).ok());
        UUID poor = world.player(2, 10L);
        assertFalse(buy(poor, 5, 50L).ok());
        EconomyEventBus.drain();
        assertTrue(sold.isEmpty());
        assertTrue(bought.isEmpty());
        assertTrue(filled.isEmpty());
    }
}
