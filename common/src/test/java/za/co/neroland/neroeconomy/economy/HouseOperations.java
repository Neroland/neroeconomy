package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.book.Traded;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The fuzzer's Merchant House operations, against the real House section and the real book. The
 * Houses step with the simulation (see {@link FuzzWorld#houseEnv}); here players buy from their
 * asks and sell to their bids, and the server tick re-quotes what was traded. Their stock is a
 * place goods can be, restocking makes goods and the sink destroys them — the world's item check
 * counts both — and their credits are ledger balances, so Invariants A and B cover them as they are.
 */
final class HouseOperations {
    private HouseOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new BuyFromHouse(), new SellToHouse(), new Requote());
    }

    private static boolean house(AccountId account) { return account instanceof SystemAccountId system && system.isHouse(); }

    /** A player buys straight from a House's ask, sometimes for more than they may. */
    static final class BuyFromHouse implements FuzzOperation {
        @Override public String name() { return "house_buy"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            List<SellOrder> asks = new ArrayList<>();
            for (SellOrder order : world.store.book().sellsMatching(item -> true, world.ledger().now()))
                if (house(order.owner())) asks.add(order);
            if (asks.isEmpty()) return 0L;
            SellOrder ask = asks.get(world.random.nextInt(asks.size()));
            AccountId buyer = FuzzWorld.account(world.player());
            int quantity = 1 + world.random.nextInt(ask.lot().count() + 2);
            boolean live = world.random.nextBoolean();
            Traded traded = world.store.book().buyFrom(world.env, buyer, ask.id(), quantity,
                    ask.unitPrice() - (world.random.nextInt(8) == 0 ? 1L : 0L),
                    live ? BookOperations.inventory(world) : VaultGoods.receiver(world.store, world.env, buyer));
            if (!traded.result().ok()) {
                assertTrue(traded.result() != BookResult.INTERNAL);
                return 0L;
            }
            // A House's price never moves for a modifier.
            assertTrue(traded.fill().buyerPaid() == traded.fill().base());
            return traded.fill().buyerPaid();
        }
    }

    /** A player sells to a House's bid, from the hand or the vault. */
    static final class SellToHouse implements FuzzOperation {
        @Override public String name() { return "house_sell"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            List<BuyOrder> bids = new ArrayList<>();
            for (BuyOrder order : world.store.book().buysMatching(item -> true, world.ledger().now()))
                if (house(order.owner())) bids.add(order);
            if (bids.isEmpty()) return 0L;
            BuyOrder bid = bids.get(world.random.nextInt(bids.size()));
            AccountId seller = FuzzWorld.account(world.player());
            Lot lot = Lot.of(bid.item(), 1 + world.random.nextInt(bid.remaining() + 1));
            Traded traded = world.store.book().sellTo(world.env, seller, bid.id(), lot, world.random.nextBoolean()
                    ? BookOperations.hand(world) : VaultGoods.origin(world.store, world.env, seller));
            if (!traded.result().ok()) return 0L;
            return traded.fill().base() + traded.fill().fee() * 31L;
        }
    }

    /** The server tick re-quotes what the Houses traded since they last quoted it. */
    static final class Requote implements FuzzOperation {
        @Override public String name() { return "house_requote"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            return world.store.houses().requoteDirty(world.houseEnv(), 3);
        }
    }
}
