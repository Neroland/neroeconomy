package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * The fuzzer's ledger operations: every way credits move that does not involve the escrow account.
 * Only a mint, a burn, an operator's set-balance and the Reserve top-up change the supply; every
 * one of the others is a transfer and must leave it exactly where it was.
 */
final class LedgerOperations {
    private LedgerOperations() { }

    private static final TransactionType[] REASONS = TransactionType.values();
    private static final BudgetKind[] BUDGETS = BudgetKind.values();

    private static TransactionType reason(FuzzWorld world) { return REASONS[world.random.nextInt(REASONS.length)]; }
    private static BudgetKind budget(FuzzWorld world) { return BUDGETS[world.random.nextInt(BUDGETS.length)]; }
    private static long flag(boolean done) { return done ? 1L : 0L; }

    static List<FuzzOperation> all() {
        return List.of(new Pay(), new SiblingDeposit(), new SiblingCharge(), new Mint(), new Burn(), new SetBalance(),
                new Transfer(), new Fee(), new ShopSale(), new ReservePayout(), new ProRataPayout(), new TopUp(),
                new Vest(), new Chip());
    }

    /** One player pays another; unvested starting credits cannot be sent. */
    static final class Pay implements FuzzOperation {
        @Override public String name() { return "pay"; }
        @Override public int weight() { return 80; }
        @Override public long run(FuzzWorld world) {
            return flag(world.ledger().transfer(world.player(), world.player(), NeroEconomyCommon.CREDITS, world.amount()));
        }
    }

    /** Another mod's deposit: a transfer out of the Reserve within the sibling budget. */
    static final class SiblingDeposit implements FuzzOperation {
        @Override public String name() { return "sibling_deposit"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            return flag(world.ledger().deposit(world.player(), NeroEconomyCommon.CREDITS, world.amount()));
        }
    }

    /** Another mod's charge: a transfer into the Reserve. */
    static final class SiblingCharge implements FuzzOperation {
        @Override public String name() { return "sibling_charge"; }
        @Override public long run(FuzzWorld world) {
            return flag(world.ledger().withdraw(world.player(), NeroEconomyCommon.CREDITS, world.amount()));
        }
    }

    /** A mint under every reason there is; only the minting reasons may succeed. */
    static final class Mint implements FuzzOperation {
        @Override public String name() { return "mint"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            TransactionType reason = reason(world);
            long amount = world.amount();
            if (!world.ledger().deposit(world.anyAccount(), NeroEconomyCommon.CREDITS, amount, reason)) return 0L;
            assertTrue(reason.mints(), reason.name());
            world.supply += amount;
            return amount;
        }
    }

    /** A burn under every reason there is; only the burning reasons may succeed. */
    static final class Burn implements FuzzOperation {
        @Override public String name() { return "burn"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            TransactionType reason = reason(world);
            long amount = world.amount();
            if (!world.ledger().withdraw(world.anyAccount(), NeroEconomyCommon.CREDITS, amount, reason)) return 0L;
            assertTrue(reason.burns(), reason.name());
            world.supply -= amount;
            return amount;
        }
    }

    /** An operator sets a balance: the difference is minted or burned. */
    static final class SetBalance implements FuzzOperation {
        @Override public String name() { return "set_balance"; }
        @Override public int weight() { return 10; }
        @Override public long run(FuzzWorld world) {
            EconomySavedData ledger = world.ledger();
            UUID player = world.player();
            TransactionType reason = reason(world);
            long target = Math.max(0L, world.amount());
            long before = ledger.totalBalancesForTest();
            if (!ledger.setBalance(player, target, reason)) return 0L;
            assertEquals(TransactionType.ADMIN_SET, reason);
            assertEquals(target, ledger.getBalance(player, NeroEconomyCommon.CREDITS));
            // The account may not have been open: only the totals say what the change was.
            world.supply += ledger.totalBalancesForTest() - before;
            return 1L + target;
        }
    }

