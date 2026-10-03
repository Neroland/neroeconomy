package za.co.neroland.neroeconomy.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.StackLimit;

/** The vault's rules: what a player may put in, what the system may always book, and what comes out. */
class VaultTest {
    private static final AccountId ALICE = new PlayerAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId BOB = new PlayerAccountId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final String DIAMOND = "minecraft:diamond";
    private static final String PEARL = "minecraft:ender_pearl";
    private static final String SWORD = "minecraft:diamond_sword";
    private static final StackLimit LIMIT = (item, payload) -> switch (item) {
        case PEARL -> 16;
        case SWORD -> 1;
        default -> 64;
    };
    private static final int CAP = 3;
    private static final int BOUND = 12;

    @Test void aDepositGoesInWholeOrNotAtAll() {
        Vault vault = new Vault();
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(DIAMOND, 100)), CAP, LIMIT));
        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 36)), vault.stacks(ALICE));
        // Two more kinds would make four stacks: refused, and nothing of it is kept.
        long revision = vault.revision(ALICE);
        assertFalse(vault.deposit(ALICE, List.of(Lot.of(PEARL, 4), Lot.of(SWORD, 1)), CAP, LIMIT));
        assertEquals(2, vault.stackCount(ALICE));
        assertEquals(0L, vault.total(ALICE, PEARL, 0));
        assertEquals(revision, vault.revision(ALICE));
        // One of them alone fits.
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(PEARL, 4)), CAP, LIMIT));
        assertEquals(3, vault.stackCount(ALICE));
    }

    @Test void aFullVaultStillTakesWhatMergesIntoItsPartStacks() {
        Vault vault = new Vault();
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 10), Lot.of(PEARL, 4)), CAP, LIMIT));
        assertEquals(CAP, vault.stackCount(ALICE));
        assertTrue(vault.canAccept(ALICE, List.of(Lot.of(DIAMOND, 54), Lot.of(PEARL, 12)), CAP, LIMIT));
        // One more than the part stacks have room for needs a fourth stack.
        assertFalse(vault.canAccept(ALICE, List.of(Lot.of(DIAMOND, 55)), CAP, LIMIT));
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(DIAMOND, 54), Lot.of(PEARL, 12)), CAP, LIMIT));
        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 64), Lot.of(PEARL, 16)), vault.stacks(ALICE));
        assertFalse(vault.canAccept(ALICE, List.of(Lot.of(PEARL, 1)), CAP, LIMIT));
        // Asking whether nothing fits is asking whether the vault is over its cap.
        assertTrue(vault.canAccept(ALICE, List.of(), CAP, LIMIT));
    }

    @Test void aModifiedStackNeverMergesWithAnUnmodifiedOne() {
        Vault vault = new Vault();
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(DIAMOND, 1), new Lot(DIAMOND, 1, 5)), CAP, LIMIT));
        assertEquals(2, vault.stackCount(ALICE));
        assertTrue(vault.deposit(ALICE, List.of(new Lot(DIAMOND, 3, 5)), CAP, LIMIT));
        assertEquals(List.of(Lot.of(DIAMOND, 1), new Lot(DIAMOND, 4, 5)), vault.stacks(ALICE));
        assertEquals(1L, vault.total(ALICE, DIAMOND, 0));
        assertEquals(4L, vault.total(ALICE, DIAMOND, 5));
        assertEquals(Map.of(new Lot.Key(DIAMOND, 0), 1L, new Lot.Key(DIAMOND, 5), 4L), vault.totals(ALICE));
    }

    @Test void aSystemDeliveryAlwaysBooksEvenPastTheCap() {
        Vault vault = new Vault();
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(SWORD, 3)), CAP, LIMIT));
        Vault.Delivery atCap = vault.deliver(ALICE, List.of(), CAP, BOUND, LIMIT);
        assertEquals(3, atCap.stacks());
        assertFalse(atCap.overCap());
        // A refund of two more swords: booked, and the vault is now over its cap.
        Vault.Delivery over = vault.deliver(ALICE, List.of(Lot.of(SWORD, 2)), CAP, BOUND, LIMIT);
        assertEquals(5, over.stacks());
        assertTrue(over.overCap());
        assertFalse(over.overBound());
        assertEquals(5L, vault.total(ALICE, SWORD, 0));
        assertTrue(vault.overCap(ALICE, CAP));
    }

    @Test void whileOverTheCapNothingAPlayerAsksForGoesIn() {
        Vault vault = new Vault();
        vault.deliver(ALICE, List.of(Lot.of(SWORD, 4), Lot.of(DIAMOND, 1)), CAP, BOUND, LIMIT);
        assertEquals(5, vault.stackCount(ALICE));
        // Not even goods that would merge into a part stack.
        assertFalse(vault.canAccept(ALICE, List.of(Lot.of(DIAMOND, 1)), CAP, LIMIT));
        assertFalse(vault.deposit(ALICE, List.of(Lot.of(DIAMOND, 1)), CAP, LIMIT));
        // Taking out always works, and once the vault is back at its cap it takes a top-up again.
        assertEquals(2L, vault.withdraw(ALICE, SWORD, 0, 2L));
        assertFalse(vault.overCap(ALICE, CAP));
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(DIAMOND, 1)), CAP, LIMIT));
        // A new stack needs it to be under the cap.
        assertFalse(vault.deposit(ALICE, List.of(Lot.of(PEARL, 1)), CAP, LIMIT));
        assertEquals(1L, vault.withdraw(ALICE, SWORD, 0, 1L));
        assertTrue(vault.deposit(ALICE, List.of(Lot.of(PEARL, 1)), CAP, LIMIT));
    }

    @Test void aDeliveryPastTheHardBoundIsBookedAndReported() {
        Vault vault = new Vault();
        Vault.Delivery delivery = vault.deliver(ALICE, List.of(Lot.of(SWORD, BOUND + 2)), CAP, BOUND, LIMIT);
        assertTrue(delivery.overCap());
        assertTrue(delivery.overBound());
        // Reported, never refused: every sword is there.
        assertEquals(BOUND + 2, delivery.stacks());
        assertEquals(BOUND + 2L, vault.total(ALICE, SWORD, 0));
        assertFalse(vault.deliver(BOB, List.of(Lot.of(SWORD, BOUND)), CAP, BOUND, LIMIT).overBound());
    }

    @Test void withdrawingTakesWhatIsThereAndSaysHowMuch() {
        Vault vault = new Vault();
        vault.deliver(ALICE, List.of(Lot.of(DIAMOND, 100), Lot.of(PEARL, 5)), CAP, BOUND, LIMIT);
        assertEquals(40L, vault.withdraw(ALICE, DIAMOND, 0, 40L));
        // The newest stack goes first, so the oldest stays whole.
        assertEquals(List.of(Lot.of(DIAMOND, 60), Lot.of(PEARL, 5)), vault.stacks(ALICE));
        assertEquals(60L, vault.withdraw(ALICE, DIAMOND, 0, 500L));
        assertEquals(0L, vault.withdraw(ALICE, DIAMOND, 0, 1L));
        assertEquals(0L, vault.withdraw(ALICE, PEARL, 0, 0L));
        assertEquals(0L, vault.withdraw(BOB, PEARL, 0, 5L));
        assertEquals(5L, vault.withdraw(ALICE, PEARL, 0, 5L));
        // An account that holds nothing has no row at all.
        assertTrue(vault.isEmpty());
        assertTrue(vault.accounts().isEmpty());
    }

    @Test void takingIsExactOrNothing() {
        Vault vault = new Vault();
        vault.deliver(ALICE, List.of(Lot.of(DIAMOND, 70), new Lot(DIAMOND, 3, 2)), CAP, BOUND, LIMIT);
        assertFalse(vault.take(ALICE, Lot.of(DIAMOND, 71)));
        assertEquals(70L, vault.total(ALICE, DIAMOND, 0));
        assertTrue(vault.take(ALICE, Lot.of(DIAMOND, 70)));
        // The modified stack is not the unmodified item, however many of it there are.
        assertFalse(vault.take(ALICE, Lot.of(DIAMOND, 1)));
        assertTrue(vault.take(ALICE, new Lot(DIAMOND, 3, 2)));
        assertFalse(vault.take(ALICE, null));
        assertTrue(vault.isEmpty());
    }

    @Test void oneStackCanBeTakenFromByPosition() {
        Vault vault = new Vault();
        vault.deliver(ALICE, List.of(Lot.of(DIAMOND, 64), Lot.of(PEARL, 5), Lot.of(SWORD, 1)), CAP, BOUND, LIMIT);
        assertEquals(Lot.of(PEARL, 2), vault.withdrawAt(ALICE, 1, 2));
        assertEquals(Lot.of(PEARL, 3), vault.stacks(ALICE).get(1));
        // Asking for more than the stack holds takes the stack and closes the gap.
        assertEquals(Lot.of(PEARL, 3), vault.withdrawAt(ALICE, 1, 99));
        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(SWORD, 1)), vault.stacks(ALICE));
        assertNull(vault.withdrawAt(ALICE, 2, 1));
        assertNull(vault.withdrawAt(ALICE, -1, 1));
        assertNull(vault.withdrawAt(ALICE, 0, 0));
        assertNull(vault.withdrawAt(BOB, 0, 1));
    }

    @Test void erasureDestroysOneVaultAndSaysWhatItDestroyed() {
        Vault vault = new Vault();
        vault.deliver(ALICE, List.of(Lot.of(DIAMOND, 70)), CAP, BOUND, LIMIT);
        vault.deliver(BOB, List.of(Lot.of(PEARL, 3)), CAP, BOUND, LIMIT);
        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 6)), vault.erase(ALICE));
        assertEquals(0, vault.stackCount(ALICE));
        assertEquals(List.of(), vault.erase(ALICE));
        // Nobody else's goods are touched.
        assertEquals(3L, vault.total(BOB, PEARL, 0));
    }

    @Test void everyChangeMovesTheRevisionAndARefusalDoesNot() {
        Vault vault = new Vault();
        long start = vault.revision(ALICE);
        vault.deliver(ALICE, List.of(Lot.of(DIAMOND, 1)), CAP, BOUND, LIMIT);
        long delivered = vault.revision(ALICE);
        assertNotEquals(start, delivered);
        // A delivery of nothing, a refused deposit and an empty withdrawal change nothing.
        vault.deliver(ALICE, List.of(), CAP, BOUND, LIMIT);
        vault.deposit(ALICE, List.of(Lot.of(SWORD, 9)), CAP, LIMIT);
        vault.withdraw(ALICE, PEARL, 0, 4L);
        assertFalse(vault.replaceAll(ALICE, vault.stacks(ALICE)));
        assertEquals(delivered, vault.revision(ALICE));
        assertTrue(vault.replaceAll(ALICE, List.of(Lot.of(PEARL, 2), Lot.of(DIAMOND, 1))));
        assertNotEquals(delivered, vault.revision(ALICE));
        // One account's change is not another's.
        assertEquals(0L, vault.revision(BOB));
    }

    @Test void aSystemAccountCanHoldGoodsToo() {
        Vault vault = new Vault();
        AccountId house = SystemAccountId.house("ironworks");
        vault.deliver(house, List.of(Lot.of(DIAMOND, 5)), CAP, BOUND, LIMIT);
        assertEquals(5L, vault.total(house, DIAMOND, 0));
        assertTrue(vault.accounts().contains(house));
    }

    @Test void aLongRandomRunConservesEveryKindOfGoods() {
        Random random = new Random(20_261_003L);
        Vault vault = new Vault();
        AccountId[] accounts = {ALICE, BOB, SystemAccountId.house("granary")};
        String[] items = {DIAMOND, PEARL, SWORD};
        // What every account must hold, counted from the outcomes alone.
        Map<AccountId, Map<Lot.Key, Long>> expected = new HashMap<>();
        for (int step = 0; step < 20_000; step++) {
            AccountId account = accounts[random.nextInt(accounts.length)];
            Lot lot = new Lot(items[random.nextInt(items.length)], 1 + random.nextInt(90), random.nextInt(3));
            Map<Lot.Key, Long> held = expected.computeIfAbsent(account, ignored -> new HashMap<>());
            switch (random.nextInt(7)) {
                case 0, 1 -> {
                    boolean fits = vault.canAccept(account, List.of(lot), CAP * 3, LIMIT);
                    assertEquals(fits, vault.deposit(account, List.of(lot), CAP * 3, LIMIT));
                    if (fits) held.merge(lot.key(), (long) lot.count(), Long::sum);
                }
                case 2 -> {
                    vault.deliver(account, List.of(lot), CAP * 3, BOUND * 3, LIMIT);
                    held.merge(lot.key(), (long) lot.count(), Long::sum);
                }
                case 3, 4 -> held.merge(lot.key(), -vault.withdraw(account, lot.item(), lot.payload(), lot.count()),
                        Long::sum);
                case 5 -> {
                    if (vault.take(account, lot)) held.merge(lot.key(), (long) -lot.count(), Long::sum);
                }
                default -> {
                    Lot taken = vault.withdrawAt(account, random.nextInt(8), 1 + random.nextInt(70));
                    if (taken != null) held.merge(taken.key(), (long) -taken.count(), Long::sum);
                }
            }
            for (AccountId each : accounts) {
                Map<Lot.Key, Long> totals = vault.totals(each);
                Map<Lot.Key, Long> wanted = new HashMap<>(expected.getOrDefault(each, Map.of()));
                wanted.values().removeIf(count -> count == 0L);
                assertEquals(wanted, totals, "step " + step);
                // A deposit never takes a vault over its cap, and no stack passes its limit.
                List<Lot> stacks = new ArrayList<>(vault.stacks(each));
                for (Lot stack : stacks) assertTrue(stack.count() <= LIMIT.limitOf(stack), "step " + step);
            }
        }
    }
}
