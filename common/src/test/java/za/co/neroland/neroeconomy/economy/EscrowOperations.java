package za.co.neroland.neroeconomy.economy;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * The fuzzer's escrow operations, with the pure model of who is owed what
 * ({@link FuzzWorld#escrow}). They are the credit movements the market, jobs and auctions make —
 * escrow when an obligation is published, a refund or a payout when it is settled, the fee out of
 * escrow — and every one of them moves the escrow account and the model together, or neither.
 */
final class EscrowOperations {
    private EscrowOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new Escrow(), new Refund(), new Collect(), new EscrowFee(), new EscrowedSale(),
                new SystemJob());
    }

    /** A buy order, a bid or a job payout: the player's credits move into escrow. */
    static final class Escrow implements FuzzOperation {
        @Override public String name() { return "escrow"; }
        @Override public int weight() { return 70; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long amount = world.amount();
            if (!world.ledger().apply(LedgerPlan.of(FuzzWorld.account(player), SystemAccountId.ESCROW, amount,
                    TransactionType.MARKET_ESCROW)).ok()) return 0L;
            world.owe(player, amount);
            return amount;
        }
    }

    /** Part of what a player is owed goes back to them, as a cancel or an expiry refunds it. */
    static final class Refund implements FuzzOperation {
        @Override public String name() { return "escrow_refund"; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long owed = world.owed(player);
            if (owed <= 0L) return 0L;
            long amount = 1L + world.random.nextLong(owed);
            if (!world.ledger().apply(LedgerPlan.of(SystemAccountId.ESCROW, FuzzWorld.account(player), amount,
                    TransactionType.ESCROW_REFUND)).ok()) return 0L;
            world.owe(player, -amount);
            return amount;
        }
    }

    /** A player collects everything they are owed. */
    static final class Collect implements FuzzOperation {
        @Override public String name() { return "escrow_collect"; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long owed = world.owed(player);
            if (owed <= 0L || !world.ledger().apply(LedgerPlan.of(SystemAccountId.ESCROW, FuzzWorld.account(player),
                    owed, TransactionType.MARKET_PAYOUT)).ok()) return 0L;
            world.owe(player, -owed);
            return owed;
        }
    }

    /** A fee comes out of what is held for a player and goes to the Reserve. */
    static final class EscrowFee implements FuzzOperation {
        @Override public String name() { return "escrow_fee"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long owed = world.owed(player);
            long fee = Fees.fee(owed, world.random.nextBoolean() ? 2.0D : 5.0D);
            if (fee <= 0L || !world.ledger().apply(LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee,
                    world.random.nextBoolean() ? TransactionType.FEE_MARKET : TransactionType.FEE_AUCTION)).ok())
                return 0L;
            world.owe(player, -fee);
            return fee;
        }
    }

    /**
     * A board sale as the market settles it, in one plan: a discount paid by the Reserve, the price
     * into escrow, the fee out of escrow. What stays in escrow is the seller's to collect.
     */
    static final class EscrowedSale implements FuzzOperation {
        @Override public String name() { return "escrowed_sale"; }
        @Override public int weight() { return 50; }
        @Override public long run(FuzzWorld world) {
            AccountId buyer = FuzzWorld.account(world.player());
            UUID seller = world.player();
            long amount = world.amount();
            long fee = Fees.fee(Math.max(0L, amount), 2.0D);
            if (!world.ledger().apply(LedgerPlan.builder()
                    .pay(BudgetKind.UNBUDGETED, buyer, world.random.nextInt(20), TransactionType.PRICE_ADJUSTMENT)
                    .move(buyer, SystemAccountId.ESCROW, amount, TransactionType.MARKET_PURCHASE)
                    .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee, TransactionType.FEE_MARKET).build()).ok())
                return 0L;
            world.owe(seller, amount - fee);
            return amount;
        }
    }

    /**
     * A server-funded job: its payout is escrowed from the Reserve within the system-job budget when
     * it is posted, and is later either earned by a worker or released back to the Reserve.
     */
    static final class SystemJob implements FuzzOperation {
        @Override public String name() { return "system_job"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            long held = world.owed(FuzzWorld.SYSTEM_ISSUER);
            switch (world.random.nextInt(3)) {
                case 0 -> {
                    long payout = world.random.nextInt(400);
                    if (!world.ledger().apply(LedgerPlan.pay(BudgetKind.SYSTEM_JOB, SystemAccountId.ESCROW, payout,
                            TransactionType.CONTRACT_PAYOUT)).ok()) return 0L;
                    world.owe(FuzzWorld.SYSTEM_ISSUER, payout);
                    return payout;
                }
                case 1 -> {
                    // Fulfilled: the escrow stays where it is and becomes a worker's claim.
                    if (held <= 0L) return 0L;
                    long payout = 1L + world.random.nextLong(held);
                    world.owe(FuzzWorld.SYSTEM_ISSUER, -payout);
                    world.owe(world.player(), payout);
                    return payout;
                }
                default -> {
                    // Expired: the escrow goes back to the Reserve it came from.
                    if (held <= 0L || !world.ledger().apply(LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE,
                            held, TransactionType.ESCROW_REFUND)).ok()) return 0L;
                    world.owe(FuzzWorld.SYSTEM_ISSUER, -held);
                    return held;
                }
            }
        }
    }
}
