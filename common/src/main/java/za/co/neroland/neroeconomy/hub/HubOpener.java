package za.co.neroland.neroeconomy.hub;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.menu.EconomyHubMenu;
import za.co.neroland.neroeconomy.menu.VaultMenu;
import za.co.neroland.neroeconomy.network.IntentGuard;

/**
 * Opens the Economy Hub, and the windows it hands over to, always on the server and always at a
 * block: there is no remote hub. A window the hub opens (the vault) brings the player back to the
 * hub, on the same tab, at the same block.
 */
public final class HubOpener {
    public static final String TITLE = "container.neroeconomy.economy_hub";

    private HubOpener() { }

    /** Opens the hub for {@code player} at {@code anchor}, on {@code tab} (the block's own tab when null). */
    public static void open(ServerPlayer player, BlockEntity anchor, HubTab tab) {
        if (player == null || !(anchor instanceof HubAnchor hub)) return;
        HubTab opening = tab == null ? hub.hubTab() : tab;
        player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) ->
                new EconomyHubMenu(containerId, inventory, anchor, opening), Component.translatable(TITLE)));
    }

    /** Opens the player's own vault in place of the hub. Only a Bank Terminal has one. */
    public static boolean openVault(ServerPlayer player, BlockEntity anchor) {
        if (player == null || !(anchor instanceof BankTerminalBlockEntity)) return false;
        player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) ->
                new VaultMenu(containerId, inventory, player, anchor),
                Component.translatable("container.neroeconomy.vault")));
        return true;
    }

    /**
     * The economy block nearest to the player that they are close enough to use, or null. Used by
     * {@code /eco hub}: the command is a shortcut for using the block, so it needs one in reach.
     * Only the block entities of the few chunks in reach are looked at.
     */
    public static BlockEntity nearestAnchor(ServerPlayer player) {
        if (player == null) return null;
        BlockPos at = player.blockPosition();
        int reach = 8;
        BlockEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cx = (at.getX() - reach) >> 4; cx <= (at.getX() + reach) >> 4; cx++) {
            for (int cz = (at.getZ() - reach) >> 4; cz <= (at.getZ() + reach) >> 4; cz++) {
                if (!player.level().hasChunk(cx, cz)) continue;
                LevelChunk chunk = player.level().getChunk(cx, cz);
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof HubAnchor) || !IntentGuard.anchored(player, entity)) continue;
                    double distance = entity.getBlockPos().distToCenterSqr(player.position());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = entity;
                    }
                }
            }
        }
        return best;
    }
}
