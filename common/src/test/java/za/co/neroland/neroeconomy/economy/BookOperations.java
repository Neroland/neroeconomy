package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.Fill;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.Traded;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.CancelResult;

/**
 * The fuzzer's order-book operations, against the real book section. An order's goods and escrow
 * are the store's own records, so the world's checks cover them without a model: the book reports
 * its lots and its escrow to the store, and Invariant B and item conservation are checked over
 * both after every operation. The only thing an operation here has to tell the world is when
 * goods cross between the store and "outside" — a sale from the hand, a purchase into an inventory.
 */
final class BookOperations {
    private BookOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new SellFromVault(), new SellFromHand(), new Buy(), new BuyIntoInventory(), new Cancel(),
                new BuyFrom(), new SellTo(), new Expire(), new CollectClaims());
    }

    /** An id drawn from the world's own random source, so a run is repeatable from its seed. */
    static UUID id(FuzzWorld world) { return new UUID(world.random.nextLong(), world.random.nextLong()); }

    static long price(FuzzWorld world) { return 1L + world.random.nextInt(world.random.nextInt(4) == 0 ? 60 : 9); }

    /** A number for the digest: what was placed, what filled and for how much. */
    private static long outcome(Placed placed) {
        if (!placed.ok()) return 0L;
        long value = 1L + placed.rested() * 31L + placed.listingFee();
        for (Fill fill : placed.fills()) {
            assertEquals(fill.base(), fill.sellerReceives() + fill.fee());
            assertTrue(fill.buyerPaid() >= 0L && fill.refund() >= 0L);
            value = value * 17L + fill.quantity() * 7L + fill.buyerPaid() + fill.fee();
        }
        return value;
    }

    /** Goods outside the store as the place a sale's goods come from: a hand. */
    static GoodsOrigin hand(FuzzWorld world) {
        return new GoodsOrigin() {
            @Override public boolean holds(Lot lot) { return world.outside(lot.key()) >= lot.count(); }
            @Override public boolean take(Lot lot) {
                if (!holds(lot)) return false;
                world.intoStore(lot.key(), lot.count());
                return true;
            }
            @Override public void restore(Lot lot) { world.outOfStore(lot.key(), lot.count()); }
        };
    }

    /** An inventory that takes whatever it is given: the goods leave the store. */
    static GoodsReceiver inventory(FuzzWorld world) {
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) { return true; }
            @Override public boolean give(Lot lot) {
                world.outOfStore(lot.key(), lot.count());
                return false;
            }
        };
    }

    /** A sell order placed from the vault: by a command, or from the companion app. */
    static final class SellFromVault implements FuzzOperation {
        @Override public String name() { return "book_sell_vault"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            List<Lot> held = world.vault().stacks(account);
            // Mostly something the vault really holds; sometimes something it may not.
            Lot lot = held.isEmpty() || world.random.nextInt(6) == 0 ? world.lot()
                    : held.get(world.random.nextInt(held.size()));
            lot = lot.withCount(1 + world.random.nextInt(lot.count()));
            return outcome(world.store.book().placeSell(world.env, account, lot, price(world), OrderSource.VAULT,
                    VaultGoods.origin(world.store, world.env, account), id(world)));
        }
    }

    /** A sell order placed from the hand: the goods come into the store. */
    static final class SellFromHand implements FuzzOperation {
        @Override public String name() { return "book_sell_hand"; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            Lot lot = world.lot();
            return outcome(world.store.book().placeSell(world.env, account, lot, price(world), OrderSource.HAND,
                    hand(world), id(world)));
        }
    }

    /** A buy order whose goods go to the vault. */
    static final class Buy implements FuzzOperation {
        @Override public String name() { return "book_buy"; }
        @Override public int weight() { return 70; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            String item = FuzzWorld.ITEMS[world.random.nextInt(FuzzWorld.ITEMS.length)];
            // Now and then an item that does not exist, a quantity of nothing or a price of nothing.
            if (world.random.nextInt(40) == 0) item = "minecraft:no_such_item";
            int quantity = world.random.nextInt(30);
            long limit = world.random.nextInt(30) == 0 ? 0L : price(world);
            return outcome(world.store.book().placeBuy(world.env, account, item, quantity, limit, OrderSource.VAULT,
                    false, VaultGoods.receiver(world.store, world.env, account), id(world)));
        }
    }

    /** A buy order placed in game: what fills at once goes into the inventory, out of the store. */
    static final class BuyIntoInventory implements FuzzOperation {
        @Override public String name() { return "book_buy_live"; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            String item = FuzzWorld.ITEMS[world.random.nextInt(FuzzWorld.ITEMS.length)];
            return outcome(world.store.book().placeBuy(world.env, account, item, 1 + world.random.nextInt(20),
                    price(world), OrderSource.HAND, false, inventory(world), id(world)));
        }
    }

    /** A player cancels one of their own orders — or tries to cancel somebody else's. */
    static final class Cancel implements FuzzOperation {
        @Override public String name() { return "book_cancel"; }
        @Override public long run(FuzzWorld world) {
            BookSection book = world.store.book();
            AccountId account = FuzzWorld.account(world.player());
            List<SellOrder> sells = book.sellsOf(account);
            List<BuyOrder> buys = book.buysOf(account);
            if (world.random.nextInt(10) == 0) {
                // Somebody else's order is not found, whoever asks.
                AccountId stranger = FuzzWorld.account(world.player());
                UUID theirs = !sells.isEmpty() ? sells.get(0).id() : !buys.isEmpty() ? buys.get(0).id() : null;
                if (theirs != null && !stranger.equals(account))
                    assertEquals(CancelResult.NOT_FOUND, book.cancel(world.env, theirs, stranger));
                return 0L;
            }
            UUID id = world.random.nextBoolean() && !sells.isEmpty() ? sells.get(world.random.nextInt(sells.size())).id()
                    : !buys.isEmpty() ? buys.get(world.random.nextInt(buys.size())).id()
                    : !sells.isEmpty() ? sells.get(0).id() : null;
            if (id == null) return 0L;
            CancelResult result = book.cancel(world.env, id, account);
            // A second cancel of the same order never finds it again.
            if (result == CancelResult.CANCELLED) assertEquals(CancelResult.NOT_FOUND, book.cancel(world.env, id, account));
            return result == CancelResult.CANCELLED ? 1L : 0L;
        }
    }

    /** The terminal's "Buy": straight from one sell order, paid from the balance. */
    static final class BuyFrom implements FuzzOperation {
        @Override public String name() { return "book_buy_from"; }
        @Override public long run(FuzzWorld world) {
            BookSection book = world.store.book();
            List<SellOrder> asks = book.sellsMatching(item -> true, world.ledger().now());
            if (asks.isEmpty()) return 0L;
            SellOrder ask = asks.get(world.random.nextInt(asks.size()));
            AccountId buyer = FuzzWorld.account(world.player());
            int quantity = 1 + world.random.nextInt(ask.lot().count() + 1);
            // The ceiling is sometimes below the price, and sometimes generous.
            long ceiling = ask.unitPrice() + world.random.nextInt(4) - 1L;
            boolean live = world.random.nextBoolean();
            Traded traded = book.buyFrom(world.env, buyer, ask.id(), quantity, ceiling,
                    live ? inventory(world) : VaultGoods.receiver(world.store, world.env, buyer));
            if (!traded.ok()) return 0L;
            assertTrue(traded.fill().buyerPaid() <= ceiling * quantity);
            return traded.fill().buyerPaid() + traded.fill().fee() * 31L;
        }
    }

    /** The terminal's "fill this buy order": from the hand or from the vault. */
    static final class SellTo implements FuzzOperation {
        @Override public String name() { return "book_sell_to"; }
        @Override public long run(FuzzWorld world) {
            BookSection book = world.store.book();
            List<BuyOrder> bids = book.buysMatching(item -> true, world.ledger().now());
            if (bids.isEmpty()) return 0L;
            BuyOrder bid = bids.get(world.random.nextInt(bids.size()));
            AccountId seller = FuzzWorld.account(world.player());
            // Mostly the unmodified goods the order wants; sometimes a modified lot, which it refuses.
            Lot lot = new Lot(bid.item(), 1 + world.random.nextInt(bid.remaining() + 1),
                    world.random.nextInt(8) == 0 ? 1 : Lot.PRISTINE);
            Traded traded = book.sellTo(world.env, seller, bid.id(), lot, world.random.nextBoolean() ? hand(world)
                    : VaultGoods.origin(world.store, world.env, seller));
            if (!traded.ok()) return 0L;
            assertTrue(lot.pristine());
            return traded.fill().base() + traded.fill().fee() * 31L;
        }
    }

    /** The short-cadence expiry sweep the server tick runs. */
    static final class Expire implements FuzzOperation {
        @Override public String name() { return "book_expire"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            // Sometimes after a good while with nothing else happening, so there is something to sweep.
            if (world.random.nextInt(4) == 0) world.wall += 3_600_000L + world.random.nextInt(7_200_000);
            return world.store.book().expire(world.env, world.ledger().now());
        }
    }

    /** A player collects the credits the market owes them: proceeds of sales, refunds of cancelled orders. */
    static final class CollectClaims implements FuzzOperation {
        @Override public String name() { return "claims_collect"; }
        @Override public int weight() { return 50; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long owed = world.store.market().creditClaimTotal(player);
            long before = world.ledger().getBalance(player, za.co.neroland.neroeconomy.NeroEconomyCommon.CREDITS);
            long collected = world.store.market().collectCreditsTotal(player);
            assertTrue(collected <= owed);
            assertEquals(before + collected, world.ledger().getBalance(player, za.co.neroland.neroeconomy.NeroEconomyCommon.CREDITS));
            return collected;
        }
    }
}
