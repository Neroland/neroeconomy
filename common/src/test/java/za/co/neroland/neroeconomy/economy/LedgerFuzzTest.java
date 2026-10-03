package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * Invariant A under a long random run of every ledger operation: whatever is tried, in whatever
 * order, the sum of all balances equals what was minted less what was burned, no balance is ever
 * negative, and nothing but the minting and burning reasons changes the supply. The supply is
 * tracked here independently of the ledger's own counters, so the two are checked against each
 * other after every single operation.
 */
class LedgerFuzzTest {
    private static final int OPERATIONS = 20_000;
    private static final int PLAYERS = 12;
    private static final int ROUND_TRIP_EVERY = 2_500;

    private static final SystemAccountId[] SYSTEM = {SystemAccountId.RESERVE, SystemAccountId.ESCROW,
            SystemAccountId.EXCHANGE, SystemAccountId.house("ironworks"), SystemAccountId.house("granary")};

    @Test void seedOne() { run(1L); }
    @Test void seedFortyTwo() { run(42L); }
    @Test void seedToday() { run(20_261_003L); }

    private static void run(long seed) {
        Random random = new Random(seed);
        List<UUID> players = new ArrayList<>();
        for (int index = 0; index < PLAYERS; index++) players.add(new UUID(seed, index + 1L));
        EconomySavedData data = new EconomySavedData();
        // What the supply must be, counted from the outcomes of mints and burns alone.
        long supply = data.seedReserve(Ledgers.RESERVE);
        assertEquals(Ledgers.RESERVE, supply);
        TransactionType[] reasons = TransactionType.values();
        BudgetKind[] budgets = BudgetKind.values();
        for (int step = 0; step < OPERATIONS; step++) {
            UUID player = players.get(random.nextInt(PLAYERS));
            UUID other = players.get(random.nextInt(PLAYERS));
            PlayerAccountId account = new PlayerAccountId(player);
            AccountId any = random.nextInt(3) == 0 ? SYSTEM[random.nextInt(SYSTEM.length)] : new PlayerAccountId(other);
            AccountId anyFrom = random.nextInt(3) == 0 ? SYSTEM[random.nextInt(SYSTEM.length)] : account;
            TransactionType reason = reasons[random.nextInt(reasons.length)];
            long amount = switch (random.nextInt(8)) {
                case 0 -> 0L;
                case 1 -> random.nextInt(100_000);
                case 2 -> -1L - random.nextInt(50);
                default -> random.nextInt(300);
            };
            long before = data.totalBalancesForTest();
            switch (random.nextInt(19)) {
                // --- the calls another mod reaches through Core --------------------------------
                case 0 -> data.deposit(player, NeroEconomyCommon.CREDITS, amount);
                case 1 -> data.withdraw(player, NeroEconomyCommon.CREDITS, amount);
                case 2 -> data.transfer(player, other, NeroEconomyCommon.CREDITS, amount);
                // --- mint and burn, under every reason there is ---------------------------------
                case 3 -> {
                    boolean minted = data.deposit(any, NeroEconomyCommon.CREDITS, amount, reason);
                    if (minted) {
                        assertTrue(reason.mints(), reason.name());
                        supply += amount;
                    }
                }
                case 4 -> {
                    boolean burned = data.withdraw(anyFrom, NeroEconomyCommon.CREDITS, amount, reason);
                    if (burned) {
                        assertTrue(reason.burns(), reason.name());
                        supply -= amount;
                    }
                }
                case 5 -> {
                    long held = data.getBalance(player, NeroEconomyCommon.CREDITS);
                    long target = Math.max(0L, amount);
                    // The account may not be open yet: only the outcome says what the balance was.
                    if (data.setBalance(player, target, reason)) {
                        assertEquals(TransactionType.ADMIN_SET, reason);
                        supply += data.totalBalancesForTest() - before;
                        assertEquals(target, data.getBalance(player, NeroEconomyCommon.CREDITS));
                    } else assertTrue(held >= 0L);
                }
                // --- transfers ---------------------------------------------------------------------
                case 6 -> data.transfer(anyFrom, any, NeroEconomyCommon.CREDITS, amount, reason);
                case 7 -> data.apply(LedgerPlan.of(account, SystemAccountId.ESCROW, amount, TransactionType.MARKET_ESCROW));
                case 8 -> data.apply(LedgerPlan.of(SystemAccountId.ESCROW, account, amount, TransactionType.ESCROW_REFUND));
                case 9 -> {
                    // A board purchase as the market settles it: discount, purchase, fee.
                    long fee = Fees.fee(Math.max(0L, amount), 2.0D);
                    data.apply(LedgerPlan.builder()
                            .pay(BudgetKind.UNBUDGETED, account, random.nextInt(20), TransactionType.PRICE_ADJUSTMENT)
                            .move(account, SystemAccountId.ESCROW, amount, TransactionType.MARKET_PURCHASE)
                            .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee, TransactionType.FEE_MARKET).build());
                }
                case 10 -> {
                    // A shop sale: the price to the seller, the fee out of it, a surcharge to the Reserve.
                    PlayerAccountId seller = new PlayerAccountId(other);
                    data.apply(LedgerPlan.builder().move(account, seller, amount, TransactionType.SHOP_PURCHASE)
                            .move(seller, SystemAccountId.RESERVE, Fees.fee(Math.max(0L, amount), 2.0D), TransactionType.FEE_MARKET)
                            .move(account, SystemAccountId.RESERVE, random.nextInt(10), TransactionType.PRICE_ADJUSTMENT).build());
                }
                case 11 -> data.apply(LedgerPlan.builder().moveAllowingLocked(account, SYSTEM[random.nextInt(SYSTEM.length)],
                        amount, reason).build());
                // --- the Reserve -------------------------------------------------------------------
                case 12 -> data.payFromReserve(budgets[random.nextInt(budgets.length)], any, amount, reason);
                case 13 -> {
                    Map<AccountId, Long> batch = new LinkedHashMap<>();
                    for (int index = random.nextInt(4); index >= 0; index--)
                        batch.put(new PlayerAccountId(players.get(random.nextInt(PLAYERS))), (long) random.nextInt(4_000));
                    data.payProRata(budgets[random.nextInt(budgets.length)], batch, TransactionType.MARKET_PAYOUT);
                }
                case 14 -> {
                    // Already seeded: a second fill mints nothing. The daily top-up mints only if the
                    // economy day happens to change while the test runs, and that is a mint like any other.
                    assertEquals(0L, data.seedReserve(Ledgers.RESERVE));
                    supply += data.topUpReserve();
                }
                // --- vesting, chips, erasure -------------------------------------------------------
                case 15 -> data.markVested(player);
                case 16 -> {
                    if (data.reserveChipRedemption(player, 5) && random.nextBoolean()) data.releaseChipRedemption(player);
                }
                case 17 -> {
                    if (random.nextInt(6) == 0) {
                        data.forgetPlayer(player);
                        assertEquals(0L, data.getBalance(player, NeroEconomyCommon.CREDITS));
                    } else data.sweepErasedEscrow(amount);
                }
                default -> data.apply(LedgerPlan.pay(budgets[random.nextInt(budgets.length)], account, amount,
                        TransactionType.SIBLING_PAYOUT));
            }
            // After every operation, successful or refused.
            assertEquals(supply, data.totalBalancesForTest(), "seed " + seed + ", step " + step);
            assertTrue(data.supplyBalanced(), "seed " + seed + ", step " + step);
            assertTrue(data.balance(SystemAccountId.RESERVE) >= 0L);
            assertTrue(data.balance(SystemAccountId.ESCROW) >= 0L);
            long held = data.getBalance(player, NeroEconomyCommon.CREDITS);
            assertTrue(held >= 0L);
            assertTrue(data.locked(player) >= 0L && data.locked(player) <= held);
            if (step % ROUND_TRIP_EVERY == ROUND_TRIP_EVERY - 1) {
                var encoded = EconomySavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
                EconomySavedData restored = EconomySavedData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
                assertEquals(supply, restored.totalBalancesForTest(), "seed " + seed + ", reload at step " + step);
                assertTrue(restored.supplyBalanced());
                for (UUID each : players) {
                    assertEquals(data.getBalance(each, NeroEconomyCommon.CREDITS),
                            restored.getBalance(each, NeroEconomyCommon.CREDITS));
                    assertEquals(data.locked(each), restored.locked(each));
                    assertEquals(data.unvested(each), restored.unvested(each));
                }
                for (SystemAccountId system : SYSTEM) assertEquals(data.balance(system), restored.balance(system));
                for (BudgetKind kind : budgets) assertEquals(data.budgetUsed(kind), restored.budgetUsed(kind));
                data = restored;
            }
        }
        // The run was not a run of refusals: credits really did move in every direction.
        SupplyReport report = data.supplyReport(data.balance(SystemAccountId.ESCROW));
        assertTrue(report.mintedAllTime() > Ledgers.RESERVE);
        assertTrue(report.burnedAllTime() > 0L);
        assertTrue(report.healthy());
        assertEquals(supply, report.total());
        assertEquals(supply, report.mintedAllTime() - report.burnedAllTime());
        assertFalse(data.rowsForTest().isEmpty());
        for (BudgetKind kind : budgets)
            if (kind != BudgetKind.UNBUDGETED) assertTrue(data.budgetUsed(kind) <= EconomySavedData.dailyLimit(kind));
    }
}
