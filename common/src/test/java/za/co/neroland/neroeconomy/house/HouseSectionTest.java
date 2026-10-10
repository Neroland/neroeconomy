package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.CommodityClass;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.Traded;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The Merchant Houses against the real store and the real order book: quotes, pressure, caps
 * inside matching, capital within the NPC budget, restock, the daily guard re-check, the sink,
 * reference drift, erasure, export, a reload, and a week of a farm bot.
 */
class HouseSectionTest {
    private HouseWorld houses;

    @AfterEach void close() { if (houses != null) houses.world.close(); }

    private TradeWorld world() { return houses.world; }

    private static AccountId account(UUID player) { return TradeWorld.account(player); }

    private Traded buyFromHouse(UUID player, String item, int quantity) {
        SellOrder ask = houses.ask(item);
        assertNotNull(ask, "the House has no ask for " + item);
        return world().store.book().buyFrom(world().env, account(player), ask.id(), quantity, ask.unitPrice(),
                VaultGoods.receiver(world().store, world().env, account(player)));
    }

    private Traded sellToHouse(UUID player, String item, int quantity) {
        BuyOrder bid = houses.bid(item);
        assertNotNull(bid, "the House has no bid for " + item);
        world().stock(player, Lot.of(item, quantity));
        return world().store.book().sellTo(world().env, account(player), bid.id(), Lot.of(item, quantity),
                VaultGoods.origin(world().store, world().env, account(player)));
    }

    private void requote() { houses.section().requoteDirty(houses.env(), 100); }

    @Test void aStepPutsAskAboveBidOnTheBookFundedFromTheReserve() {
        houses = HouseWorld.standard();
        long reserve = world().reserve();
        houses.step();
        SellOrder ask = houses.ask(HouseWorld.WHEAT);
        BuyOrder bid = houses.bid(HouseWorld.WHEAT);
        assertEquals(11L, ask.unitPrice());
        assertEquals(64, ask.lot().count());
        assertEquals(9L, bid.unitPrice());
        assertEquals(64, bid.remaining());
        assertTrue(ask.unitPrice() > bid.unitPrice());
        // Diamonds are only bought, at 400 less 10 %, in chunks of an eighth of the day's demand.
        assertNull(houses.ask(HouseWorld.DIAMOND));
        assertEquals(360L, houses.bid(HouseWorld.DIAMOND).unitPrice());
        assertEquals(4, houses.bid(HouseWorld.DIAMOND).remaining());
        // Both Houses were topped up to exactly their working capital, out of the Reserve and the NPC budget.
        assertEquals(4_000L, houses.balance("growers") + bid.escrow());
        assertEquals(10_000L, houses.balance("general") + houses.bid(HouseWorld.DIAMOND).escrow());
        assertEquals(14_000L, reserve - world().reserve());
        assertEquals(14_000L, world().ledger().budgetUsed(BudgetKind.NPC));
        assertTrue(world().invariants());
        // A restock created the goods: the House holds them, in stock and on the book.
        assertEquals(86L, houses.section().created(HouseWorld.WHEAT));
        assertEquals(86L, world().inStore(HouseWorld.WHEAT, Lot.PRISTINE));
    }

    @Test void aPriceModifierNeverAppliesToAHouseAndUnvestedCreditsMayBuyFromOne() {
        houses = HouseWorld.standard();
        world().env = world().env.with((buyer, listing, item, base, available) -> base * 2L);
        houses.step();
        UUID newcomer = world().newcomer(1);
        // The starting balance of 100 is still locked, and a House is a system counterparty.
        Traded traded = buyFromHouse(newcomer, HouseWorld.WHEAT, 5);
        assertTrue(traded.result().ok(), traded.result().name());
        assertEquals(55L, traded.fill().buyerPaid());
        assertEquals(45L, world().balance(newcomer));
        assertTrue(world().invariants());
    }