    /** A transfer between any two accounts but escrow, under any reason. */
    static final class Transfer implements FuzzOperation {
        @Override public String name() { return "transfer"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            return flag(world.ledger().transfer(world.anyAccount(), world.anyAccount(), NeroEconomyCommon.CREDITS,
                    world.amount(), reason(world)));
        }
    }

    /** A fee paid straight to the Reserve; it may come out of unvested starting credits. */
    static final class Fee implements FuzzOperation {
        @Override public String name() { return "fee"; }
        @Override public int weight() { return 50; }
        @Override public long run(FuzzWorld world) {
            long fee = world.random.nextBoolean() ? Fees.fee(Math.max(0L, world.amount()), 2.0D) : Fees.flat(1L);
            return world.ledger().apply(LedgerPlan.of(FuzzWorld.account(world.player()), SystemAccountId.RESERVE, fee,
                    world.random.nextBoolean() ? TransactionType.FEE_MARKET : TransactionType.FEE_LISTING)).ok()
                            ? fee : 0L;
        }
    }

    /** A shop sale as the market settles it: the price to the seller, the fee out of it, a surcharge to the Reserve. */
    static final class ShopSale implements FuzzOperation {
        @Override public String name() { return "shop_sale"; }
        @Override public int weight() { return 50; }
        @Override public long run(FuzzWorld world) {
            AccountId buyer = FuzzWorld.account(world.player());
            AccountId seller = new PlayerAccountId(world.player());
            long amount = world.amount();
            return flag(world.ledger().apply(LedgerPlan.builder().move(buyer, seller, amount, TransactionType.SHOP_PURCHASE)
                    .move(seller, SystemAccountId.RESERVE, Fees.fee(Math.max(0L, amount), 2.0D), TransactionType.FEE_MARKET)
                    .move(buyer, SystemAccountId.RESERVE, world.random.nextInt(10), TransactionType.PRICE_ADJUSTMENT)
                    .build()).ok());
        }
    }

    /** One payment out of the Reserve, under any budget. */
    static final class ReservePayout implements FuzzOperation {
        @Override public String name() { return "reserve_payout"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            return flag(world.ledger().payFromReserve(budget(world), world.anyAccount(), world.amount(), reason(world)).ok());
        }
    }

    /** A batch out of the Reserve, scaled down when the budget is short. */
    static final class ProRataPayout implements FuzzOperation {
        @Override public String name() { return "pro_rata"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            Map<AccountId, Long> batch = new LinkedHashMap<>();
            for (int index = world.random.nextInt(4); index >= 0; index--)
                batch.put(FuzzWorld.account(world.player()), (long) world.random.nextInt(4_000));
            return world.ledger().payProRata(budget(world), batch, TransactionType.MARKET_PAYOUT).paid();
        }
    }

    /** The Reserve's own minting: a second initial fill mints nothing, a top-up mints at most once a day. */
    static final class TopUp implements FuzzOperation {
        @Override public String name() { return "top_up"; }
        @Override public int weight() { return 10; }
        @Override public long run(FuzzWorld world) {
            assertEquals(0L, world.ledger().seedReserve(Ledgers.RESERVE));
            long minted = world.ledger().topUpReserve();
            world.supply += minted;
            return minted;
        }
    }

    /** An account reaches the play time that unlocks its starting balance. */
    static final class Vest implements FuzzOperation {
        @Override public String name() { return "vest"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) { return flag(world.ledger().markVested(world.player())); }
    }

    /** A Credit Chip redemption is reserved, and sometimes handed back. */
    static final class Chip implements FuzzOperation {
        @Override public String name() { return "chip"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            if (!world.ledger().reserveChipRedemption(player, 5)) return 0L;
            if (world.random.nextBoolean()) world.ledger().releaseChipRedemption(player);
            return 1L;
        }
    }
}
