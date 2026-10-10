package za.co.neroland.neroeconomy.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.GoodsSection;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.MarketLimits;

/**
 * The vault as the rest of the game sees it: item stacks in, item stacks out. This is the edge
 * between the pure {@link Vault} rules and live inventories; it turns stacks into lots through the
 * store's {@link GoodsSection}, reads the configured caps, and asks for the prompt save whenever
 * goods cross between the store and a player's inventory.
 *
 * <p>Every method takes the {@link EconomyStore} it works on, so a caller that already holds the
 * store (a section, mid-operation) and one that only has the server reach the same code.
 */
public final class VaultStacks {
    /** How far past its cap a vault may be before an operator is told; see {@link #hardBound()}. */
    public static final int HARD_BOUND_FACTOR = 4;
    private static final int ROW = 9;
    private static final long WARN_INTERVAL_MILLIS = 600_000L;
    private static final AtomicLong OVER_BOUND = new AtomicLong();
    private static volatile long nextWarnAt;

    private VaultStacks() { }

    // --- the caps ----------------------------------------------------------------------------

    /** {@code vaultStacks}, as whole rows of nine: how many stacks a player may put in a vault. */
    public static int cap() {
        return Math.max(ROW, EconomyConfig.VAULT_STACKS.get() / ROW * ROW);
    }

    /**
     * The cap a voluntary trade is checked against before it may deliver to a vault: a purchase, a
     * fill, a bid, a cancel. It is the vault cap, or {@code maxClaimStacksPerPlayer} when an
     * operator has set that lower.
     */
    public static int tradeCap() {
        return Math.min(cap(), EconomyConfig.MAX_CLAIM_STACKS_PER_PLAYER.get());
    }

    /** The stack count past which a system delivery is still booked but an operator is warned. */
    public static int hardBound() { return cap() * HARD_BOUND_FACTOR; }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    // --- reads -------------------------------------------------------------------------------

    /** How many stacks are waiting in the player's vault. */
    public static int stackCount(EconomyStore store, UUID player) {
        return store == null || player == null ? 0 : store.vault().stackCount(player);
    }

    /** The player's vault as item stacks, in order; stacks the game cannot name right now are left out. */
    public static List<ItemStack> stacks(EconomyStore store, UUID player) {
        List<ItemStack> out = new ArrayList<>();
        if (store == null || player == null) return out;
        GoodsSection goods = store.goods();
        for (Lot lot : store.vault().stacks(player)) {
            ItemStack stack = goods.stackOf(lot);
            if (!stack.isEmpty()) out.add(stack);
        }
        return out;
    }

    // --- goods coming in ---------------------------------------------------------------------