    @Test void buyingRaisesTheAskSellingLowersTheBidAndBothRecover() {
        houses = HouseWorld.standard();
        houses.step();
        UUID buyer = houses.player(1, 5_000L);
        UUID seller = houses.player(2, 100L);
        assertTrue(buyFromHouse(buyer, HouseWorld.WHEAT, 32).result().ok());
        assertEquals(32L, world().held(buyer, HouseWorld.WHEAT));
        assertEquals(5_000L - 32L * 11L, world().balance(buyer));
        assertTrue(sellToHouse(seller, HouseWorld.WHEAT, 32).result().ok());
        // 32 × 9 = 288, less a 2 % market fee rounded up.
        assertEquals(282L, world().claim(seller));
        requote();
        // 0.8 × 32 / 512 = 5 %: 11 → 11.55 → 12, and 9 → 8.55 → 8.
        assertEquals(12L, houses.ask(HouseWorld.WHEAT).unitPrice());
        assertEquals(8L, houses.bid(HouseWorld.WHEAT).unitPrice());
        // Pressure decays by the recovery each step until the prices are back.
        houses.steps(250);
        assertEquals(11L, houses.ask(HouseWorld.WHEAT).unitPrice());
        assertEquals(9L, houses.bid(HouseWorld.WHEAT).unitPrice());
        assertTrue(world().invariants());
    }

    @Test void anAccountsDailyCapHoldsInsideMatchingWhicheverSidePlacesTheOrder() {
        houses = HouseWorld.standard();
        houses.step();
        UUID buyer = houses.player(1, 10_000L);
        // A buy order far above the ask, for more than the account may buy from the Houses in a day.
        Placed placed = world().store.book().placeBuy(world().env, account(buyer), HouseWorld.WHEAT, 200, 30L,
                OrderSource.VAULT, false, VaultGoods.receiver(world().store, world().env, account(buyer)), null);
        assertTrue(placed.ok());
        assertEquals(64, placed.filled());
        // The House re-quotes; its new ask crosses the player's resting bid, but the account is at its cap.
        for (int round = 0; round < 5; round++) {
            requote();
            houses.step();
        }
        assertEquals(64L, world().held(buyer, HouseWorld.WHEAT));
        assertEquals(136, world().store.book().buy(placed.orderId()).remaining());
        // Buying directly is refused with the reason.
        assertEquals(BookResult.DAILY_LIMIT, buyFromHouse(buyer, HouseWorld.WHEAT, 1).result());
        long[] left = houses.section().remainingToday(buyer, HouseWorld.WHEAT, world().now());
        assertEquals(0L, left[0]);
        assertEquals(64L, left[1]);
        // A new economy-day: the cap starts again, and the resting order fills from the next quote.
        world().wall += TradeWorld.DAY;
        houses.step();
        assertTrue(world().held(buyer, HouseWorld.WHEAT) > 64L);
        assertTrue(world().invariants());
    }

    @Test void theCommoditysGlobalCapHoldsAcrossAccounts() {
        CommodityDefinition stone = new CommodityDefinition(HouseWorld.STONE, CommodityDefinition.DIRECT_FILE, 4L, 16L,
                16L, 12L, 12L, 0.4D, 0.04D, 1_000, CommodityClass.STAPLE, "general", "", true, true);
        houses = new HouseWorld(List.of(stone), List.of(HouseWorld.house("general", 1_000L)));
        houses.steps(8);
        UUID first = houses.player(1, 1_000L);
        UUID second = houses.player(2, 1_000L);
        long bought = 0L;
        for (int round = 0; round < 10; round++) {
            for (UUID player : List.of(first, second)) {
                SellOrder ask = houses.ask(HouseWorld.STONE);
                if (ask == null) continue;
                Traded traded = buyFromHouse(player, HouseWorld.STONE, Math.min(ask.lot().count(), 12));
                if (traded.result().ok()) bought += traded.fill().quantity();
            }
            requote();
            houses.step();
        }
        assertEquals(16L, bought);
        assertTrue(world().held(first, HouseWorld.STONE) <= 12L);
        assertTrue(world().held(second, HouseWorld.STONE) <= 12L);
        // Selling is capped the same way.
        long sold = 0L;
        for (int round = 0; round < 10; round++) {
            for (UUID player : List.of(first, second)) {
                BuyOrder bid = houses.bid(HouseWorld.STONE);
                if (bid == null) continue;
                Traded traded = sellToHouse(player, HouseWorld.STONE, Math.min(bid.remaining(), 12));
                if (traded.result().ok()) sold += traded.fill().quantity();
            }
            requote();
            houses.step();
        }
        assertEquals(16L, sold);
        assertTrue(world().invariants());
    }

