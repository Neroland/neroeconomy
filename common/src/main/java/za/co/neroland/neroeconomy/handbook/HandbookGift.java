package za.co.neroland.neroeconomy.handbook;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.registry.ModItems;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * The Trader's Handbook given once on first join ({@code giveHandbookOnFirstJoin}). The only thing
 * kept is one flag per account in the ledger section ({@code handbook_given}): it is exported with
 * the account and erased with it. The rule itself is {@link #shouldGive}, pure and tested; the
 * loaders call {@link #joined} from their join event. Nothing is logged about a gift.
 */
public final class HandbookGift {
    /** Hard bound on the flags kept: past it nobody else is given a book (it is craftable anyway). */
    public static final int MAX_FLAGS = 1_000_000;
    public static final String GIVEN = "gui.neroeconomy.handbook.given";
    public static final String GIVEN_TO_VAULT = "gui.neroeconomy.handbook.given_vault";

    private HandbookGift() { }

    /**
     * Whether a joining player gets the book now. Never with the option off, never while the economy
     * is frozen (its store could not record the gift), never to a spectator, never twice, and never
     * once the flags are at their bound. A refusal records nothing, so a spectator who joins again in
     * another mode still gets it.
     */
    public static boolean shouldGive(boolean enabled, boolean frozen, boolean spectator, boolean alreadyGiven,
            int flagsKept) {
        return enabled && !frozen && !spectator && !alreadyGiven && flagsKept >= 0 && flagsKept < MAX_FLAGS;
    }

    /** A player joined the server: gives the book if {@link #shouldGive} says so. Server thread only. */
    public static void joined(ServerPlayer player) {
        if (player == null || player.level().getServer() == null) return;
        boolean enabled = EconomyConfig.GIVE_HANDBOOK_ON_FIRST_JOIN.get();
        boolean frozen = EconomyHealth.frozen();
        if (!enabled || frozen) return;
        EconomyStore store = EconomyStore.get(player.level().getServer());
        EconomySavedData ledger = store.ledger();
        if (!shouldGive(true, false, player.isSpectator(), ledger.handbookGiven(player.getUUID()),
                ledger.handbookGivenCount())) return;
        // The flag first: whatever happens to the book after this, it is not given again.
        if (!ledger.markHandbookGiven(player.getUUID())) return;
        int inHand = VaultStacks.giveOrDeliver(store, player, new ItemStack(ModItems.TRADERS_HANDBOOK.get()));
        player.sendSystemMessage(Component.translatable(inHand > 0 ? GIVEN : GIVEN_TO_VAULT));
    }
}
