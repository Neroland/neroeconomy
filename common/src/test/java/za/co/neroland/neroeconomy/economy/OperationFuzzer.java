package za.co.neroland.neroeconomy.economy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The operation fuzzer: one harness over the real ledger section, the real vault section, the real
 * simulation driver and a pure model of escrow. It picks operations at random by weight, runs each
 * against a {@link FuzzWorld}, and after <b>every</b> operation checks Invariant A (all balances
 * equal minted less burned), Invariant B (the escrow account equals what is owed from it),
 * Invariant C (the exchange account equals the open positions the exchange records) and item
 * conservation (no kind of goods appears or vanishes unless an operation said so).
 *
 * <p>The exchange is under it as well — shares, the index fund, Savings Bonds and practice mode,
 * with prices, splits, dividends, maturities and delisting payouts running in the world's
 * simulation steps.
 *
 * <p>Direct trades and rules bound to Auto-Trader blocks are under it too: a trade's escrow is a
 * section of the store, and a block's slots are counted beside the store (see {@link FuzzWorld}).
 *
 * <p><b>Adding a part of the economy:</b> write a {@link FuzzOperation} class for each thing it
 * can do and add it to {@link #OPERATIONS}. If the new section escrows credits it implements
 * {@code EscrowHolder} and if it holds goods it implements {@code LotHolder}; the world's checks
 * then cover it without any change here.
 */
final class OperationFuzzer {
    private OperationFuzzer() { }

    /** Every kind of operation the fuzzer picks from. */
    static final List<FuzzOperation> OPERATIONS = operations();

    private static List<FuzzOperation> operations() {
        List<FuzzOperation> all = new ArrayList<>();
        all.addAll(LedgerOperations.all());
        all.addAll(EscrowOperations.all());
        all.addAll(VaultOperations.all());
        all.addAll(LifecycleOperations.all());
        all.addAll(BookOperations.all());
        all.addAll(OfferOperations.all());
        all.addAll(RuleOperations.all());
        all.addAll(DirectTradeOperations.all());
        all.addAll(TraderOperations.all());
        all.addAll(ExchangeOperations.all());
        all.addAll(HouseOperations.all());
        all.addAll(DealOperations.all());
        return List.copyOf(all);
    }

    /**
     * What a run did.
     *
     * @param world   the world as the run left it
     * @param calls   how often each operation was picked
     * @param effects how often each operation did something rather than being refused
     */
    record Result(FuzzWorld world, Map<String, Integer> calls, Map<String, Integer> effects) { }

    /**
     * Runs {@code count} operations from {@code seed}.
     *
     * @param checkpoints operation indices at which the clock's saved watermark is brought up to date
     * @param crash       whether the store is also saved and reloaded at each checkpoint
     */
    static Result run(long seed, int count, Set<Integer> checkpoints, boolean crash) {
        return run(seed, count, checkpoints, crash, List.of());
    }

    /**
     * {@link #run(long, int, Set, boolean)} with {@code extra} operations picked alongside the
     * standard ones. The standard seeds keep their exact sequences because they run without extras.
     */
    static Result run(long seed, int count, Set<Integer> checkpoints, boolean crash, List<FuzzOperation> extra) {
        List<FuzzOperation> operations = new ArrayList<>(OPERATIONS);
        operations.addAll(extra);
        FuzzWorld world = new FuzzWorld(seed);
        int totalWeight = 0;
        for (FuzzOperation operation : operations) totalWeight += Math.max(1, operation.weight());
        Map<String, Integer> calls = new LinkedHashMap<>();
        Map<String, Integer> effects = new LinkedHashMap<>();
        for (FuzzOperation operation : operations) {
            calls.put(operation.name(), 0);
            effects.put(operation.name(), 0);
        }
        world.check("start", -1);
        for (int step = 0; step < count; step++) {
            // Time passes between operations: up to twenty seconds, on the world's own clock.
            world.wall += world.random.nextInt(20_000);
            FuzzOperation operation = pick(world, operations, totalWeight);
            long outcome = operation.run(world);
            world.digest = world.digest * 31L + outcome * 7L + operation.name().hashCode();
            calls.merge(operation.name(), 1, Integer::sum);
            if (outcome != 0L) effects.merge(operation.name(), 1, Integer::sum);
            world.check(operation.name(), step);
            if (checkpoints.contains(step)) {
                world.checkpoint();
                // No trade window survives a restart, crashed or clean: either way what its sides
                // had put in is back in their vaults afterwards.
                if (crash) world.crash();
                else {
                    world.stopTrades();
                    // The one thing a restart changes by design: the Merchant Houses' pair tally of
                    // the step in flight is memory only (never saved, see HouseSection#forgetPairTally),
                    // so after any stop that step moves no reference. The run that does not crash
                    // stops cleanly here, and a clean stop loses that tally too.
                    world.store.houses().forgetPairTally();
                }
                // What was read back satisfies every invariant before anything else happens to it.
                world.check(crash ? "crash" : "stop", step);
            }
        }
        return new Result(world, calls, effects);
    }

    private static FuzzOperation pick(FuzzWorld world, List<FuzzOperation> operations, int totalWeight) {
        int roll = world.random.nextInt(totalWeight);
        for (FuzzOperation operation : operations) {
            roll -= Math.max(1, operation.weight());
            if (roll < 0) return operation;
        }
        throw new IllegalStateException("weights");
    }
}