    /**
     * The pre-flight for a voluntary trade: whether these stacks would fit the player's vault
     * under {@link #tradeCap()}. Checked before any credit or item moves; stores nothing.
     */
    public static boolean canAccept(EconomyStore store, UUID player, List<ItemStack> stacks) {
        if (store == null || player == null || stacks == null) return false;
        GoodsSection goods = store.goods();
        List<Lot> lots = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            Lot lot = goods.peekLot(stack);
            if (lot != null) lots.add(lot);
        }
        return store.vault().canAccept(account(player), lots, tradeCap(), goods.limits());
    }

    /**
     * Books a stack into a player's vault unconditionally. This is the path refunds, expiries,
     * fills, settlements and migration use, and it ignores the cap on purpose: the cap may refuse
     * a new trade, but it must never be the reason goods are lost.
     */
    public static void deliver(EconomyStore store, UUID player, ItemStack stack) {
        if (store == null || player == null || stack == null || stack.isEmpty()) return;
        GoodsSection goods = store.goods();
        Lot lot = goods.lotOf(stack);
        if (lot == null) return;
        Vault.Delivery delivery = store.vault().deliver(account(player), List.of(lot), cap(), hardBound(),
                goods.limits());
        if (delivery.overBound()) warnOverBound();
        if (delivery.overCap()) {
            try {
                za.co.neroland.neroeconomy.link.EconomyAlerts.raise(NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull(),
                        player, za.co.neroland.neroeconomy.link.EconomyAlerts.Kind.VAULT_FULL,
                        za.co.neroland.nerolandcore.link.LinkAlert.Severity.INFO);
            } catch (RuntimeException | LinkageError failure) {
                // An alert is a courtesy; the goods are booked whatever happens to it.
            }
        }
    }

    /** One aggregate warning every ten minutes at most; it names no player and no item. */
    private static void warnOverBound() {
        OVER_BOUND.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now < nextWarnAt) return;
        nextWarnAt = now + WARN_INTERVAL_MILLIS;
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] {} system deliveries have gone to vaults holding more than "
                + "{} stacks. Nothing was lost, but those vaults need emptying at a Bank Terminal.",
                OVER_BOUND.getAndSet(0L), hardBound());
    }

    // --- goods going out, into a live inventory ----------------------------------------------

    /**
     * Moves as much of the player's vault into their inventory as actually fits, and reduces the
     * vault by exactly that. The fit is worked out per stack before anything is handed over: a
     * creative-mode inventory "accepts" a stack it has no room for by deleting it, so the
     * inventory is never offered more than it can place.
     *
     * @return how many items were moved
     */
    public static int collect(EconomyStore store, ServerPlayer player) {
        if (store == null || player == null || EconomyHealth.frozen()) return 0;
        GoodsSection goods = store.goods();
        VaultSection vault = store.vault();
        AccountId account = account(player.getUUID());
        int delivered = 0;
        // Oldest stack first. A stack taken whole leaves the list, so the ones after it move up by one.
        List<Lot> held = vault.stacks(account);
        int gone = 0;
        for (int index = 0; index < held.size(); index++) {
            Lot lot = held.get(index);
            ItemStack stack = goods.stackOf(lot);
            if (stack.isEmpty()) continue;
            int moved = give(player, stack);
            if (moved <= 0) continue;
            vault.withdrawAt(account, index - gone, moved);
            if (moved >= lot.count()) gone++;
            delivered += moved;
        }
        return delivered;
    }

    /**
     * Hands a stack to a player who is in game: what fits goes into their inventory, and whatever
     * does not is delivered to their vault. Nothing is dropped and nothing is lost.
     *
     * @return how many items went into the inventory
     */
    public static int giveOrDeliver(EconomyStore store, ServerPlayer player, ItemStack stack) {
        if (store == null || player == null || stack == null || stack.isEmpty()) return 0;
        int moved = give(player, stack.copy());
        if (moved < stack.getCount()) deliver(store, player.getUUID(), stack.copyWithCount(stack.getCount() - moved));
        return moved;
    }

    /**
     * Hands back a stack that may not stay where it was put: into the player's inventory as far as
     * it fits, and at their feet for the rest. Never loses an item.
     */
    public static void handBack(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return;
        int moved = give(player, stack);
        if (moved < stack.getCount())
            Block.popResource(player.level(), player.blockPosition(), stack.copyWithCount(stack.getCount() - moved));
    }

    /** Whether the whole stack fits the player's main inventory right now. */
    public static boolean fits(ServerPlayer player, ItemStack stack) {
        return player != null && stack != null && !stack.isEmpty() && deliverable(player, stack) >= stack.getCount();
    }

    /** Puts as much of {@code stack} into the inventory as fits. @return how many items were placed */
    private static int give(ServerPlayer player, ItemStack stack) {
        int fits = deliverable(player, stack);
        if (fits <= 0) return 0;
        ItemStack moving = stack.copyWithCount(fits);
        player.getInventory().add(moving);
        // Whatever the inventory reports as left over was genuinely not placed, in any game mode.
        return Math.max(0, fits - moving.getCount());
    }

    /** How many items of {@code stack} the player's main inventory can take right now. */
    private static int deliverable(ServerPlayer player, ItemStack stack) {
        NonNullList<ItemStack> slots = player.getInventory().getNonEquipmentItems();
        int perSlot = player.getInventory().getMaxStackSize(stack);
        // A damaged stack is never merged by the inventory, only dropped whole into an empty slot.
        boolean stackable = stack.isStackable() && !stack.isDamaged();
        int freeSlots = 0;
        long partial = 0L;
        for (ItemStack slot : slots) {
            if (slot.isEmpty()) freeSlots++;
            else if (stackable && ItemStack.isSameItemSameComponents(slot, stack))
                partial += Math.max(0, perSlot - slot.getCount());
        }
        return MarketLimits.deliverable(stack.getCount(), freeSlots, MarketLimits.clampCount(partial), perSlot,
                stackable);
    }
}
