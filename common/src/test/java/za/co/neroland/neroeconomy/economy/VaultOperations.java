package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.vault.Vault;
import za.co.neroland.neroeconomy.vault.VaultSection;
import za.co.neroland.neroeconomy.vault.VaultWindow;

/**
 * The fuzzer's goods operations, against the real vault section. Goods exist in two places: in the
 * store, and "outside" (in inventories, as far as the model cares). Every operation here either
 * moves goods between the two or says explicitly that it made or used some up; anything else that
 * changed a count would break item conservation, which is checked after every operation.
 */
final class VaultOperations {
    private VaultOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new Create(), new Consume(), new Deposit(), new Deliver(), new Withdraw(), new Take(),
                new WithdrawAt(), new Collect(), new Window());
    }

    /** Goods come into being outside the store: mined, crafted, found. */
    static final class Create implements FuzzOperation {
        @Override public String name() { return "goods_created"; }
        @Override public long run(FuzzWorld world) {
            Lot.Key kind = world.kind();
            // Kept modest, so the run exercises the rules rather than growing without end.
            if (world.outside(kind) > 300L) return 0L;
            int count = 1 + world.random.nextInt(96);
            world.create(kind, count);
            return count;
        }
    }

    /** Goods outside the store are used up. */
    static final class Consume implements FuzzOperation {
        @Override public String name() { return "goods_consumed"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            Lot.Key kind = world.kind();
            long count = Math.min(world.outside(kind), world.random.nextInt(40));
            world.consume(kind, count);
            return count;
        }
    }

    /** A player puts goods into their vault: every lot goes in, or none does. */
    static final class Deposit implements FuzzOperation {
        @Override public String name() { return "vault_deposit"; }
        @Override public int weight() { return 80; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            // One to three lots, never more of a kind than there is outside the store.
            Map<Lot.Key, Long> left = new LinkedHashMap<>(world.outside);
            List<Lot> lots = new ArrayList<>();
            for (int index = world.random.nextInt(3); index >= 0; index--) {
                Lot wanted = world.lot();
                int count = (int) Math.min(wanted.count(), left.getOrDefault(wanted.key(), 0L));
                if (count <= 0) continue;
                lots.add(wanted.withCount(count));
                left.merge(wanted.key(), (long) -count, Long::sum);
            }
            VaultSection vault = world.vault();
            boolean fits = vault.canAccept(account, lots, FuzzWorld.CAP, FuzzWorld.LIMIT);
            int before = vault.stackCount(account);
            assertEquals(fits, vault.deposit(account, lots, FuzzWorld.CAP, FuzzWorld.LIMIT));
            if (!fits) {
                assertEquals(before, vault.stackCount(account));
                return 0L;
            }
            // A deposit never takes a vault past its cap.
            assertTrue(vault.stackCount(account) <= FuzzWorld.CAP);
            long moved = 0L;
            for (Lot lot : lots) {
                world.intoStore(lot.key(), lot.count());
                moved += lot.count();
            }
            return moved;
        }
    }

    /** The system delivers goods to a vault — a refund, an expiry, a fill: always booked, whatever the cap. */
    static final class Deliver implements FuzzOperation {
        @Override public String name() { return "vault_deliver"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            Lot wanted = world.lot();
            int count = (int) Math.min(wanted.count(), world.outside(wanted.key()));
            if (count <= 0) return 0L;
            Lot lot = wanted.withCount(count);
            VaultSection vault = world.vault();
            long before = vault.total(account, lot.item(), lot.payload());
            Vault.Delivery delivery = vault.deliver(account, List.of(lot), FuzzWorld.CAP, FuzzWorld.BOUND,
                    FuzzWorld.LIMIT);
            assertEquals(before + count, vault.total(account, lot.item(), lot.payload()));
            assertEquals(vault.stackCount(account), delivery.stacks());
            assertEquals(delivery.stacks() > FuzzWorld.CAP, delivery.overCap());
            assertEquals(delivery.stacks() > FuzzWorld.BOUND, delivery.overBound());
            world.intoStore(lot.key(), count);
            return count;
        }
    }

    /** Up to so many of one kind come out of a vault. */
    static final class Withdraw implements FuzzOperation {
        @Override public String name() { return "vault_withdraw"; }
        @Override public int weight() { return 80; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            Lot lot = world.lot();
            long held = world.vault().total(account, lot.item(), lot.payload());
            long taken = world.vault().withdraw(account, lot.item(), lot.payload(), lot.count());
            assertEquals(Math.min(held, lot.count()), taken);
            world.outOfStore(lot.key(), taken);
            return taken;
        }
    }

    /** Exactly so many of one kind come out, or none: what an order sourced from the vault needs. */
    static final class Take implements FuzzOperation {
        @Override public String name() { return "vault_take"; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            Lot lot = world.lot();
            long held = world.vault().total(account, lot.item(), lot.payload());
            boolean taken = world.vault().take(account, lot);
            assertEquals(held >= lot.count(), taken);
            if (!taken) return 0L;
            world.outOfStore(lot.key(), lot.count());
            return lot.count();
        }
    }

    /** Part or all of one stack comes out, by its position. */
    static final class WithdrawAt implements FuzzOperation {
        @Override public String name() { return "vault_withdraw_at"; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            Lot taken = world.vault().withdrawAt(account, world.random.nextInt(FuzzWorld.CAP + 4),
                    1 + world.random.nextInt(70));
            if (taken == null) return 0L;
            world.outOfStore(taken.key(), taken.count());
            return taken.count();
        }
    }

    /** "Collect" at a terminal: every stack of one vault comes out, oldest first, as far as there is room. */
    static final class Collect implements FuzzOperation {
        @Override public String name() { return "vault_collect"; }
        @Override public int weight() { return 25; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            VaultSection vault = world.vault();
            // An inventory has thirty-six slots; whatever does not fit stays in the vault.
            int room = world.random.nextInt(37);
            long moved = 0L;
            for (int index = 0; room > 0 && index < vault.stackCount(account); room--) {
                Lot taken = vault.withdrawAt(account, index, Integer.MAX_VALUE);
                world.outOfStore(taken.key(), taken.count());
                moved += taken.count();
            }
            return moved;
        }
    }

    /**
     * A session at the vault screen: the window is opened, a few slots are changed the way clicks
     * change them — a stack taken out, one topped up, a new one put into a free slot — hidden
     * stacks surface into the gaps, and the window's contents are written back.
     */
    static final class Window implements FuzzOperation {
        @Override public String name() { return "vault_window"; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            VaultSection vault = world.vault();
            VaultWindow window = VaultWindow.open(vault.stacks(account), FuzzWorld.CAP, lot -> true);
            long moved = 0L;
            for (int clicks = 1 + world.random.nextInt(6); clicks > 0; clicks--) {
                int slot = world.random.nextInt(FuzzWorld.CAP);
                Lot held = window.slot(slot);
                if (held != null && world.random.nextBoolean()) {
                    // Take some or all of the stack out.
                    int count = 1 + world.random.nextInt(held.count());
                    window.set(slot, count == held.count() ? null : held.withCount(held.count() - count));
                    world.outOfStore(held.key(), count);
                    moved += count;
                } else if (held != null) {
                    // Top the stack up; allowed while the vault is not over its cap.
                    long room = Math.min(FuzzWorld.LIMIT.limitOf(held) - held.count(), world.outside(held.key()));
                    if (window.stackCount() > FuzzWorld.CAP || room <= 0L) continue;
                    int count = 1 + world.random.nextInt((int) room);
                    window.set(slot, held.withCount(held.count() + count));
                    world.intoStore(held.key(), count);
                    moved += count;
                } else {
                    // Put a new stack into the free slot; allowed while the vault is under its cap.
                    Lot wanted = world.lot();
                    long count = Math.min(Math.min(wanted.count(), FuzzWorld.LIMIT.limitOf(wanted)),
                            world.outside(wanted.key()));
                    if (window.stackCount() >= FuzzWorld.CAP || count <= 0L) continue;
                    window.set(slot, wanted.withCount((int) count));
                    world.intoStore(wanted.key(), count);
                    moved += count;
                }
                // Stacks that were off screen move into whatever is free now.
                window.surface(lot -> true);
            }
            vault.replaceAll(account, window.contents());
            return moved;
        }
    }
}
