package za.co.neroland.neroeconomy.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.TransactionRecord;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The swap of a direct trade against a real store: credits and goods are conserved, the trade tax
 * goes to the Reserve, the vesting lock holds, what does not fit an inventory goes to the vault,
 * and a swap that is refused has moved nothing. Every test ends with both ledger invariants holding.
 */
class TradeSwapTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private static final String IRON = TradeWorld.IRON;
    private final TradeWorld world = new TradeWorld();
    /** What each player holds outside the store: the stand-in for an inventory. */
    private final Map<UUID, Map<Lot.Key, Long>> inventories = new HashMap<>();

    @AfterEach void done() {
        assertTrue(world.invariants(), "the ledger invariants must hold at the end of every test");
        EconomyHealth.clear();
        world.close();
    }

    private TradeEscrowSection escrow() { return world.store.tradeEscrow(); }

    /** Puts goods on the table for a player, as the trade window does when a stack is placed in a slot. */
    private void give(UUID player, Lot... lots) {
        assertTrue(escrow().replace(TradeWorld.account(player), List.of(lots)));
    }

    /** An inventory with room for {@code room} more items; whatever does not fit goes to the vault. */
    private GoodsReceiver inventory(UUID player, long room) {
        long[] left = {room};
        AccountId account = TradeWorld.account(player);
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) { return true; }
            @Override public boolean give(Lot lot) {
                int fits = (int) Math.min(left[0], lot.count());
                if (fits > 0) inventories.computeIfAbsent(player, ignored -> new HashMap<>())
                        .merge(lot.key(), (long) fits, Long::sum);
                left[0] -= fits;
                if (fits < lot.count()) VaultGoods.deliver(world.store, world.env, account, lot.withCount(lot.count() - fits));
                return fits < lot.count();
            }
        };
    }

    private long inInventory(UUID player, String item) {
        return inventories.getOrDefault(player, Map.of()).getOrDefault(new Lot.Key(item, Lot.PRISTINE), 0L);
    }

    private TradeSwap.Outcome swap(UUID first, long firstCredits, List<Lot> firstGives, UUID second, long secondCredits,
            List<Lot> secondGives) {
        return TradeSwap.execute(world.store, world.env, TradeWorld.account(first), firstCredits, firstGives,
                inventory(first, 1_000_000L), TradeWorld.account(second), secondCredits, secondGives,
                inventory(second, 1_000_000L));
    }

    /** Everything about two players a swap could change, and the Reserve. */
    private String snapshot(UUID first, UUID second) {
        return world.balance(first) + "/" + world.store.vault().stacks(first) + "/" + escrow().held(TradeWorld.account(first))
                + ";" + world.balance(second) + "/" + world.store.vault().stacks(second) + "/"
                + escrow().held(TradeWorld.account(second)) + ";" + world.reserve() + ";" + world.escrow() + ";" + inventories;
    }

    /** Every credit there is in a world of two players: theirs, the Reserve's and what escrow holds. */
    private long credits(UUID first, UUID second) {
        return world.balance(first) + world.balance(second) + world.reserve() + world.escrow();
    }

    // --- the credit side ---------------------------------------------------------------------

    @Test void theTaxIsTakenOnEachCreditSideRoundedUp() {
        TradeSwap.Terms free = TradeSwap.terms(100L, 40L, 0.0D);
        assertEquals(new TradeSwap.Terms(100L, 40L, 0L, 0L), free);
        assertEquals(40L, free.receives(Side.FIRST));
        assertEquals(100L, free.receives(Side.SECOND));
        assertEquals(-60L, free.net(Side.FIRST));
        assertEquals(60L, free.net(Side.SECOND));
        TradeSwap.Terms taxed = TradeSwap.terms(100L, 33L, 3.0D);
        assertEquals(3L, taxed.tax(Side.FIRST));
        // 0.99 of a credit is a whole credit of tax.
        assertEquals(1L, taxed.tax(Side.SECOND));
        assertEquals(32L, taxed.receives(Side.FIRST));
        assertEquals(97L, taxed.receives(Side.SECOND));
        // A side that gives nothing pays no tax, and an amount below nothing is nothing.
        assertEquals(new TradeSwap.Terms(0L, 0L, 0L, 0L), TradeSwap.terms(-5L, 0L, 3.0D));
        assertEquals(1L, TradeSwap.terms(1L, 0L, 3.0D).tax(Side.FIRST));
        assertEquals(0L, TradeSwap.terms(1L, 0L, 3.0D).receives(Side.SECOND));
    }

    @Test void theCreditsMoveInOnePlanUnlessBothSidesEndUpPaying() {
        AccountId first = TradeWorld.account(world.player(1, 0L));
        AccountId second = TradeWorld.account(world.player(2, 0L));
        assertTrue(TradeSwap.plans(first, second, TradeSwap.terms(0L, 0L, 3.0D)).isEmpty());
        // Without a tax one side's loss is the other's gain: always one plan.
        Random random = new Random(3L);
        for (int round = 0; round < 2_000; round++) {
            TradeSwap.Terms terms = TradeSwap.terms(random.nextInt(500), random.nextInt(500), 0.0D);
            assertTrue(TradeSwap.plans(first, second, terms).size() <= 1);
        }
        // With a tax, one plan as long as one side comes out ahead or even.
        List<LedgerPlan> uneven = TradeSwap.plans(first, second, TradeSwap.terms(100L, 20L, 3.0D));
        assertEquals(1, uneven.size());
        assertEquals(-81L, uneven.get(0).net(first));
        assertEquals(77L, uneven.get(0).net(second));
        assertEquals(4L, uneven.get(0).net(SystemAccountId.RESERVE));
        // Both a little poorer: one plan per side, and together they still move exactly the same.
        TradeSwap.Terms even = TradeSwap.terms(100L, 100L, 3.0D);
        List<LedgerPlan> both = TradeSwap.plans(first, second, even);
        assertEquals(2, both.size());
        assertEquals(even.net(Side.FIRST), both.get(0).net(first) + both.get(1).net(first));
        assertEquals(even.net(Side.SECOND), both.get(0).net(second) + both.get(1).net(second));
        assertEquals(6L, both.get(0).net(SystemAccountId.RESERVE) + both.get(1).net(SystemAccountId.RESERVE));
        // Every plan, of every trade, moves credits and never makes or loses any.
        for (int round = 0; round < 5_000; round++) {
            TradeSwap.Terms terms = TradeSwap.terms(random.nextInt(400), random.nextInt(400),
                    new double[] {0.0D, 0.5D, 3.0D, 50.0D}[random.nextInt(4)]);
            long firstNet = 0L;
            long secondNet = 0L;
            long reserve = 0L;
            for (LedgerPlan plan : TradeSwap.plans(first, second, terms)) {
                firstNet += plan.net(first);
                secondNet += plan.net(second);
                reserve += plan.net(SystemAccountId.RESERVE);
            }
            assertEquals(terms.net(Side.FIRST), firstNet);
            assertEquals(terms.net(Side.SECOND), secondNet);
            assertEquals(terms.firstTax() + terms.secondTax(), reserve);
            assertEquals(0L, firstNet + secondNet + reserve);
        }
    }

    // --- the swap ----------------------------------------------------------------------------

    @Test void aSwapMovesGoodsAndCreditsBothWaysInOneOperation() {
        UUID alice = world.player(1, 500L);
        UUID bob = world.player(2, 300L);
        Lot sword = new Lot("minecraft:diamond_sword", 1, 4);
        give(alice, Lot.of(DIAMOND, 20), sword);
        give(bob, Lot.of(IRON, 64), Lot.of(IRON, 6));
        long reserve = world.reserve();
        int rows = world.ledger().history(alice, 100).size();

        TradeSwap.Outcome outcome = swap(alice, 120L, List.of(Lot.of(DIAMOND, 20), sword), bob, 0L,
                List.of(Lot.of(IRON, 70)));

        assertTrue(outcome.ok());
        assertEquals(380L, world.balance(alice));
        assertEquals(420L, world.balance(bob));
        assertEquals(reserve, world.reserve());
        // One row: the whole credit side was a single transfer.
        assertEquals(rows + 1, world.ledger().history(alice, 100).size());
        // Each side's goods are with the other, and nothing is left on the table.
        assertEquals(70L, inInventory(alice, IRON));
        assertEquals(20L, inInventory(bob, DIAMOND));
        assertEquals(1L, inventories.get(bob).get(sword.key()));
        assertTrue(escrow().isEmpty());
        assertEquals(List.of(Lot.of(DIAMOND, 20), sword), outcome.first());
        assertEquals(List.of(Lot.of(IRON, 64), Lot.of(IRON, 6)), outcome.second());
        assertEquals(0L, world.inStore(DIAMOND, Lot.PRISTINE));
    }

    @Test void theTradeTaxOnEachCreditSideGoesToTheReserve() {
        world.env = world.env.with(TradeWorld.settings().withFees(2.0D, 1L, 3.0D));
        UUID alice = world.player(1, 500L);
        UUID bob = world.player(2, 300L);
        long reserve = world.reserve();

        TradeSwap.Outcome outcome = swap(alice, 200L, List.of(), bob, 50L, List.of());

        assertTrue(outcome.ok());
        assertEquals(6L, outcome.terms().firstTax());
        assertEquals(2L, outcome.terms().secondTax());
        assertEquals(500L - 200L + 48L, world.balance(alice));
        assertEquals(300L - 50L + 194L, world.balance(bob));
        assertEquals(reserve + 8L, world.reserve());
        List<TransactionRecord> rows = world.ledger().history(alice, 100);
        assertEquals(1, rows.stream().filter(row -> row.type() == TransactionType.TRADE_TAX
                && row.destinationAccount().equals("system:reserve") && row.amount() == 6L).count());
        assertEquals(1, world.ledger().history(bob, 100).stream().filter(row -> row.type() == TransactionType.TRADE_TAX
                && row.amount() == 2L).count());
    }

    @Test void whenTheTaxLeavesBothSidesPayingTheSwapStillConservesEveryCredit() {
        world.env = world.env.with(TradeWorld.settings().withFees(2.0D, 1L, 3.0D));
        UUID alice = world.player(1, 150L);
        UUID bob = world.player(2, 150L);
        give(alice, Lot.of(DIAMOND, 3));
        long reserve = world.reserve();
        long supply = credits(alice, bob);

        TradeSwap.Outcome outcome = swap(alice, 100L, List.of(Lot.of(DIAMOND, 3)), bob, 100L, List.of());

        assertTrue(outcome.ok());
        assertEquals(147L, world.balance(alice));
        assertEquals(147L, world.balance(bob));
        assertEquals(reserve + 6L, world.reserve());
        assertEquals(supply, credits(alice, bob));
        assertEquals(3L, inInventory(bob, DIAMOND));
    }

    @Test void aSideThatCannotPayStopsTheSwapBeforeAnythingMoves() {
        UUID alice = world.player(1, 50L);
        UUID bob = world.player(2, 300L);
        give(alice, Lot.of(DIAMOND, 5));
        give(bob, Lot.of(IRON, 9));
        String before = snapshot(alice, bob);

        assertEquals(TradeSwap.Result.FIRST_SHORT,
                swap(alice, 51L, List.of(Lot.of(DIAMOND, 5)), bob, 0L, List.of(Lot.of(IRON, 9))).result());
        assertEquals(TradeSwap.Result.SECOND_SHORT,
                swap(alice, 0L, List.of(Lot.of(DIAMOND, 5)), bob, 301L, List.of(Lot.of(IRON, 9))).result());
        // What the other side would have paid in the same swap does not count towards it.
        assertEquals(TradeSwap.Result.FIRST_SHORT,
                swap(alice, 200L, List.of(Lot.of(DIAMOND, 5)), bob, 300L, List.of(Lot.of(IRON, 9))).result());
        assertEquals(before, snapshot(alice, bob));
        assertEquals(5L, escrow().total(TradeWorld.account(alice), DIAMOND, Lot.PRISTINE));
        assertEquals("message.neroeconomy.direct_trade.swap.short.you", TradeSwap.Result.FIRST_SHORT.translationKey(Side.FIRST));
        assertEquals("message.neroeconomy.direct_trade.swap.short.them", TradeSwap.Result.FIRST_SHORT.translationKey(Side.SECOND));
        assertEquals(Side.SECOND, TradeSwap.Result.SECOND_SHORT.side());
        assertNull(TradeSwap.Result.ESCROW_CHANGED.side());
    }

    @Test void creditsSentToAnotherPlayerCannotComeOutOfAnUnvestedStartingBalance() {
        UUID newcomer = world.newcomer(1);
        UUID bob = world.player(2, 300L);
        give(bob, Lot.of(IRON, 9));
        assertEquals(100L, world.balance(newcomer));
        String before = snapshot(newcomer, bob);

        // The newcomer has the credits, but all of them are the locked starting balance.
        assertEquals(TradeSwap.Result.FIRST_LOCKED,
                swap(newcomer, 60L, List.of(), bob, 0L, List.of(Lot.of(IRON, 9))).result());
        assertEquals(TradeSwap.Result.SECOND_LOCKED,
                swap(bob, 0L, List.of(Lot.of(IRON, 9)), newcomer, 1L, List.of()).result());
        assertEquals(before, snapshot(newcomer, bob));
        // Nor can they pass on credits the other side gives in the same swap as if they were their own.
        assertEquals(TradeSwap.Result.FIRST_LOCKED,
                swap(newcomer, 60L, List.of(), bob, 200L, List.of(Lot.of(IRON, 9))).result());
        assertEquals(before, snapshot(newcomer, bob));
        // Receiving is never locked, and what they receive they can then spend.
        assertTrue(swap(newcomer, 0L, List.of(), bob, 200L, List.of(Lot.of(IRON, 9))).ok());
        assertEquals(300L, world.balance(newcomer));
        give(bob, Lot.of(DIAMOND, 1));
        assertTrue(swap(newcomer, 150L, List.of(), bob, 0L, List.of(Lot.of(DIAMOND, 1))).ok());
        assertEquals(150L, world.balance(newcomer));
        // The starting balance itself is still locked.
        assertEquals(TradeSwap.Result.FIRST_LOCKED, swap(newcomer, 51L, List.of(), bob, 0L, List.of()).result());
        // Once vested it goes like any other credit.
        world.ledger().markVested(newcomer);
        assertTrue(swap(newcomer, 150L, List.of(), bob, 0L, List.of()).ok());
        assertEquals(0L, world.balance(newcomer));
        assertEquals("message.neroeconomy.direct_trade.swap.funds_locked.you",
                TradeSwap.Result.SECOND_LOCKED.translationKey(Side.SECOND));
    }

    @Test void whatDoesNotFitTheInventoryGoesToTheVaultAndIsNeverLost() {
        UUID alice = world.player(1, 0L);
        UUID bob = world.player(2, 0L);
        give(alice, Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 30));
        give(bob, Lot.of(IRON, 10));
        // Bob's vault is already full: the delivery books past its cap all the same.
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(bob, Lot.of(TradeWorld.GOLD, 1));

        TradeSwap.Outcome outcome = TradeSwap.execute(world.store, world.env, TradeWorld.account(alice), 0L,
                List.of(Lot.of(DIAMOND, 94)), inventory(alice, 0L), TradeWorld.account(bob), 0L, List.of(Lot.of(IRON, 10)),
                inventory(bob, 70L));

        assertTrue(outcome.ok());
        // Bob had room for 70 diamonds; the other 24 are in his vault, past the cap.
        assertEquals(70L, inInventory(bob, DIAMOND));
        assertEquals(24L, world.held(bob, DIAMOND));
        assertEquals(2, world.store.vault().stackCount(bob));
        // Alice had no room at all: everything she received is in her vault.
        assertEquals(0L, inInventory(alice, IRON));
        assertEquals(10L, world.held(alice, IRON));
        assertTrue(escrow().isEmpty());
    }

    @Test void aSwapIsRefusedWhenTheEscrowIsNotWhatTheTradeSays() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 100L);
        give(alice, Lot.of(DIAMOND, 5));
        String before = snapshot(alice, bob);

        // The trade says six, or another item, or a renamed one; the escrow holds five plain diamonds.
        assertEquals(TradeSwap.Result.ESCROW_CHANGED,
                swap(alice, 10L, List.of(Lot.of(DIAMOND, 6)), bob, 0L, List.of()).result());
        assertEquals(TradeSwap.Result.ESCROW_CHANGED,
                swap(alice, 10L, List.of(Lot.of(IRON, 5)), bob, 0L, List.of()).result());
        assertEquals(TradeSwap.Result.ESCROW_CHANGED,
                swap(alice, 10L, List.of(new Lot(DIAMOND, 5, 7)), bob, 0L, List.of()).result());
        assertEquals(TradeSwap.Result.ESCROW_CHANGED, swap(alice, 10L, List.of(), bob, 0L, List.of()).result());
        assertEquals(TradeSwap.Result.ESCROW_CHANGED,
                swap(alice, 10L, List.of(Lot.of(DIAMOND, 5)), bob, 0L, List.of(Lot.of(IRON, 1))).result());
        assertEquals(before, snapshot(alice, bob));
        // The same goods in other stacks are the same goods.
        assertTrue(swap(alice, 10L, List.of(Lot.of(DIAMOND, 2), Lot.of(DIAMOND, 3)), bob, 0L, List.of()).ok());
        assertEquals(5L, inInventory(bob, DIAMOND));
    }

    @Test void aFrozenEconomyAndABadAmountMoveNothing() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 100L);
        give(alice, Lot.of(DIAMOND, 5));
        String before = snapshot(alice, bob);
        List<Lot> gives = List.of(Lot.of(DIAMOND, 5));

        assertEquals(TradeSwap.Result.LEDGER, swap(alice, -1L, gives, bob, 0L, List.of()).result());
        assertEquals(TradeSwap.Result.LEDGER, swap(alice, 0L, gives, bob, TradeSession.MAX_CREDITS + 1L, List.of()).result());
        assertEquals(TradeSwap.Result.LEDGER, swap(alice, 0L, gives, alice, 0L, gives).result());
        EconomyHealth.freeze();
        assertEquals(TradeSwap.Result.FROZEN, swap(alice, 10L, gives, bob, 0L, List.of()).result());
        EconomyHealth.clear();
        assertEquals(before, snapshot(alice, bob));
        assertEquals(EconomyHealth.FROZEN_KEY, TradeSwap.Result.FROZEN.translationKey(Side.FIRST));
        assertTrue(swap(alice, 10L, gives, bob, 0L, List.of()).ok());
    }

    @Test void aCancelHandsEverythingBackToItsOwner() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 100L);
        give(alice, Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 6));
        give(bob, Lot.of(IRON, 3));

        // Alice has room for 50: the rest overflows to her own vault.
        List<Lot> back = TradeSwap.giveBack(world.store, TradeWorld.account(alice), inventory(alice, 50L));

        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 6)), back);
        assertEquals(50L, inInventory(alice, DIAMOND));
        assertEquals(20L, world.held(alice, DIAMOND));
        // Bob's side is untouched until his own goods are handed back, and a second call finds nothing.
        assertEquals(3L, escrow().total(TradeWorld.account(bob), IRON, Lot.PRISTINE));
        assertTrue(TradeSwap.giveBack(world.store, TradeWorld.account(alice), inventory(alice, 50L)).isEmpty());
        assertEquals(50L, inInventory(alice, DIAMOND));
        assertEquals(List.of(Lot.of(IRON, 3)), TradeSwap.giveBack(world.store, TradeWorld.account(bob), inventory(bob, 99L)));
        assertTrue(escrow().isEmpty());
        assertEquals(100L, world.balance(alice));
    }

    @Test void aThousandRandomSwapsConserveEveryCreditAndEveryItem() {
        world.env = world.env.with(TradeWorld.settings().withFees(2.0D, 1L, 2.5D));
        UUID alice = world.player(1, 2_000L);
        UUID bob = world.player(2, 2_000L);
        Random random = new Random(20_261_003L);
        long made = 0L;
        long done = 0L;
        for (int round = 0; round < 1_000; round++) {
            int diamonds = random.nextInt(40);
            int iron = random.nextInt(40);
            if (diamonds > 0) give(alice, Lot.of(DIAMOND, diamonds));
            if (iron > 0) give(bob, Lot.of(IRON, iron));
            made += diamonds + iron;
            long firstCredits = random.nextInt(3) == 0 ? 0L : random.nextInt(400);
            long secondCredits = random.nextInt(3) == 0 ? 0L : random.nextInt(400);
            long credits = credits(alice, bob);
            String before = snapshot(alice, bob);
            TradeSwap.Outcome outcome = TradeSwap.execute(world.store, world.env, TradeWorld.account(alice), firstCredits,
                    diamonds > 0 ? List.of(Lot.of(DIAMOND, diamonds)) : List.of(), inventory(alice, random.nextInt(60)),
                    TradeWorld.account(bob), secondCredits, iron > 0 ? List.of(Lot.of(IRON, iron)) : List.of(),
                    inventory(bob, random.nextInt(60)));
            if (outcome.ok()) done++;
            else {
                // A refusal moved nothing; the trade is then cancelled and everything goes back.
                assertEquals(before, snapshot(alice, bob));
                TradeSwap.giveBack(world.store, TradeWorld.account(alice), inventory(alice, random.nextInt(60)));
                TradeSwap.giveBack(world.store, TradeWorld.account(bob), inventory(bob, random.nextInt(60)));
            }
            assertTrue(escrow().isEmpty());
            assertEquals(credits, credits(alice, bob));
            assertTrue(world.invariants());
        }
        assertTrue(done > 300L, "too few swaps went through: " + done);
        assertFalse(done == 1_000L, "no swap was ever refused");
        // Every item that was put on a table is in an inventory or a vault now.
        long held = 0L;
        for (UUID player : List.of(alice, bob))
            for (String item : List.of(DIAMOND, IRON)) held += inInventory(player, item) + world.held(player, item);
        assertEquals(made, held);
    }
}
