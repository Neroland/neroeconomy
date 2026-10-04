package za.co.neroland.neroeconomy.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/** The traded-volume tally the exchange reads: what counts, the two caps, its bound, and that it forgets. */
class TradedVolumeTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private static final String IRON = TradeWorld.IRON;
    private static final AccountId ANNA = new PlayerAccountId(new UUID(1L, 1L));
    private static final AccountId BEN = new PlayerAccountId(new UUID(1L, 2L));
    private static final AccountId CARA = new PlayerAccountId(new UUID(1L, 3L));
    private static final AccountId HOUSE = SystemAccountId.RESERVE;
    private static final long NO_CAP = Long.MAX_VALUE;

    private TradeWorld world;

    @BeforeEach void open() { world = new TradeWorld(); }
    @AfterEach void close() { world.close(); }

    // --- the tally itself ------------------------------------------------------------------------

    @Test void fillsAddUpPerItemAndReadingEmptiesTheTally() {
        TradedVolume volume = new TradedVolume();
        volume.record(ANNA, BEN, DIAMOND, 3, 30L);
        volume.record(BEN, ANNA, DIAMOND, 2, 24L);
        volume.record(ANNA, CARA, IRON, 10, 50L);
        Map<String, ItemVolume> totals = volume.drain(NO_CAP, NO_CAP);
        assertEquals(new ItemVolume(5L, 54L), totals.get(DIAMOND));
        assertEquals(new ItemVolume(10L, 50L), totals.get(IRON));
        assertEquals(2, totals.size());
        assertTrue(volume.drain(NO_CAP, NO_CAP).isEmpty());
        assertEquals(0, volume.size());
    }

    @Test void nothingThatIsNotAFillIsRecorded() {
        TradedVolume volume = new TradedVolume();
        volume.record(ANNA, ANNA, DIAMOND, 1, 10L);
        volume.record(null, BEN, DIAMOND, 1, 10L);
        volume.record(ANNA, null, DIAMOND, 1, 10L);
        volume.record(ANNA, BEN, null, 1, 10L);
        volume.record(ANNA, BEN, DIAMOND, 0, 10L);
        volume.record(ANNA, BEN, DIAMOND, 1, 0L);
        assertEquals(0, volume.size());
        assertTrue(volume.drain(NO_CAP, NO_CAP).isEmpty());
    }

    @Test void fillsBetweenTheSameTwoPlayersCountUpToThePairCapWhicheverOfThemSold() {
        TradedVolume volume = new TradedVolume();
        // Two accounts passing diamonds back and forth all step long, and some iron as well.
        for (int round = 0; round < 50; round++) {
            volume.record(ANNA, BEN, DIAMOND, 10, 1_000L);
            volume.record(BEN, ANNA, DIAMOND, 10, 1_000L);
        }
        volume.record(ANNA, BEN, IRON, 5, 500L);
        Map<String, ItemVolume> totals = volume.drain(250L, NO_CAP);
        // 100000 credits changed hands; 250 of them count, across every item the pair traded.
        assertEquals(250L, totals.get(DIAMOND).value());
        assertFalse(totals.containsKey(IRON));
        // The quantity is counted in the same proportion.
        assertEquals(1_000L * 250L / 100_000L, totals.get(DIAMOND).quantity());
    }

    @Test void onePlayersFillsCountUpToTheAccountCapHoweverManyPartnersTheyUse() {
        TradedVolume volume = new TradedVolume();
        // One account trading with a hundred others, each pair within the pair cap.
        for (int index = 0; index < 100; index++)
            volume.record(ANNA, new PlayerAccountId(new UUID(2L, index)), DIAMOND, 1, 200L);
        Map<String, ItemVolume> totals = volume.drain(250L, 1_000L);
        assertEquals(1_000L, totals.get(DIAMOND).value());
        assertEquals(5L, totals.get(DIAMOND).quantity());

        // Separate pairs of accounts each count in full, up to their own caps.
        for (int index = 0; index < 8; index++)
            volume.record(new PlayerAccountId(new UUID(3L, index)), new PlayerAccountId(new UUID(4L, index)), DIAMOND,
                    100, 5_000L);
        assertEquals(8L * 250L, volume.drain(250L, 1_000L).get(DIAMOND).value());
    }

    @Test void tradeWithASystemAccountCountsAgainstThePlayerOnlyAndBetweenSystemAccountsInFull() {
        TradedVolume volume = new TradedVolume();
        volume.record(HOUSE, ANNA, DIAMOND, 10, 5_000L);
        // No pair cap for a player and a system account, but the player's own cap applies.
        assertEquals(1_000L, volume.drain(250L, 1_000L).get(DIAMOND).value());
        volume.record(HOUSE, SystemAccountId.ESCROW, IRON, 10, 5_000L);
        assertEquals(new ItemVolume(10L, 5_000L), volume.drain(250L, 1_000L).get(IRON));
        // Caps of zero count no player's trade at all.
        volume.record(ANNA, BEN, DIAMOND, 1, 10L);
        assertTrue(volume.drain(0L, 1_000L).isEmpty());
        volume.record(HOUSE, ANNA, DIAMOND, 1, 10L);
        assertTrue(volume.drain(250L, 0L).isEmpty());
    }

    @Test void theTallyIsBoundedAndForgetsAnAccountAtOnce() {
        TradedVolume volume = new TradedVolume();
        for (int index = 0; index < TradedVolume.MAX_ROWS + 500; index++)
            volume.record(ANNA, new PlayerAccountId(new UUID(5L, index)), DIAMOND, 1, 1L);
        assertEquals(TradedVolume.MAX_ROWS, volume.size());
        // A pair that is already in it still adds up.
        volume.record(ANNA, new PlayerAccountId(new UUID(5L, 0L)), DIAMOND, 1, 1L);
        assertEquals(TradedVolume.MAX_ROWS, volume.size());
        volume.forget(ANNA);
        assertEquals(0, volume.size());

        volume.record(ANNA, BEN, DIAMOND, 1, 10L);
        volume.record(BEN, CARA, DIAMOND, 1, 20L);
        volume.record(CARA, ANNA, IRON, 1, 30L);
        volume.forget(BEN);
        volume.forget(null);
        Map<String, ItemVolume> totals = volume.drain(NO_CAP, NO_CAP);
        assertFalse(totals.containsKey(DIAMOND));
        assertEquals(30L, totals.get(IRON).value());
    }

    // --- through the order book ------------------------------------------------------------------

    private Placed sell(UUID seller, String item, int count, long price) {
        AccountId account = TradeWorld.account(seller);
        world.stock(seller, Lot.of(item, count));
        return world.store.book().placeSell(world.env, account, new Lot(item, count, Lot.PRISTINE), price,
                OrderSource.VAULT, VaultGoods.origin(world.store, world.env, account), null);
    }

    private Placed buy(UUID buyer, String item, int count, long limit) {
        AccountId account = TradeWorld.account(buyer);
        return world.store.book().placeBuy(world.env, account, item, count, limit, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, account), null);
    }

    @Test void everyFillOnTheBookIsCountedAtTheSellersPriceAndAnOrderThatOnlyRestsIsNot() {
        UUID seller = world.player(1, 1_000L);
        UUID buyer = world.player(2, 1_000L);
        assertTrue(sell(seller, DIAMOND, 10, 7L).ok());
        // Resting orders are not trade.
        assertTrue(world.store.book().drainTradedVolume(NO_CAP, NO_CAP).isEmpty());
        // The buyer bids 9 and is filled at the resting order's 7: 4 × 7 credits changed hands.
        assertEquals(4, buy(buyer, DIAMOND, 4, 9L).filled());
        // A direct purchase from the same order counts too.
        assertTrue(world.store.book().buyFrom(world.env, TradeWorld.account(buyer),
                world.store.book().sellsOf(TradeWorld.account(seller)).get(0).id(), 2, 7L,
                VaultGoods.receiver(world.store, world.env, TradeWorld.account(buyer))).ok());
        Map<String, ItemVolume> totals = world.store.book().drainTradedVolume(NO_CAP, NO_CAP);
        assertEquals(new ItemVolume(6L, 42L), totals.get(DIAMOND));
        assertTrue(world.store.book().drainTradedVolume(NO_CAP, NO_CAP).isEmpty());
        assertTrue(world.invariants());
    }

    @Test void anAccountNeverMatchesItselfSoItsOwnOrdersNeverCount() {
        UUID trader = world.player(1, 10_000L);
        assertTrue(sell(trader, DIAMOND, 10, 5L).ok());
        // Its own buy order crosses its own sell order: the book passes it over, and nothing is traded.
        assertEquals(0, buy(trader, DIAMOND, 10, 5L).filled());
        assertTrue(world.store.book().drainTradedVolume(NO_CAP, NO_CAP).isEmpty());
    }

    @Test void theTallyIsNotSavedAndAnErasedPlayerLeavesIt() {
        UUID seller = world.player(1, 1_000L);
        UUID buyer = world.player(2, 1_000L);
        UUID other = world.player(3, 1_000L);
        assertTrue(sell(seller, DIAMOND, 10, 5L).ok());
        assertEquals(4, buy(buyer, DIAMOND, 4, 5L).filled());
        assertEquals(2, buy(other, DIAMOND, 2, 5L).filled());
        // Erasing the first buyer drops what was recorded about them; the other fill stays.
        world.store.book().forgetPlayer(buyer);
        assertEquals(new ItemVolume(2L, 10L), world.store.book().drainTradedVolume(NO_CAP, NO_CAP).get(DIAMOND));

        assertEquals(2, buy(other, DIAMOND, 2, 5L).filled());
        // A save and a load: nothing about who traded with whom is ever written.
        assertFalse(za.co.neroland.neroeconomy.economy.StoreTesting.encode(world.store).toString().contains("volume"));
        world.reload();
        assertTrue(world.store.book().drainTradedVolume(NO_CAP, NO_CAP).isEmpty());
    }
}
