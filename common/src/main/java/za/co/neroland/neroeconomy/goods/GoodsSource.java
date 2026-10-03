package za.co.neroland.neroeconomy.goods;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * The one guard on goods entering the economy from a live inventory: a creative-mode player, a
 * spectator, or anyone with an endless supply of items can never be the source. It is asked by
 * every operation that takes items out of an inventory into the store — posting a sell order from
 * the hand, filling a buy order from the hand, sending an offer from the hand, putting items into
 * the vault, stocking a shop, creating an auction, fulfilling a job.
 *
 * <p>Buying and receiving are never refused here: nothing a creative-mode player takes out of the
 * economy adds value to it. The operator-only gallery does not pass through this guard either; it
 * creates demo rows that hold no value.
 */
public final class GoodsSource {
    /** Lang key of the one message a refused player is shown. */
    public static final String REFUSED_KEY = "message.neroeconomy.goods_source.refused";

    private GoodsSource() { }

    /** The rule itself, free of the game so it is unit tested directly. */
    public static boolean allowed(boolean creative, boolean spectator, boolean infiniteMaterials) {
        return !creative && !spectator && !infiniteMaterials;
    }

    /** Whether goods may leave this player's inventory into the economy right now. */
    public static boolean allowed(Player player) {
        return player != null && allowed(player.isCreative(), player.isSpectator(), player.hasInfiniteMaterials());
    }

    /**
     * Tells a player who may not be a source of goods why nothing happened.
     *
     * @return true when the player was refused, so the caller must stop
     */
    public static boolean refused(ServerPlayer player) {
        if (allowed(player)) return false;
        if (player != null) player.sendSystemMessage(Component.translatable(REFUSED_KEY));
        return true;
    }
}
