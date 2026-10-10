package za.co.neroland.neroeconomy.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * The one server-side gate in front of every player intent: each serverbound payload (see
 * {@link NeroEconomyNetwork}) and each menu button passes through it before a store is touched.
 *
 * <p>It answers three questions, in this order: is the player within their packet budget
 * ({@link RateLimiter}); is the menu they named the one the server has open for them; and may they
 * act through it at all — alive, not a spectator, the block entity behind the menu still present,
 * in the level the player is in now, and in reach. A refusal is silent: nothing is sent back and
 * nothing is logged, so a refused packet reveals nothing and cannot be turned into chat spam. The
 * one exception is a value-moving intent while the economy is frozen, which is answered with a
 * single fixed message so the player knows why nothing happened.
 */
public final class IntentGuard {
    private static final RateLimiter LIMITER = new RateLimiter(System::nanoTime);

    private IntentGuard() { }

    /**
     * The {@code stillValid} rule every NeroEconomy menu shares. A menu built on the client has no
     * block entity and is always valid there; on the server a menu without one is never valid.
     */
    public static boolean anchored(Player player, BlockEntity anchor) {
        if (player == null) return false;
        if (anchor == null) return player.level().isClientSide();
        BlockPos pos = anchor.getBlockPos();
        // Identity, not coordinates: the same position in another dimension is a different block.
        boolean sameLevel = anchor.getLevel() == player.level();
        boolean present = !anchor.isRemoved() && sameLevel && player.level().getBlockEntity(pos) == anchor;
        return IntentRules.anchored(present, sameLevel,
                player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
    }

    /** Whether {@code player} may act through {@code menu} right now. Never consumes budget. */
    public static boolean mayAct(Player player, AbstractContainerMenu menu) {
        return player != null && menu != null && IntentRules.mayAct(player.isAlive(), player.isSpectator(),
                player.containerMenu == menu, menu.stillValid(player));
    }

    /**
     * The open menu a payload is addressed to, or null when the intent must be dropped: the player
     * has a different menu open, the container id is stale, or {@link #mayAct} refuses.
     */
    public static <M extends AbstractContainerMenu> M openMenu(ServerPlayer player, Class<M> type, int containerId) {
        if (player == null || player.containerMenu == null) return null;
        AbstractContainerMenu open = player.containerMenu;
        if (!IntentRules.addressed(type.isInstance(open), open.containerId, containerId)) return null;
        M menu = type.cast(open);
        return mayAct(player, menu) ? menu : null;
    }

    /** Takes one token from the player's budget. @return false when the packet must be dropped. */
    public static boolean withinBudget(Player player, RateLimiter.Budget budget) {
        return player != null && withinBudget(player.getUUID(), budget);
    }

    /**
     * The same budget for a caller that only has the account id, such as a companion-app action for
     * a player who is not online: one player, one budget, whichever way the intent arrives.
     */
    public static boolean withinBudget(UUID player, RateLimiter.Budget budget) {
        return LIMITER.tryAcquire(player, budget);
    }

    /**
     * A menu button press: the same guard as a payload, for the vanilla button packet. A
     * value-moving button is also refused while the economy is frozen.
     */
    public static boolean button(Player player, AbstractContainerMenu menu, RateLimiter.Budget budget) {
        if (!(player instanceof ServerPlayer actor) || !withinBudget(player, budget) || !mayAct(player, menu))
            return false;
        return budget != RateLimiter.Budget.VALUE || !refusedWhileFrozen(actor);
    }

    /**
     * Whether the economy is frozen. If it is, the player is told so in one plain message; the
     * packet budget in front of this already bounds how often that can happen.
     */
    public static boolean refusedWhileFrozen(ServerPlayer player) {
        if (!EconomyHealth.frozen()) return false;
        if (player != null) player.sendSystemMessage(Component.translatable(EconomyHealth.FROZEN_KEY));
        return true;
    }

    /** Logout hook: the limiter keys on the player id in memory, so the entry goes with the player. */
    public static void forget(UUID player) { LIMITER.forget(player); }

    /** Server-stop hook: no bucket outlives the world it was filled in. */
    public static void clear() { LIMITER.clear(); }
}
