package za.co.neroland.neroeconomy.sibling;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.event.CoreEvents;
import za.co.neroland.nerolandcore.progression.GateEvents;
import za.co.neroland.nerolandcore.progression.GateScope;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.handbook.Handbook;

/**
 * What a quest pack's {@code mod_hook} rewards do here. A quest mod opens one of the record gates
 * below for the player who completed a quest, online or not, through Core alone (no class of it is
 * ever named here); this listens on Core's gate events, applies the effect and closes the gate again
 * to re-arm it. The gates are records that gate nothing: nothing ever checks them for access.
 *
 * <ul>
 *   <li>{@code neroeconomy:hook/fee_waiver}: the player's next listing fee is waived once
 *       ({@link PerkSection}). A flag, never a credit, so a quest can never mint currency here.</li>
 *   <li>{@code neroeconomy:hook/handbook_page}: the next Trader's Note of the Handbook opens for
 *       the player ({@link Handbook#NOTES_IN_ORDER}); a player who has them all gets nothing more.</li>
 * </ul>
 * While no server runs or the economy is frozen the gate stays open and the effect is delivered at
 * the player's next join ({@link #onJoin}). Listeners run on the server thread. Nothing about a
 * player is logged.
 */
public final class QuestHooks {
    /** The hook gates, each {@code neroeconomy:hook/<id>}. */
    public enum Hook {
        FEE_WAIVER("fee_waiver"), HANDBOOK_PAGE("handbook_page");

        private final String id;
        private final Identifier gate;

        Hook(String id) {
            this.id = id;
            this.gate = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "hook/" + id);
        }

        public Identifier gate() { return gate; }

        /** The lang key of the hook's name, which a quest book may show beside the reward. */
        public String translationKey() { return "hook.neroeconomy." + id; }

        /** The hook {@code gate} stands for, or null for any other gate. */
        public static Hook byGate(Identifier gate) {
            if (gate == null) return null;
            for (Hook hook : values()) if (hook.gate.equals(gate)) return hook;
            return null;
        }
    }

    private static boolean initialised;

    private QuestHooks() { }

    /** Common init: listens for the hook gates opening. */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        CoreEvents.onProgression(QuestHooks::onChange);
    }

    static void onChange(GateEvents.GateChange change) {
        if (change == null || !change.open() || change.scope() != GateScope.PLAYER) return;
        Hook hook = Hook.byGate(change.gate());
        if (hook == null) return;
        MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
        if (server == null || EconomyHealth.frozen()) return; // the gate stays open; see onJoin
        UUID player;
        try {
            player = UUID.fromString(change.target());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return;
        }
        deliver(server, player, hook);
    }

    /** A player joined: delivers any hook that opened while they were away or the economy was frozen. */
    public static void onJoin(ServerPlayer player) {
        if (player == null || player.level().getServer() == null || EconomyHealth.frozen()) return;
        MinecraftServer server = player.level().getServer();
        for (Hook hook : Hook.values()) {
            boolean open;
            try {
                open = ProgressionGates.isOpen(player, hook.gate());
            } catch (RuntimeException | LinkageError failure) {
                return;
            }
            if (open) deliver(server, player.getUUID(), hook);
        }
    }

    /** Applies {@code hook} for {@code player} and closes its gate; the close is a change this ignores. */
    private static void deliver(MinecraftServer server, UUID player, Hook hook) {
        try {
            switch (hook) {
                case FEE_WAIVER -> EconomyStore.get(server).perks().grantFeeWaiver(player);
                case HANDBOOK_PAGE -> openNextNote(server, player);
            }
            ProgressionGates.close(server, player, hook.gate());
        } catch (RuntimeException | LinkageError failure) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A quest hook could not be applied ({}).",
                    failure.getClass().getName());
        }
    }

    /** Opens the first Trader's Note not yet open for {@code player}. @return false when they have them all */
    static boolean openNextNote(MinecraftServer server, UUID player) {
        for (Handbook.Note note : Handbook.NOTES_IN_ORDER) {
            if (ProgressionGates.isOpen(server, player, note.gate())) continue;
            ProgressionGates.open(server, player, note.gate());
            return true;
        }
        return false;
    }
}
