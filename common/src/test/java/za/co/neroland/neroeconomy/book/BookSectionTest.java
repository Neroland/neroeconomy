package za.co.neroland.neroeconomy.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.trade.BuyerPricing;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The order book's rules against a real store: matching, settlement, caps, cancelling, expiry,
 * erasure and the saved form. Every test ends with both ledger invariants holding.
 */
class BookSectionTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private final TradeWorld world = new TradeWorld();

    @AfterEach void done() {
        assertTrue(world.invariants(), "the ledger invariants must hold at the end of every test");
        world.close();
    }

    private BookSection book() { return world.store.book(); }

    private Placed sell(UUID seller, String item, int count, long price) {
        return sell(seller, new Lot(item, count, Lot.PRISTINE), price);
    }

    private Placed sell(UUID seller, Lot lot, long price) {
        AccountId account = TradeWorld.account(seller);
        return book().placeSell(world.env, account, lot, price, OrderSource.VAULT,
                VaultGoods.origin(world.store, world.env, account), null);
    }

    private Placed buy(UUID buyer, String item, int count, long limit) {
        AccountId account = TradeWorld.account(buyer);
        return book().placeBuy(world.env, account, item, count, limit, OrderSource.VAULT, false,
                VaultGoods.receiver(world.store, world.env, account), null);
    }

    /** A seller with credits for fees and {@code count} diamonds in the vault. */
    private UUID seller(int number, int count) {
        UUID player = world.player(number, 100L);
        world.stock(player, Lot.of(DIAMOND, count));
        return player;
    }

    // --- matching ----------------------------------------------------------------------------

    @Test void aBuyOrderFillsTheCheapestSellFirstAndThenTheOldest() {
        UUID first = seller(1, 5);
        UUID cheap = seller(2, 5);
        UUID later = seller(3, 5);
        assertTrue(sell(first, DIAMOND, 5, 5L).ok());
        assertTrue(sell(cheap, DIAMOND, 5, 4L).ok());
        assertTrue(sell(later, DIAMOND, 5, 5L).ok());
        UUID buyer = world.player(4, 1_000L);
        long reserve = world.reserve();

        Placed placed = buy(buyer, DIAMOND, 12, 5L);

        assertTrue(placed.ok());
        assertEquals(12, placed.filled());
        assertEquals(0, placed.rested());
        List<Fill> fills = placed.fills();
        assertEquals(3, fills.size());
        // Best price first: the 4-credit order, although it was placed second.
        assertEquals(TradeWorld.account(cheap), fills.get(0).seller());
        assertEquals(4L, fills.get(0).unitPrice());
        assertEquals(5, fills.get(0).quantity());
        // Then oldest first among equal prices, and only as much of the last one as is still wanted.
        assertEquals(TradeWorld.account(first), fills.get(1).seller());
        assertEquals(TradeWorld.account(later), fills.get(2).seller());
        assertEquals(2, fills.get(2).quantity());
        // 20 + 25 + 10 at the sellers' prices; the buyer also paid the listing fee of 1.
        assertEquals(55L, placed.buyerPaid());
        assertEquals(1_000L - 55L - 1L, world.balance(buyer));
        assertEquals(12L, world.held(buyer, DIAMOND));
        // Each seller is owed their price less a fee of at least 1 per fill.
        assertEquals(19L, world.claim(cheap));
        assertEquals(24L, world.claim(first));
        assertEquals(9L, world.claim(later));
        // The Reserve took the buyer's listing fee and three market fees.
        assertEquals(reserve + 1L + 3L, world.reserve());
        // What is left of the last order still rests, and nothing else does.
        assertEquals(1, book().size());
        assertEquals(3, book().sellsOf(TradeWorld.account(later)).get(0).lot().count());
        assertEquals(world.claim(cheap) + world.claim(first) + world.claim(later), world.escrow());
    }

    @Test void aFillSettlesAtTheRestingOrdersPriceWhicheverSideWasThereFirst() {
        UUID buyer = world.player(1, 500L);
        assertTrue(buy(buyer, DIAMOND, 10, 10L).ok());
        assertEquals(500L - 100L - 1L, world.balance(buyer));
        UUID seller = seller(2, 10);

        // The seller asked for 6, but the buy order was resting at 10: the sale is at 10.
        Placed placed = sell(seller, DIAMOND, 10, 6L);

        assertEquals(10, placed.filled());
        assertEquals(10L, placed.fills().get(0).unitPrice());
        assertEquals(100L, placed.base());
        assertEquals(2L, placed.fees());
        assertEquals(98L, world.claim(seller));
        assertEquals(10L, world.held(buyer, DIAMOND));
        assertEquals(0, book().size());
        // The buyer paid exactly their limit and nothing came back.
        assertEquals(399L, world.balance(buyer));
    }

    @Test void aBuyerWhoBidAboveTheFillPriceGetsTheDifferenceBackAtOnce() {
        UUID seller = seller(1, 4);
        assertTrue(sell(seller, DIAMOND, 4, 7L).ok());
        UUID buyer = world.player(2, 500L);

        Placed placed = buy(buyer, DIAMOND, 10, 20L);

        // 200 escrowed; 4 filled at 7, so 13 a unit came straight back for those four.
        assertEquals(200L, placed.escrowed());
        assertEquals(4, placed.filled());
        assertEquals(52L, placed.refunded());
        assertEquals(6, placed.rested());
        assertEquals(500L - 1L - 200L + 52L, world.balance(buyer));
        // What still rests holds exactly its own limit times what is left.
        BuyOrder resting = book().buy(placed.orderId());
        assertEquals(6, resting.remaining());
        assertEquals(120L, resting.escrow());
        assertEquals(120L + world.claim(seller), world.escrow());
    }

    @Test void anAccountNeverMatchesItselfAndItsOwnOrderDoesNotBlockTheNextOne() {
        UUID both = seller(1, 5);
        world.player(1, 500L);
        UUID other = seller(2, 5);
        Placed own = sell(both, DIAMOND, 5, 3L);
        assertTrue(sell(other, DIAMOND, 5, 4L).ok());

        Placed placed = buy(both, DIAMOND, 5, 4L);

        // The cheaper order is the buyer's own: passed over, and the next one fills.
        assertEquals(5, placed.filled());
        assertEquals(TradeWorld.account(other), placed.fills().get(0).seller());
        assertEquals(4L, placed.fills().get(0).unitPrice());
        assertNotNull(book().sell(own.orderId()));
        assertEquals(5, book().sell(own.orderId()).lot().count());
    }

    @Test void onlyUnmodifiedLotsMatchBuyOrdersButAModifiedLotCanBeBoughtDirectly() {
        UUID seller = world.player(1, 100L);
        Lot sword = new Lot("minecraft:diamond_sword", 1, 7);
        world.stock(seller, sword);
        Placed listed = sell(seller, sword, 5L);
        assertTrue(listed.ok());
        UUID buyer = world.player(2, 500L);

        Placed wanted = buy(buyer, "minecraft:diamond_sword", 1, 50L);

        // The enchanted sword is listed for far less than the bid, and still does not fill it.
        assertEquals(0, wanted.filled());
        assertEquals(1, wanted.rested());
        assertNotNull(book().sell(listed.orderId()));
        // Bought directly it changes hands, with its payload.
        Traded traded = book().buyFrom(world.env, TradeWorld.account(buyer), listed.orderId(), 1, 5L,
                VaultGoods.receiver(world.store, world.env, TradeWorld.account(buyer)));
        assertTrue(traded.ok());
        assertEquals(1L, world.held(buyer, "minecraft:diamond_sword", 7));
        assertNull(book().sell(listed.orderId()));
        assertEquals(4L, world.claim(seller));
    }

    @Test void partOfAnOrderFillsAndTheRestKeepsItsPlaceInTheQueue() {
        UUID first = seller(1, 10);
        UUID second = seller(2, 10);
        Placed older = sell(first, DIAMOND, 10, 5L);
        Placed newer = sell(second, DIAMOND, 10, 5L);
        UUID buyer = world.player(3, 1_000L);

        assertEquals(4, buy(buyer, DIAMOND, 4, 5L).filled());
        // The older order was filled in part; it is still ahead of the newer one for the next buyer.
        assertEquals(6, book().sell(older.orderId()).lot().count());
        assertEquals(10, book().sell(newer.orderId()).lot().count());
        Placed next = buy(buyer, DIAMOND, 8, 5L);
        assertEquals(TradeWorld.account(first), next.fills().get(0).seller());
        assertEquals(6, next.fills().get(0).quantity());
        assertEquals(TradeWorld.account(second), next.fills().get(1).seller());
        assertEquals(2, next.fills().get(1).quantity());
        assertNull(book().sell(older.orderId()));
    }

    // --- price modifiers ---------------------------------------------------------------------

    /** One buyer pays a fifth on top of every seller's price; everyone else pays the price. */
    private TradeEnv surcharged(UUID buyer) {
        BuyerPricing pricing = (who, listing, item, base, available) ->
                who.equals(TradeWorld.account(buyer)) ? base + (base + 4L) / 5L : base;
        return world.env.with(pricing);
    }

    @Test void aRestingSellOnlyCrossesWhenTheBuyersModifiedPriceIsWithinTheirLimit() {
        UUID seller = seller(1, 10);
        assertTrue(sell(seller, DIAMOND, 10, 10L).ok());
        UUID buyer = world.player(2, 1_000L);
        world.env = surcharged(buyer);

        // At a limit of 10 the surcharged price of 12 is out of reach: nothing fills.
        Placed tooLow = buy(buyer, DIAMOND, 10, 10L);
        assertEquals(0, tooLow.filled());
        assertEquals(10, tooLow.rested());
        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, tooLow.orderId(), TradeWorld.account(buyer)));

        long reserve = world.reserve();
        Placed placed = buy(buyer, DIAMOND, 10, 12L);

        // At 12 it crosses: the seller is paid their own price, the surcharge goes to the Reserve.
        assertEquals(10, placed.filled());
        Fill fill = placed.fills().get(0);
        assertEquals(100L, fill.base());
        assertEquals(120L, fill.buyerPaid());
        assertEquals(20L, fill.adjustment());
        assertEquals(98L, world.claim(seller));
        assertEquals(reserve + 1L + 2L + 20L, world.reserve());
        assertEquals(0L, placed.refunded());
    }

    @Test void aSurchargeOnARestingBuyerLowersTheSellersPriceSoTheBuyerStaysWithinTheirLimit() {
        UUID buyer = world.player(1, 1_000L);
        world.env = surcharged(buyer);
        Placed resting = buy(buyer, DIAMOND, 10, 12L);
        assertEquals(10, resting.rested());
        UUID seller = seller(2, 10);
        long before = world.balance(buyer);

        Placed placed = sell(seller, DIAMOND, 10, 5L);

        // 10 is the highest price whose surcharged form (12) is still within the buyer's limit.
        assertEquals(10, placed.filled());
        assertEquals(10L, placed.fills().get(0).unitPrice());
        assertEquals(120L, placed.fills().get(0).buyerPaid());
        assertEquals(98L, world.claim(seller));
        // The buyer's escrow of 120 was used exactly; nothing more was taken and nothing came back.
        assertEquals(before, world.balance(buyer));
        assertNull(book().buy(resting.orderId()));
    }

    @Test void aDiscountIsPaidByTheReserveUpToTheFeeOfTheSaleAndSkippedWhenTheReserveCannotPay() {
        UUID seller = seller(1, 20);
        assertTrue(sell(seller, DIAMOND, 10, 1_000L).ok());
        UUID buyer = world.player(2, 100_000L);
        // A tenth off for this buyer: 100 a unit, where the fee on a unit is 20.
        BuyerPricing discount = (who, listing, item, base, available) ->
                who.equals(TradeWorld.account(buyer)) ? Math.max(1L, base - base / 10L) : base;
        world.env = world.env.with(discount);
        long reserve = world.reserve();

        Placed placed = buy(buyer, DIAMOND, 10, 1_000L);

        // The seller gets their own price less the fee. The discount is cut back to the fee the
        // sale pays the Reserve — 20 a unit, not 100 — so the Reserve ends exactly where it was
        // but for the buyer's listing fee.
        assertEquals(10_000L, placed.base());
        assertEquals(9_800L, placed.buyerPaid());
        assertFalse(placed.fills().get(0).discountSkipped());
        assertEquals(9_800L, world.claim(seller));
        assertEquals(100_000L - 1L - 9_800L, world.balance(buyer));
        assertEquals(reserve + 1L + 200L - 200L, world.reserve());

        // With the Reserve empty the same purchase goes through at the seller's price instead.
        assertTrue(sell(seller, DIAMOND, 10, 1_000L).ok());
        UUID sink = world.player(3, 0L);
        assertTrue(world.ledger().apply(LedgerPlan.of(SystemAccountId.RESERVE, TradeWorld.account(sink), world.reserve(),
                TransactionType.MARKET_PAYOUT)).ok());
        long balance = world.balance(buyer);
        Placed second = buy(buyer, DIAMOND, 10, 1_000L);
        assertEquals(10, second.filled());
        assertTrue(second.fills().get(0).discountSkipped());
        assertEquals(10_000L, second.buyerPaid());
        assertEquals(balance - 1L - 10_000L, world.balance(buyer));
    }

    @Test void aDiscountTooSmallToBeCoveredByTheFeeIsNotGivenAtAll() {
        UUID seller = seller(1, 10);
        assertTrue(sell(seller, DIAMOND, 10, 10L).ok());
        UUID buyer = world.player(2, 1_000L);
        world.env = world.env.with((who, listing, item, base, available) -> Math.max(1L, base / 2L));
        long reserve = world.reserve();

        Placed placed = buy(buyer, DIAMOND, 10, 10L);

        // The fee on one unit at 10 credits rounds down to nothing, so there is no room for a discount.
        assertEquals(100L, placed.buyerPaid());
        assertFalse(placed.fills().get(0).discountSkipped());
        assertEquals(reserve + 1L + 2L, world.reserve());
    }

    @Test void twoAccountsTradingTheSameGoodsBackAndForthUnderALargeDiscountNeverGainCredits() {
        // Everybody is given half off everything: the most generous modifier the engine allows.
        world.env = world.env.with((who, listing, item, base, available) -> Math.max(1L, base / 2L));
        UUID first = world.player(1, 2_000_000L);
        UUID second = world.player(2, 2_000_000L);
        world.stock(first, Lot.of(DIAMOND, 64));
        long reserve = world.reserve();
        long credits = wealth(first) + wealth(second);
        java.util.Random random = new java.util.Random(20_261_003L);
        UUID holder = first;
        UUID other = second;
        for (int round = 0; round < 400; round++) {
            // Every price from the smallest to one where the discount would be worth thousands.
            long price = switch (random.nextInt(4)) {
                case 0 -> 1L + random.nextInt(60);
                case 1 -> 50L + random.nextInt(500);
                case 2 -> 1_000L + random.nextInt(9_000);
                default -> 1L + random.nextInt(3);
            };
            int quantity = 1 + random.nextInt((int) Math.min(64L, world.held(holder, DIAMOND)));
            // Now and then the buyer's order is the resting one, so both ways of crossing are played.
            if (random.nextInt(3) == 0) {
                Placed resting = buy(other, DIAMOND, quantity, price);
                assertTrue(resting.ok(), "round " + round);
                assertTrue(sell(holder, DIAMOND, quantity, price).ok(), "round " + round);
            } else {
                assertTrue(sell(holder, DIAMOND, quantity, price).ok(), "round " + round);
                assertEquals(quantity, buy(other, DIAMOND, quantity, price).filled(), "round " + round);
            }
            // The seller collects what the sale brought them, so the credits go round again.
            world.store.market().collectCreditsTotal(first);
            world.store.market().collectCreditsTotal(second);
            long now = wealth(first) + wealth(second);
            assertTrue(now <= credits, "round " + round + ": the pair gained " + (now - credits) + " credits");
            assertTrue(world.reserve() >= reserve, "round " + round + ": the Reserve was drawn on");
            credits = now;
            reserve = world.reserve();
            assertTrue(world.invariants(), "round " + round);
            // The goods went to the buyer's vault; they sell them straight back.
            if (world.held(other, DIAMOND) > 0L && world.held(holder, DIAMOND) == 0L) {
                UUID swap = holder;
                holder = other;
                other = swap;
            } else if (world.held(other, DIAMOND) > world.held(holder, DIAMOND)) {
                UUID swap = holder;
                holder = other;
                other = swap;
            }
        }
        // Nothing was created on the way: the pair still holds the same 64 diamonds between them.
        assertEquals(64L, world.inStore(DIAMOND, Lot.PRISTINE));
        assertTrue(wealth(first) + wealth(second) < 4_000_000L, "four hundred sales paid no fee at all");
    }

    /** Everything a player owns in credits: the balance, what waits to be collected, and open buy escrow. */
    private long wealth(UUID player) {
        long escrowed = 0L;
        for (BuyOrder order : book().buysOf(TradeWorld.account(player))) escrowed += order.escrow();
        return world.balance(player) + world.claim(player) + escrowed;
    }

    @Test void aModifierThatThrowsChangesNothing() {
        UUID seller = seller(1, 5);
        assertTrue(sell(seller, DIAMOND, 5, 10L).ok());
        UUID buyer = world.player(2, 1_000L);
        world.env = world.env.with((who, listing, item, base, available) -> { throw new IllegalStateException("broken"); });
        Placed placed = buy(buyer, DIAMOND, 5, 10L);
        assertEquals(5, placed.filled());
        assertEquals(50L, placed.buyerPaid());
    }

    // --- fees --------------------------------------------------------------------------------

    @Test void everyFillPaysTheMarketFeeSoSplittingAnOrderNeverPaysLess() {
        UUID whole = seller(1, 10);
        UUID split = seller(2, 10);
        UUID buyer = world.player(3, 10_000L);
        // One fill of 10 at 5: a fee of 1 on 50.
        assertTrue(sell(whole, DIAMOND, 10, 5L).ok());
        assertEquals(1L, buy(buyer, DIAMOND, 10, 5L).fees());
        assertEquals(49L, world.claim(whole));
        // The same goods as ten fills of one: a fee of at least 1 each time.
        assertTrue(sell(split, DIAMOND, 10, 5L).ok());
        long fees = 0L;
        for (int index = 0; index < 10; index++) fees += buy(buyer, DIAMOND, 1, 5L).fees();
        assertEquals(10L, fees);
        assertEquals(40L, world.claim(split));
    }

    @Test void theListingFeeIsChargedForEveryOrderAndAnOrderThatCannotPayItIsRefused() {
        UUID seller = world.player(1, 1L);
        world.stock(seller, Lot.of(DIAMOND, 10));
        long reserve = world.reserve();
        assertTrue(sell(seller, DIAMOND, 5, 5L).ok());
        assertEquals(0L, world.balance(seller));
        assertEquals(reserve + 1L, world.reserve());

        // No credits left for a second listing fee: refused, and the goods are back in the vault.
        Placed refused = sell(seller, DIAMOND, 5, 5L);
        assertEquals(BookResult.FEE_UNPAID, refused.result());
        assertEquals(5L, world.held(seller, DIAMOND));
        assertEquals(1, book().size());
    }

    @Test void withNoFeesConfiguredNothingIsCharged() {
        world.env = world.env.with(TradeWorld.settings().withFees(0.0D, 0L, 0.0D));
        UUID seller = world.player(1, 0L);
        world.stock(seller, Lot.of(DIAMOND, 5));
        UUID buyer = world.player(2, 25L);
        long reserve = world.reserve();
        assertTrue(sell(seller, DIAMOND, 5, 5L).ok());
        assertEquals(5, buy(buyer, DIAMOND, 5, 5L).filled());
        assertEquals(25L, world.claim(seller));
        assertEquals(0L, world.balance(buyer));
        assertEquals(reserve, world.reserve());
    }

    // --- refusals: nothing moves -------------------------------------------------------------

    @Test void anOrderIsRefusedBeforeAnythingMovesWhenTheGoodsItWouldBuyAtOnceCannotBeStored() {
        UUID seller = seller(1, 10);
        assertTrue(sell(seller, DIAMOND, 10, 5L).ok());
        UUID buyer = world.player(2, 1_000L);
        // A vault of one stack, and it is taken.
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(buyer, Lot.of(TradeWorld.IRON, 1));

        Placed refused = buy(buyer, DIAMOND, 10, 5L);

        assertEquals(BookResult.STORAGE_FULL, refused.result());
        assertEquals(1_000L, world.balance(buyer));
        assertEquals(0L, world.escrow());
        assertEquals(10, book().sell(book().sellsOf(TradeWorld.account(seller)).get(0).id()).lot().count());
        // An order that fills nothing at once has nothing to store yet, so it is accepted.
        assertTrue(buy(buyer, DIAMOND, 10, 4L).ok());
    }

    @Test void aBuyOrderNeedsTheCreditsAndCannotUseAStartingBalanceThatHasNotVested() {
        UUID poor = world.player(1, 10L);
        assertEquals(BookResult.INSUFFICIENT_FUNDS, buy(poor, DIAMOND, 10, 5L).result());
        assertEquals(10L, world.balance(poor));
        UUID newcomer = world.newcomer(2);
        assertEquals(BookResult.FUNDS_LOCKED, buy(newcomer, DIAMOND, 10, 5L).result());
        assertEquals(100L, world.balance(newcomer));
        assertEquals(0, book().size());
        assertEquals(0L, world.escrow());
    }

    @Test void aSellOrderNeedsTheGoods() {
        UUID seller = seller(1, 3);
        assertEquals(BookResult.NOT_HOLDING, sell(seller, DIAMOND, 4, 5L).result());
        assertEquals(3L, world.held(seller, DIAMOND));
        assertEquals(100L, world.balance(seller));
    }

    @Test void malformedOrdersAreRefused() {
        UUID player = seller(1, 5);
        AccountId account = TradeWorld.account(player);
        assertEquals(BookResult.INVALID, sell(player, DIAMOND, 5, 0L).result());
        assertEquals(BookResult.INVALID, sell(player, DIAMOND, 5, -3L).result());
        assertEquals(BookResult.INVALID, buy(player, DIAMOND, 0, 5L).result());
        assertEquals(BookResult.INVALID, buy(player, DIAMOND, BookSection.MAX_ORDER_QUANTITY + 1, 5L).result());
        // A total that does not fit 64 bits is refused, not wrapped.
        assertEquals(BookResult.INVALID, buy(player, DIAMOND, 2_000, Long.MAX_VALUE / 1_000L).result());
        assertEquals(BookResult.INVALID, sell(player, DIAMOND, 5, Long.MAX_VALUE / 2L).result());
        // A player cannot place a House order, and a system account cannot place one while nothing keeps its stock.
        assertEquals(BookResult.INVALID, book().placeSell(world.env, account, Lot.of(DIAMOND, 1), 5L, OrderSource.HOUSE,
                VaultGoods.origin(world.store, world.env, account), null).result());
        AccountId house = SystemAccountId.house("ironworks");
        assertEquals(BookResult.INVALID, book().placeBuy(world.env, house, DIAMOND, 1, 5L, OrderSource.HOUSE, false,
                VaultGoods.receiver(world.store, world.env, house), null).result());
        assertEquals(0, book().size());
        assertEquals(5L, world.held(player, DIAMOND));
    }

    @Test void thePerAccountCapsRefuseOneOrderTooMany() {
        world.env = world.env.with(TradeWorld.settings().withOrderCaps(2, 1));
        UUID player = seller(1, 30);
        world.player(1, 1_000L);
        assertTrue(sell(player, DIAMOND, 1, 50L).ok());
        assertTrue(sell(player, DIAMOND, 1, 51L).ok());
        assertEquals(BookResult.LIMIT_REACHED, sell(player, DIAMOND, 1, 52L).result());
        assertTrue(buy(player, TradeWorld.IRON, 1, 2L).ok());
        assertEquals(BookResult.LIMIT_REACHED, buy(player, TradeWorld.IRON, 1, 3L).result());
        assertEquals(2, book().sellCount(TradeWorld.account(player)));
        assertEquals(1, book().buyCount(TradeWorld.account(player)));
        // Closing one frees its place.
        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, book().sellsOf(TradeWorld.account(player)).get(0).id(),
                TradeWorld.account(player)));
        assertTrue(sell(player, DIAMOND, 1, 52L).ok());
    }

    // --- trading against one named order -----------------------------------------------------

    @Test void buyingFromANamedOrderChargesTheBuyerOnceAndNeverAboveTheirCeiling() {
        UUID seller = seller(1, 10);
        Placed listed = sell(seller, DIAMOND, 10, 8L);
        UUID buyer = world.player(2, 100L);
        AccountId account = TradeWorld.account(buyer);
        GoodsReceiver vault = VaultGoods.receiver(world.store, world.env, account);

        assertEquals(BookResult.PRICE_MOVED, book().buyFrom(world.env, account, listed.orderId(), 3, 7L, vault).result());
        assertEquals(BookResult.OUT_OF_STOCK, book().buyFrom(world.env, account, listed.orderId(), 11, 8L, vault).result());
        assertEquals(BookResult.NOT_FOUND, book().buyFrom(world.env, account, UUID.randomUUID(), 1, 8L, vault).result());
        assertEquals(BookResult.OWN_ORDER, book().buyFrom(world.env, TradeWorld.account(seller), listed.orderId(), 1, 8L,
                VaultGoods.receiver(world.store, world.env, TradeWorld.account(seller))).result());
        assertEquals(BookResult.INSUFFICIENT_FUNDS, book().buyFrom(world.env, TradeWorld.account(world.player(3, 5L)),
                listed.orderId(), 1, 8L, VaultGoods.receiver(world.store, world.env, TradeWorld.account(world.player(3, 5L)))).result());
        assertEquals(100L, world.balance(buyer));

        Traded traded = book().buyFrom(world.env, account, listed.orderId(), 3, 9L, vault);

        assertTrue(traded.ok());
        assertTrue(traded.toVault());
        // The ceiling was 9; the price is 8, and 8 is what is charged. No listing fee: nothing was posted.
        assertEquals(24L, traded.fill().buyerPaid());
        assertEquals(76L, world.balance(buyer));
        assertEquals(3L, world.held(buyer, DIAMOND));
        assertEquals(23L, world.claim(seller));
        assertEquals(7, book().sell(listed.orderId()).lot().count());
    }

    @Test void fillingANamedBuyOrderPaysTheSellerFromItsEscrow() {
        UUID buyer = world.player(1, 500L);
        Placed wanted = buy(buyer, DIAMOND, 10, 9L);
        UUID seller = seller(2, 20);
        AccountId account = TradeWorld.account(seller);
        GoodsOrigin vault = VaultGoods.origin(world.store, world.env, account);

        assertEquals(BookResult.WRONG_ITEM, book().sellTo(world.env, account, wanted.orderId(), Lot.of(TradeWorld.IRON, 1), vault).result());
        assertEquals(BookResult.MODIFIED_ITEM, book().sellTo(world.env, account, wanted.orderId(), new Lot(DIAMOND, 1, 3), vault).result());
        assertEquals(BookResult.NOT_ENOUGH, book().sellTo(world.env, account, wanted.orderId(), Lot.of(DIAMOND, 11), vault).result());
        assertEquals(BookResult.OWN_ORDER, book().sellTo(world.env, TradeWorld.account(buyer), wanted.orderId(), Lot.of(DIAMOND, 1),
                VaultGoods.origin(world.store, world.env, TradeWorld.account(buyer))).result());
        UUID empty = world.player(3, 100L);
        assertEquals(BookResult.NOT_HOLDING, book().sellTo(world.env, TradeWorld.account(empty), wanted.orderId(), Lot.of(DIAMOND, 1),
                VaultGoods.origin(world.store, world.env, TradeWorld.account(empty))).result());

        Traded traded = book().sellTo(world.env, account, wanted.orderId(), Lot.of(DIAMOND, 4), vault);

        assertTrue(traded.ok());
        assertEquals(36L, traded.fill().base());
        assertEquals(1L, traded.fill().fee());
        assertEquals(35L, world.claim(seller));
        assertEquals(16L, world.held(seller, DIAMOND));
        assertEquals(4L, world.held(buyer, DIAMOND));
        assertEquals(6, book().buy(wanted.orderId()).remaining());
        assertEquals(54L, book().buy(wanted.orderId()).escrow());
    }

    // --- cancelling and expiry ---------------------------------------------------------------

    @Test void cancellingReturnsGoodsToTheVaultAndEscrowAsAClaimExactlyOnce() {
        UUID seller = seller(1, 10);
        Placed listed = sell(seller, DIAMOND, 10, 5L);
        UUID buyer = world.player(2, 500L);
        Placed wanted = buy(buyer, TradeWorld.IRON, 10, 5L);
        assertEquals(0L, world.held(seller, DIAMOND));

        // Somebody else's order is reported as not found, whichever kind it is.
        assertEquals(CancelResult.NOT_FOUND, book().cancel(world.env, listed.orderId(), TradeWorld.account(buyer)));
        assertEquals(CancelResult.NOT_FOUND, book().cancel(world.env, wanted.orderId(), TradeWorld.account(seller)));

        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, listed.orderId(), TradeWorld.account(seller)));
        assertEquals(10L, world.held(seller, DIAMOND));
        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, wanted.orderId(), TradeWorld.account(buyer)));
        assertEquals(50L, world.claim(buyer));
        assertEquals(50L, world.escrow());
        // A second cancel finds nothing and returns nothing twice.
        assertEquals(CancelResult.NOT_FOUND, book().cancel(world.env, listed.orderId(), TradeWorld.account(seller)));
        assertEquals(CancelResult.NOT_FOUND, book().cancel(world.env, wanted.orderId(), TradeWorld.account(buyer)));
        assertEquals(10L, world.held(seller, DIAMOND));
        assertEquals(50L, world.claim(buyer));
        assertEquals(0, book().size());
    }

    @Test void aSellOrderIsNotCancelledIntoAVaultThatCannotTakeTheGoods() {
        UUID seller = seller(1, 10);
        Placed listed = sell(seller, DIAMOND, 10, 5L);
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(seller, Lot.of(TradeWorld.IRON, 1));
        assertEquals(CancelResult.CLAIMS_FULL, book().cancel(world.env, listed.orderId(), TradeWorld.account(seller)));
        assertNotNull(book().sell(listed.orderId()));
    }

    @Test void ordersExpireOnTimeAndTheirGoodsAndEscrowGoBackEvenPastTheVaultCap() {
        UUID seller = seller(1, 10);
        Placed listed = sell(seller, DIAMOND, 10, 5L);
        UUID buyer = world.player(2, 500L);
        Placed wanted = buy(buyer, TradeWorld.IRON, 10, 5L);
        world.wall += 47L * TradeWorld.HOUR;
        assertEquals(0, book().expire(world.env, world.now()));
        // A buyer cannot buy from an order in the moment between its expiry and the sweep.
        world.wall += 2L * TradeWorld.HOUR;
        assertEquals(BookResult.NOT_FOUND, book().buyFrom(world.env, TradeWorld.account(buyer), listed.orderId(), 1, 5L,
                VaultGoods.receiver(world.store, world.env, TradeWorld.account(buyer))).result());
        // A full vault does not stop an expiry: a refund always books.
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(seller, Lot.of(TradeWorld.IRON, 1));

        assertEquals(2, book().expire(world.env, world.now()));

        assertEquals(10L, world.held(seller, DIAMOND));
        assertEquals(50L, world.claim(buyer));
        assertNull(book().buy(wanted.orderId()));
        assertEquals(0, book().size());
        assertEquals(0, book().expire(world.env, world.now()));
    }

    @Test void anExpiredOrderIsNeverMatched() {
        UUID seller = seller(1, 10);
        assertTrue(sell(seller, DIAMOND, 10, 5L).ok());
        world.wall += 49L * TradeWorld.HOUR;
        UUID buyer = world.player(2, 500L);
        Placed placed = buy(buyer, DIAMOND, 10, 5L);
        assertEquals(0, placed.filled());
        assertEquals(10, placed.rested());
    }

    // --- erasure, export and the saved form --------------------------------------------------

    @Test void erasingAPlayerDestroysTheirGoodsSweepsTheirEscrowAndLeavesEveryoneElseWhole() {
        UUID erased = seller(1, 20);
        world.player(1, 500L);
        UUID other = seller(2, 5);
        UUID buyer = world.player(3, 500L);
        assertTrue(sell(erased, DIAMOND, 10, 5L).ok());
        assertEquals(4, buy(buyer, DIAMOND, 4, 5L).filled());
        Placed wanted = buy(erased, TradeWorld.IRON, 10, 7L);
        Placed kept = sell(other, DIAMOND, 5, 9L);
        long owed = world.claim(erased);
        assertEquals(19L, owed);
        long reserve = world.reserve();

        List<Lot> destroyed = book().forgetPlayer(erased);

        assertEquals(List.of(Lot.of(DIAMOND, 6)), destroyed);
        assertNull(book().buy(wanted.orderId()));
        // The 70 credits behind the erased buy order went to the Reserve.
        assertEquals(reserve + 70L, world.reserve());
        // Nobody else lost anything: the other seller's order rests, the buyer keeps their goods.
        assertNotNull(book().sell(kept.orderId()));
        assertEquals(4L, world.held(buyer, DIAMOND));
        assertTrue(book().sellsOf(TradeWorld.account(erased)).isEmpty());
        // The export of an erased player holds no orders.
        JsonObject export = book().exportFor(erased);
        assertEquals(0, export.getAsJsonArray("sell_orders").size());
        assertEquals(0, export.getAsJsonArray("buy_orders").size());
        // Their uncollected claim is the market section's to erase; the escrow account still covers it.
        assertEquals(world.claim(erased), world.escrow());
    }

    @Test void theExportListsTheSubjectsOwnOrdersAndNamesNobodyElse() {
        UUID subject = seller(1, 10);
        world.player(1, 500L);
        UUID other = seller(2, 10);
        Placed listed = sell(subject, DIAMOND, 10, 5L);
        Placed wanted = buy(subject, TradeWorld.IRON, 3, 4L);
        assertTrue(sell(other, DIAMOND, 10, 6L).ok());

        JsonObject export = book().exportFor(subject);

        assertEquals(1, export.getAsJsonArray("sell_orders").size());
        JsonObject sellRow = export.getAsJsonArray("sell_orders").get(0).getAsJsonObject();
        assertEquals(listed.orderId().toString(), sellRow.get("id").getAsString());
        assertEquals(DIAMOND, sellRow.get("item").getAsString());
        assertEquals(10, sellRow.get("count").getAsInt());
        assertEquals(5L, sellRow.get("unit_price").getAsLong());
        JsonObject buyRow = export.getAsJsonArray("buy_orders").get(0).getAsJsonObject();
        assertEquals(wanted.orderId().toString(), buyRow.get("id").getAsString());
        assertEquals(12L, buyRow.get("escrow").getAsLong());
        String text = export.toString();
        assertFalse(text.contains(other.toString()));
        assertFalse(text.contains(subject.toString()));
    }

    @Test void theBookSurvivesASaveWithItsIdsPricesAndPlacesInTheQueue() {
        UUID first = seller(1, 5);
        UUID second = seller(2, 5);
        Placed older = sell(first, DIAMOND, 5, 5L);
        Placed newer = sell(second, DIAMOND, 5, 5L);
        UUID buyer = world.player(3, 1_000L);
        Placed wanted = buy(buyer, TradeWorld.IRON, 7, 3L);
        Lot sword = new Lot("minecraft:diamond_sword", 1, 4);
        world.stock(first, sword);
        Placed modified = sell(first, sword, 90L);

        world.reload();

        assertTrue(world.invariants());
        assertEquals(5L, book().sell(older.orderId()).unitPrice());
        assertEquals(OrderSource.VAULT, book().sell(newer.orderId()).source());
        assertEquals(4, book().sell(modified.orderId()).lot().payload());
        BuyOrder bid = book().buy(wanted.orderId());
        assertEquals(7, bid.remaining());
        assertEquals(21L, bid.escrow());
        assertEquals(21L, world.escrow());
        // The queue is as it was: the older of two equal prices still fills first.
        Placed next = buy(buyer, DIAMOND, 6, 5L);
        assertEquals(TradeWorld.account(first), next.fills().get(0).seller());
        assertEquals(5, next.fills().get(0).quantity());
        assertEquals(TradeWorld.account(second), next.fills().get(1).seller());
        // And a new order is younger than everything that was loaded.
        world.stock(first, Lot.of(DIAMOND, 1));
        Placed late = sell(first, DIAMOND, 1, 500L);
        assertTrue(book().sell(late.orderId()).sequence() > book().sell(modified.orderId()).sequence());
        assertTrue(book().sell(late.orderId()).sequence() > book().sell(newer.orderId()).sequence());
    }

    @Test void migratedBoardRowsKeepTheirIdsPricesExpiryAndEscrow() {
        UUID seller = world.player(1, 0L);
        UUID buyer = world.player(2, 500L);
        UUID listingId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        long expires = world.now() + 5L * TradeWorld.HOUR;
        // A buy offer from before the book had its credits in the escrow account already.
        assertTrue(world.ledger().apply(LedgerPlan.of(TradeWorld.account(buyer), SystemAccountId.ESCROW, 40L,
                TransactionType.MARKET_ESCROW)).ok());

        assertTrue(book().adoptSell(listingId, TradeWorld.account(seller), Lot.of(DIAMOND, 8), 6L, expires));
        assertTrue(book().adoptBuy(offerId, TradeWorld.account(buyer), TradeWorld.IRON, 10, 4L, 40L, expires));

        assertTrue(world.invariants());
        assertEquals(6L, book().sell(listingId).unitPrice());
        assertEquals(expires, book().sell(listingId).expiresAt());
        assertEquals(40L, book().buy(offerId).escrow());
        // Nothing was charged and nothing was matched by adopting them.
        assertEquals(0L, world.balance(seller));
        assertEquals(460L, world.balance(buyer));
        // An id is adopted once.
        assertFalse(book().adoptSell(listingId, TradeWorld.account(seller), Lot.of(DIAMOND, 1), 6L, expires));
        assertFalse(book().adoptBuy(listingId, TradeWorld.account(buyer), TradeWorld.IRON, 1, 4L, 4L, expires));
        // They trade like any other order afterwards.
        assertEquals(8, buy(buyer, DIAMOND, 8, 6L).filled());
        assertEquals(47L, world.claim(seller));
    }

    // --- live updates ------------------------------------------------------------------------

    @Test void theRevisionOnlyMovesWhenTheBookChangesAndSaysWhichItemsDid() {
        long start = book().revision();
        assertFalse(book().changedSince(start, item -> true));
        UUID seller = seller(1, 10);
        Placed listed = sell(seller, DIAMOND, 10, 5L);
        long afterSell = book().revision();
        assertTrue(afterSell > start);
        assertTrue(book().changedSince(start, item -> item.contains("diamond")));
        assertFalse(book().changedSince(start, item -> item.contains("iron")));
        assertEquals(java.util.Set.of(DIAMOND), book().changedItemsSince(start));
        // A refused order changes nothing.
        assertEquals(BookResult.NOT_HOLDING, sell(seller, DIAMOND, 10, 5L).result());
        assertEquals(afterSell, book().revision());
        assertEquals(CancelResult.CANCELLED, book().cancel(world.env, listed.orderId(), TradeWorld.account(seller)));
        assertTrue(book().changedSince(afterSell, item -> item.equals(DIAMOND)));
        // Far more changes than the book remembers count as a change to everything.
        world.stock(seller, Lot.of(DIAMOND, 2_000));
        world.player(1, 100_000L);
        long before = book().revision();
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 40; index++) ids.add(sell(seller, DIAMOND, 1, 1_000L + index).orderId());
        for (UUID id : ids) assertEquals(CancelResult.CANCELLED, book().cancel(world.env, id, TradeWorld.account(seller)));
        for (int index = 0; index < 600; index++) {
            Placed placed = sell(seller, DIAMOND, 1, 2_000L);
            book().cancel(world.env, placed.orderId(), TradeWorld.account(seller));
        }
        assertTrue(book().changedSince(before, item -> item.contains("iron")));
        assertNull(book().changedItemsSince(before));
    }

    // --- system accounts ---------------------------------------------------------------------

    @Test void aSystemAccountsOrdersTradeOnceSomethingKeepsItsStockAndNeverPayAModifier() {
        SystemAccountId house = SystemAccountId.house("ironworks");
        List<Lot> stock = new ArrayList<>();
        SystemDelivery delivery = new SystemDelivery() {
            @Override public boolean handles(SystemAccountId account) { return account.equals(house); }
            @Override public void deliver(SystemAccountId account, Lot lot) { stock.add(lot); }
        };
        UUID buyer = world.player(1, 1_000L);
        // Even with a modifier registered, a House order is settled at its own price.
        BuyerPricing pricing = (who, listing, item, base, available) -> base * 2L;
        world.env = new TradeEnv(TradeWorld.settings(), TradeWorld.LIMIT, pricing, null, null, delivery);
        GoodsOrigin houseStock = new GoodsOrigin() {
            @Override public boolean holds(Lot lot) { return true; }
            @Override public boolean take(Lot lot) { return true; }
            @Override public void restore(Lot lot) { stock.add(lot); }
        };

        Placed listed = book().placeSell(world.env, house, Lot.of(TradeWorld.IRON, 20), 3L, OrderSource.HOUSE, houseStock, null);
        assertTrue(listed.ok());
        assertEquals(0L, listed.listingFee());
        Placed bought = buy(buyer, TradeWorld.IRON, 5, 3L);
        assertEquals(5, bought.filled());
        assertEquals(15L, bought.buyerPaid());
        // The House is paid straight into its account, less the market fee.
        assertEquals(14L, world.ledger().getBalance(house, za.co.neroland.neroeconomy.NeroEconomyCommon.CREDITS));
        // What is left goes back to the House's stock when the order lapses.
        world.wall += 49L * TradeWorld.HOUR;
        assertEquals(1, book().expire(world.env, world.now()));
        assertEquals(List.of(Lot.of(TradeWorld.IRON, 15)), stock);
    }
}