    @Test void incomeAboveWorkingCapitalIsSweptBackAndTopUpsNeverPassTheBudget() {
        houses = HouseWorld.standard();
        houses.step();
        UUID buyer = houses.player(1, 50_000L);
        long spent = 0L;
        for (int round = 0; round < 10; round++) {
            SellOrder ask = houses.ask(HouseWorld.WHEAT);
            if (ask != null) {
                Traded traded = buyFromHouse(buyer, HouseWorld.WHEAT, Math.min(ask.lot().count(), 6));
                if (traded.result().ok()) spent += traded.fill().buyerPaid();
            }
            requote();
        }
        assertTrue(spent > 0L);
        long[] parts = capital("growers");
        assertTrue(parts[0] + parts[1] > 4_000L, "income should lift the House above its capital before the step");
        houses.step();
        parts = capital("growers");
        assertEquals(4_000L, parts[0] + parts[1]);
        assertTrue(world().ledger().budgetUsed(BudgetKind.NPC) <= EconomySavedData.dailyLimit(BudgetKind.NPC));
        assertTrue(world().invariants());
    }

    private long[] capital(String house) {
        for (HouseSection.HouseReport report : houses.section().report(houses.houses))
            if (report.id().equals(house)) return new long[] {report.balance(), report.escrowed()};
        throw new AssertionError(house);
    }

    @Test void stockIsRestockedTowardADaysSupplyAndNeverAbove() {
        houses = HouseWorld.standard();
        houses.steps(30);
        long held = world().inStore(HouseWorld.WHEAT, Lot.PRISTINE);
        assertEquals(512L, held);
        assertEquals(512L, houses.section().created(HouseWorld.WHEAT));
        assertEquals(0L, houses.section().destroyed(HouseWorld.WHEAT));
    }

    @Test void goodsAHouseCannotKeepAreDestroyedAndCounted() {
        houses = HouseWorld.standard();
        houses.step();
        UUID seller = houses.player(1, 100L);
        assertTrue(sellToHouse(seller, HouseWorld.DIAMOND, 4).result().ok());
        // The general House never sells diamonds, so it keeps none: they are a sink.
        assertEquals(4L, houses.section().destroyed(HouseWorld.DIAMOND));
        assertEquals(0L, world().inStore(HouseWorld.DIAMOND, Lot.PRISTINE));
        assertEquals(4L * 360L - 29L, world().claim(seller));
        assertTrue(world().invariants());
    }

    @Test void theDailyGuardRecheckSwitchesABuySideOffAndBackOn() {
        houses = HouseWorld.standard();
        List<Boolean> spreads = new ArrayList<>();
        houses.guard = quotes -> {
            for (CommodityDefinition commodity : houses.commodities.commodities())
                spreads.add(quotes.ask(commodity) > quotes.bid(commodity));
            return Set.of(HouseWorld.WHEAT);
        };
        houses.step();
        assertNull(houses.bid(HouseWorld.WHEAT));
        assertNotNull(houses.ask(HouseWorld.WHEAT));
        assertNotNull(houses.bid(HouseWorld.DIAMOND));
        assertEquals(Set.of(HouseWorld.WHEAT), houses.section().driftBuyOff());
        assertFalse(houses.section().quote(HouseWorld.WHEAT).orElseThrow().buys());
        // Only once a day: a passing check later the same day changes nothing.
        houses.guard = HouseEnv.GuardCheck.NONE;
        houses.step();
        assertNull(houses.bid(HouseWorld.WHEAT));
        world().wall += TradeWorld.DAY;
        houses.step();
        assertNotNull(houses.bid(HouseWorld.WHEAT));
        assertTrue(houses.section().driftBuyOff().isEmpty());
        assertFalse(spreads.isEmpty());
        assertTrue(spreads.stream().allMatch(Boolean::booleanValue));
    }

