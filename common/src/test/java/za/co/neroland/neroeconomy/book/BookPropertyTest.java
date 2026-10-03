package za.co.neroland.neroeconomy.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The matching engine under any sequence of orders. After every single operation of a long random
 * run:
 * <ul>
 *   <li>no sell order and buy order of two different accounts are left crossing;</li>
 *   <li>credits are conserved (both ledger invariants) and so is every kind of goods;</li>
 *   <li>every account's worth — balance, uncollected claim and the escrow behind its own buy
 *       orders — is exactly what it started with, less the fees and purchases it paid, plus the
 *       proceeds of fills it sold into. So nobody ends with more credits than they started with
 *       except through a sale.</li>
 * </ul>
 */
class BookPropertyTest {
    private static final String[] ITEMS = {TradeWorld.DIAMOND, TradeWorld.IRON, TradeWorld.GOLD};
    private static final int PLAYERS = 8;
    private static final long START = 3_000L;
    private static final int STOCK = 150;
    private static final int MODIFIED_STOCK = 6;
    private static final int MODIFIED_PAYLOAD = 5;

    private TradeWorld world;

    @AfterEach void done() { if (world != null) world.close(); }

    @Test void anySequenceOfOrdersLeavesNoCrossAndConservesEverything() {
        for (long seed = 1L; seed <= 24L; seed++) {
            if (world != null) world.close();
            run(seed, 500);
        }
    }

    private void run(long seed, int operations) {
        world = new TradeWorld();
        // A vault that never fills, so no order is refused for want of room.
        world.env = world.env.with(TradeWorld.settings().withVault(2_000, 2_000, 8_000).withOrderCaps(12, 12));
        Random random = new Random(seed);
        BookSection book = world.store.book();
        List<UUID> players = new ArrayList<>();
        Map<AccountId, Long> expected = new LinkedHashMap<>();
        for (int index = 0; index < PLAYERS; index++) {
            UUID player = world.player(index + 1, START);
            players.add(player);
            expected.put(TradeWorld.account(player), START);
            for (String item : ITEMS) {
                world.stock(player, Lot.of(item, STOCK));
                world.stock(player, new Lot(item, MODIFIED_STOCK, MODIFIED_PAYLOAD));
            }
        }
        long totalSold = 0L;
        for (int step = 0; step < operations; step++) {
            UUID player = players.get(random.nextInt(PLAYERS));
            AccountId account = TradeWorld.account(player);
            String item = ITEMS[random.nextInt(ITEMS.length)];
            int count = 1 + random.nextInt(20);
            long price = 1L + random.nextInt(12);
            int kind = random.nextInt(10);
            if (random.nextInt(40) == 0) {
                // Now and then time passes and whatever lapsed is swept.
                world.wall += 20L * TradeWorld.HOUR;
                book.expire(world.env, world.now());
            }
            if (kind < 4) {
                int payload = random.nextInt(6) == 0 ? MODIFIED_PAYLOAD : Lot.PRISTINE;
                Placed placed = book.placeSell(world.env, account, new Lot(item, payload == Lot.PRISTINE ? count
                        : 1 + random.nextInt(2), payload), price, OrderSource.VAULT,
                        VaultGoods.origin(world.store, world.env, account), null);
                totalSold += record(expected, account, placed);
            } else if (kind < 8) {
                Placed placed = book.placeBuy(world.env, account, item, count, price, OrderSource.VAULT, false,
                        VaultGoods.receiver(world.store, world.env, account), null);
                totalSold += record(expected, account, placed);
            } else if (kind == 8) {
                List<SellOrder> own = book.sellsOf(account);
                List<BuyOrder> wanted = book.buysOf(account);
                if (random.nextBoolean() && !own.isEmpty())
                    book.cancel(world.env, own.get(random.nextInt(own.size())).id(), account);
                else if (!wanted.isEmpty()) book.cancel(world.env, wanted.get(random.nextInt(wanted.size())).id(), account);
            } else {
                // A direct trade against somebody's order, at a ceiling that may or may not be enough.
                List<SellOrder> asks = book.sellsMatching(any -> true, world.now());
                List<BuyOrder> bids = book.buysMatching(any -> true, world.now());
                if (random.nextBoolean() && !asks.isEmpty()) {
                    SellOrder ask = asks.get(random.nextInt(asks.size()));
                    Traded traded = book.buyFrom(world.env, account, ask.id(), Math.min(count, ask.lot().count()),
                            price, VaultGoods.receiver(world.store, world.env, account));
                    if (traded.ok()) totalSold += settle(expected, traded.fill());
                } else if (!bids.isEmpty()) {
                    BuyOrder bid = bids.get(random.nextInt(bids.size()));
                    Traded traded = book.sellTo(world.env, account, bid.id(), Lot.of(bid.item(), Math.min(count,
                            bid.remaining())), VaultGoods.origin(world.store, world.env, account));
                    if (traded.ok()) totalSold += settle(expected, traded.fill());
                }
            }
            check(seed, step, players, expected);
        }
        // The run was not a run of refusals.
        assertTrue(totalSold > 0L, "seed " + seed + " never traded");
    }

    /** Books what an order did to the accounts' expected worth. @return the value sold */
    private static long record(Map<AccountId, Long> expected, AccountId owner, Placed placed) {
        if (!placed.ok()) return 0L;
        expected.merge(owner, -placed.listingFee(), Long::sum);
        long sold = 0L;
        for (Fill fill : placed.fills()) sold += settle(expected, fill);
        return sold;
    }

    private static long settle(Map<AccountId, Long> expected, Fill fill) {
        expected.merge(fill.buyer(), -fill.buyerPaid(), Long::sum);
        expected.merge(fill.seller(), fill.sellerReceives(), Long::sum);
        assertEquals(fill.base(), fill.sellerReceives() + fill.fee());
        assertTrue(fill.fee() >= 1L, "every fill pays a fee");
        return fill.base();
    }

    private void check(long seed, int step, List<UUID> players, Map<AccountId, Long> expected) {
        Supplier<String> where = () -> "seed " + seed + ", step " + step;
        BookSection book = world.store.book();
        assertTrue(world.invariants(), where);
        // Goods: every kind is still all there, in a vault or on the book.
        for (String item : ITEMS) {
            assertEquals((long) PLAYERS * STOCK, world.inStore(item, Lot.PRISTINE), where);
            assertEquals((long) PLAYERS * MODIFIED_STOCK, world.inStore(item, MODIFIED_PAYLOAD), where);
        }
        // No crossing pair of two different accounts is left resting.
        long now = world.now();
        for (String item : ITEMS) {
            List<SellOrder> asks = book.asks(item);
            for (BuyOrder bid : book.bids(item)) {
                if (bid.expiresAt() <= now) continue;
                for (SellOrder ask : asks) {
                    if (ask.unitPrice() > bid.unitPrice()) break;
                    if (ask.expiresAt() <= now) continue;
                    assertEquals(ask.owner(), bid.owner(), () -> where.get() + ": " + ask + " crosses " + bid);
                }
            }
        }
        // Credits: each account is worth exactly what the operations say it should be.
        long escrowed = 0L;
        for (UUID player : players) {
            AccountId account = TradeWorld.account(player);
            long held = 0L;
            for (BuyOrder bid : book.buysOf(account)) {
                assertEquals(bid.unitPrice() * bid.remaining(), bid.escrow(), where);
                held += bid.escrow();
            }
            assertEquals(expected.get(account), world.balance(player) + world.claim(player) + held,
                    () -> where.get() + ", " + player);
            escrowed += held + world.claim(player);
        }
        assertEquals(escrowed, world.escrow(), where);
    }
}
