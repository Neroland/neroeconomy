package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.market.MarketSavedData;

/**
 * The fuzzer's escrow operations: the credit movements the market, jobs and auctions make — escrow
 * when an obligation is published, a refund or a payout when it is settled, the fee out of escrow.
 * What stays in escrow afterwards is booked in the same step as a credit claim in the market
 * section, exactly as the game books it, and collected through the market's own collect. So every
 * credit these put into the escrow account is recorded by the store, and the store's own
 * {@code checkInvariants()} — not a private model — is what the world checks after each of them.
 */
final class EscrowOperations {
    private EscrowOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new Escrow(), new Refund(), new Collect(), new EscrowFee(), new EscrowedSale(),
                new SystemJob());
    }

    private static MarketSavedData claims(FuzzWorld world) { return world.store.market(); }

    /**
     * Applies {@code plan} and books what it leaves in escrow as {@code owedTo}'s claim, or does
     * neither: the claim is checked before anything moves, as the game's settlement does.
     */
    private static boolean settle(FuzzWorld world, LedgerPlan plan, UUID owedTo, long owed, TransactionType reason) {
        if (owed > 0L && !claims(world).canAcceptCreditClaim(owedTo, owed)) return false;
        long before = world.ledger().balance(SystemAccountId.ESCROW);
        if (!world.ledger().apply(plan).ok()) return false;
        assertEquals(before + Math.max(0L, owed), world.ledger().balance(SystemAccountId.ESCROW));
        if (owed > 0L) assertTrue(claims(world).addCreditClaim(owedTo, owed, reason));
        return true;
    }

    /** A buy order or a bid escrowed and then cancelled: the credits wait in escrow as a refund claim. */
    static final class Escrow implements FuzzOperation {
        @Override public String name() { return "escrow"; }
        @Override public int weight() { return 70; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long amount = world.amount();
            if (!settle(world, LedgerPlan.of(FuzzWorld.account(player), SystemAccountId.ESCROW, amount,
                    TransactionType.MARKET_ESCROW), player, amount, TransactionType.ESCROW_REFUND)) return 0L;
            return amount;
        }
    }

    /**
     * A buy order part filled at a better price: the difference goes straight back to the buyer and
     * the rest waits in escrow as a refund claim, all in one plan.
     */
    static final class Refund implements FuzzOperation {
        @Override public String name() { return "escrow_refund"; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long amount = world.amount();
            if (amount <= 0L) return 0L;
            long part = 1L + world.random.nextLong(amount);
            AccountId account = FuzzWorld.account(player);
            if (!settle(world, LedgerPlan.builder()
                    .move(account, SystemAccountId.ESCROW, amount, TransactionType.MARKET_ESCROW)
                    .move(SystemAccountId.ESCROW, account, part, TransactionType.ESCROW_REFUND).build(),
                    player, amount - part, TransactionType.ESCROW_REFUND)) return 0L;
            return part;
        }
    }

    /** A player collects everything they are owed, through the market's own collect. */
    static final class Collect implements FuzzOperation {
        @Override public String name() { return "escrow_collect"; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long owed = world.owed(player);
            if (owed <= 0L) return 0L;
            long collected = claims(world).collectCreditsTotal(player);
            assertEquals(owed - collected, world.owed(player));
            return collected;
        }
    }

    /**
     * An auction won: the bid is escrowed, the fee goes out of escrow to the Reserve, and the rest
     * waits for the seller to collect.
     */
    static final class EscrowFee implements FuzzOperation {
        @Override public String name() { return "escrow_fee"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            UUID bidder = world.player();
            UUID seller = world.player();
            long amount = world.amount();
            long fee = Fees.fee(Math.max(0L, amount), world.random.nextBoolean() ? 2.0D : 5.0D);
            if (fee <= 0L || !settle(world, LedgerPlan.builder()
                    .move(FuzzWorld.account(bidder), SystemAccountId.ESCROW, amount, TransactionType.MARKET_ESCROW)
                    .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee,
                            world.random.nextBoolean() ? TransactionType.FEE_MARKET : TransactionType.FEE_AUCTION).build(),
                    seller, amount - fee, TransactionType.MARKET_PAYOUT)) return 0L;
            return fee;
        }
    }

    /**
     * A board sale as the market settles it, in one plan: a discount paid by the Reserve, the price
     * into escrow, the fee out of escrow. What stays in escrow is the seller's claim to collect.
     */
    static final class EscrowedSale implements FuzzOperation {
        @Override public String name() { return "escrowed_sale"; }
        @Override public int weight() { return 50; }
        @Override public long run(FuzzWorld world) {
            AccountId buyer = FuzzWorld.account(world.player());
            UUID seller = world.player();
            long amount = world.amount();
            long fee = Fees.fee(Math.max(0L, amount), 2.0D);
            if (!settle(world, LedgerPlan.builder()
                    .pay(BudgetKind.UNBUDGETED, buyer, world.random.nextInt(20), TransactionType.PRICE_ADJUSTMENT)
                    .move(buyer, SystemAccountId.ESCROW, amount, TransactionType.MARKET_PURCHASE)
                    .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee, TransactionType.FEE_MARKET).build(),
                    seller, amount - fee, TransactionType.MARKET_PAYOUT)) return 0L;
            return amount;
        }
    }

    /**
     * A server-funded job: its payout is escrowed from the Reserve within the system-job budget when
     * it is posted, and is then either earned by a worker (a claim to collect) or released back to
     * the Reserve when it expires.
     */
    static final class SystemJob implements FuzzOperation {
        @Override public String name() { return "system_job"; }
        @Override public int weight() { return 30; }
        @Override public long run(FuzzWorld world) {
            long payout = world.random.nextInt(400);
            if (world.random.nextBoolean()) {
                UUID worker = world.player();
                return settle(world, LedgerPlan.pay(BudgetKind.SYSTEM_JOB, SystemAccountId.ESCROW, payout,
                        TransactionType.CONTRACT_PAYOUT), worker, payout, TransactionType.CONTRACT_PAYOUT) ? payout : 0L;
            }
            return settle(world, LedgerPlan.builder()
                    .pay(BudgetKind.SYSTEM_JOB, SystemAccountId.ESCROW, payout, TransactionType.CONTRACT_PAYOUT)
                    .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, payout, TransactionType.ESCROW_REFUND).build(),
                    null, 0L, TransactionType.ESCROW_REFUND) ? payout : 0L;
        }
    }
}