    @Test void referencesDriftTowardWhatPlayersPayEachOtherWithinTheCapsAndBand() {
        houses = HouseWorld.standard();
        houses.step();
        UUID washer = houses.player(1, 1_000_000L);
        UUID partner = houses.player(2, 1_000_000L);
        UUID third = houses.player(3, 1_000_000L);
        // Fills with a House do not count.
        assertTrue(buyFromHouse(washer, HouseWorld.WHEAT, 10).result().ok());
        // Nor does a sale worth less than the minimum.
        trade(partner, washer, 1, 15L);
        trade(third, washer, 1, 15L);
        trade(partner, third, 1, 15L);
        houses.step();
        assertEquals(10_000L, houses.section().referenceMillis(HouseWorld.WHEAT));
        // Three accounts (three pairs) trade wheat round at three times its reference, all day long.
        long previous = houses.section().referenceMillis(HouseWorld.WHEAT);
        for (int step = 0; step < 90; step++) {
            trade(washer, partner, 10, 30L);
            trade(partner, third, 10, 30L);
            trade(third, washer, 10, 30L);
            houses.step();
            long now = houses.section().referenceMillis(HouseWorld.WHEAT);
            assertTrue(now >= previous);
            // Never more than 1 % of the data reference in one step.
            assertTrue(now - previous <= 100L, "moved " + (now - previous));
            previous = now;
        }
        long moved = previous - 10_000L;
        assertTrue(moved > 0L, "the reference should move toward what players pay");
        // Each fill uses both accounts' daily caps, so three accounts count at most 3 × 2000 / 2 credits;
        // against a day of House trade (10 × 1024 credits) the pull is at most 3000 / 10240 of the
        // 20-credit gap, and never past the day's reach of 10 % of the data reference.
        assertTrue(moved <= 20_000L * 3_000L / 10_240L + 1L, "moved " + moved);
        assertTrue(moved <= 1_000L, "moved " + moved);
        JsonObject export = houses.section().exportFor(washer);
        assertTrue(export.get("counted_toward_reference_prices_today").getAsLong() <= 2_000L);
        // The House quotes from the drifted reference.
        houses.section().quote(HouseWorld.WHEAT).ifPresent(quote -> assertTrue(quote.ask() >= 12L));
        assertTrue(world().invariants());
    }

    @Test void twoAltsWashTradingEveryStepForTwoHundredStepsMoveNoReference() {
        houses = HouseWorld.standard();
        houses.step();
        UUID alt = houses.player(1, 10_000_000L);
        UUID other = houses.player(2, 10_000_000L);
        List<Long> checked = new ArrayList<>();
        houses.guard = quotes -> {
            checked.add(houses.world.wall);
            return Set.of();
        };
        for (int step = 0; step < 200; step++) {
            // The cheapest wash the red team found (one unit at the minimum value), both ways round.
            trade(alt, other, 1, 16L);
            trade(other, alt, 1, 30L);
            houses.step();
            assertEquals(10_000L, houses.section().referenceMillis(HouseWorld.WHEAT), "step " + step);
        }
        // Nothing moved, so the guard ran only at each day's start (200 ten-minute steps span three days at most).
        assertTrue(checked.size() <= 3, "checked " + checked.size());
        assertTrue(world().invariants());
    }

    /**
     * Three pairs trade wheat, then the server restarts before the next step: crashed (the store
     * read back through its codecs) or stopped cleanly. Either way the pair tally, which is memory
     * only, is gone and the step moves no reference; without a restart the same trades do move it.
     * The saved store carries no pair tally at all.
     */
    @Test void aRestartDropsTheStepsPairTallyWhetherItCrashedOrStoppedCleanly() {
        long[] after = new long[3];
        for (int restart = 0; restart < 3; restart++) {
            houses = HouseWorld.standard();
            try {
                houses.step();
                UUID a = houses.player(1, 1_000_000L);
                UUID b = houses.player(2, 1_000_000L);
                UUID c = houses.player(3, 1_000_000L);
                trade(a, b, 10, 30L);
                trade(b, c, 10, 30L);
                trade(c, a, 10, 30L);
                String saved = za.co.neroland.neroeconomy.economy.StoreTesting.encode(world().store).toString();
                assertFalse(saved.contains("pair"), "the pair tally reached the saved store");
                if (restart == 1) world().reload();
                if (restart == 2) houses.section().forgetPairTally();
                houses.step();
                after[restart] = houses.section().referenceMillis(HouseWorld.WHEAT);
                assertTrue(world().invariants());
            } finally {
                houses.world.close();
                houses = null;
            }
        }
        assertTrue(after[0] > 10_000L, "three pairs did not move the reference without a restart");
        assertEquals(10_000L, after[1], "a crash and reload");
        assertEquals(10_000L, after[2], "a clean stop");
    }

    @Test void aReferenceThatMovesIsCheckedByTheGuardTheSameStepNotTomorrow() {
        houses = HouseWorld.standard();
        CommodityDefinition wheat = houses.commodities.commodities().stream()
                .filter(commodity -> commodity.key().equals(HouseWorld.WHEAT)).findFirst().orElseThrow();
        long[] firstAsk = {-1L};
        // A stand-in guard: a chain that only opens once wheat's price has moved from where it started.
        houses.guard = quotes -> {
            if (firstAsk[0] < 0L) firstAsk[0] = quotes.ask(wheat);
            return quotes.ask(wheat) > firstAsk[0] ? Set.of(HouseWorld.WHEAT) : Set.of();
        };
        houses.step();
        assertTrue(houses.section().driftBuyOff().isEmpty());
        UUID a = houses.player(1, 1_000_000L);
        UUID b = houses.player(2, 1_000_000L);
        UUID c = houses.player(3, 1_000_000L);
        long day = houses.day();
        for (int step = 0; step < 6 && houses.section().driftBuyOff().isEmpty(); step++) {
            trade(a, b, 10, 30L);
            trade(b, c, 10, 30L);
            trade(c, a, 10, 30L);
            houses.step();
        }
        assertEquals(day, houses.day(), "the test must stay inside one economy-day");
        assertTrue(houses.section().referenceMillis(HouseWorld.WHEAT) > 10_000L);
        assertEquals(Set.of(HouseWorld.WHEAT), houses.section().driftBuyOff());
        assertNull(houses.bid(HouseWorld.WHEAT));
    }

    /** {@code seller} lists {@code quantity} wheat at {@code price} and {@code buyer} buys it all. */
    private void trade(UUID seller, UUID buyer, int quantity, long price) {
        world().stock(seller, Lot.of(HouseWorld.WHEAT, quantity));
        Placed listed = world().store.book().placeSell(world().env, account(seller), Lot.of(HouseWorld.WHEAT, quantity),
                price, OrderSource.VAULT, VaultGoods.origin(world().store, world().env, account(seller)), null);
        assertTrue(listed.ok());
        Traded bought = world().store.book().buyFrom(world().env, account(buyer), listed.orderId(), quantity, price,
                VaultGoods.receiver(world().store, world().env, account(buyer)));
        assertTrue(bought.result().ok(), bought.result().name());
    }

    @Test void erasureForgetsThePlayerAndTheExportShowsOnlyTheirOwnRows() {
        houses = HouseWorld.standard();
        houses.step();
        UUID buyer = houses.player(1, 1_000L);
        UUID other = houses.player(2, 1_000L);
        assertTrue(buyFromHouse(buyer, HouseWorld.WHEAT, 5).result().ok());
        assertTrue(buyFromHouse(other, HouseWorld.WHEAT, 7).result().ok());
        JsonObject export = houses.section().exportFor(buyer);
        assertEquals(1, export.getAsJsonArray("traded_with_houses_today").size());
        assertEquals(5L, export.getAsJsonArray("traded_with_houses_today").get(0).getAsJsonObject()
                .get("bought_from_houses").getAsLong());
        assertFalse(export.toString().contains(other.toString()));
        houses.section().forgetPlayer(buyer);
        assertEquals(0, houses.section().exportFor(buyer).getAsJsonArray("traded_with_houses_today").size());
        assertFalse(houses.section().exportFor(other).getAsJsonArray("traded_with_houses_today").isEmpty());
    }

    @Test void aReloadKeepsStockCountsPressureOrdersAndCaps() {
        houses = HouseWorld.standard();
        houses.step();
        UUID buyer = houses.player(1, 5_000L);
        assertTrue(buyFromHouse(buyer, HouseWorld.WHEAT, 60).result().ok());
        requote();
        HouseSection.Quote before = houses.section().quote(HouseWorld.WHEAT).orElseThrow();
        world().reload();
        HouseSection.Quote after = houses.section().quote(HouseWorld.WHEAT).orElseThrow();
        assertEquals(before, after);
        // The reloaded section still knows the House's orders and the account's count.
        assertEquals(BookResult.DAILY_LIMIT, buyFromHouse(buyer, HouseWorld.WHEAT, 5).result());
        assertTrue(buyFromHouse(buyer, HouseWorld.WHEAT, 4).result().ok());
        assertEquals(houses.section().created(HouseWorld.WHEAT), 86L);
        assertTrue(world().invariants());
    }

    @Test void aFarmBotSellingTheMostItCanEveryStepForAWeekNeverDrawsMoreThanTheBudgetADay() {
        houses = HouseWorld.standard();
        List<UUID> bots = new ArrayList<>();
        for (int index = 0; index < 12; index++) bots.add(houses.player(10 + index, 100L));
        long limit = EconomySavedData.dailyLimit(BudgetKind.NPC);
        long reserveStart = world().reserve();
        long day = houses.day();
        long boughtToday = 0L;
        for (int step = 0; step < 7 * 144; step++) {
            for (UUID bot : bots) {
                BuyOrder bid = houses.bid(HouseWorld.WHEAT);
                if (bid != null) {
                    Traded traded = sellToHouse(bot, HouseWorld.WHEAT, Math.min(bid.remaining(), 64));
                    if (traded.result().ok()) boughtToday += traded.fill().quantity();
                }
                BuyOrder diamonds = houses.bid(HouseWorld.DIAMOND);
                if (diamonds != null) sellToHouse(bot, HouseWorld.DIAMOND, Math.min(diamonds.remaining(), 8));
                world().store.market().collectCreditsTotal(bot);
            }
            requote();
            houses.step();
            assertTrue(world().ledger().budgetUsed(BudgetKind.NPC) <= limit);
            assertTrue(houses.section().quote(HouseWorld.WHEAT).orElseThrow().boughtToday() <= 512L);
            if (houses.day() != day) {
                assertTrue(boughtToday <= 512L, "bought " + boughtToday);
                boughtToday = 0L;
                day = houses.day();
                assertTrue(world().invariants());
            }
        }
        // Over the week the Reserve paid out at most a budget a day (plus the first day).
        assertTrue(reserveStart - world().reserve() <= 8L * limit);
        assertTrue(world().invariants());
    }
}
