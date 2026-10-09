package za.co.neroland.neroeconomy.direct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.direct.TradeSession.CancelReason;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.menu.TerminalAnchored;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.offers.OfferStacks;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.Trading;

/**
 * Direct trade as the rest of the game reaches it: trade requests, and the trades that are open.
 * The command, the sneak-use hook each loader delivers, and (later) the trade tab of the economy
 * screens all come through here; the rules themselves are {@link TradeRequests},
 * {@link TradePresence}, {@link TradeSession} and {@link TradeSwap}.
 *
 * <p><b>What is held.</b> Waiting requests and open trades, keyed by player id, in memory only.
 * A request is gone after half a minute and a trade when its window closes; both go when a player
 * logs out ({@link #playerLeft}) and when the server stops ({@link #clear}). Nothing here is
 * logged, and the only name ever shown is the requester's, to the player they asked — and each
 * player's to the other in the title of the window they both agreed to open.
 */
public final class DirectTrades {
    /** How often escrow that belongs to no open trade is looked for; it should never be found. */
    private static final int ORPHAN_SWEEP_TICKS = 200;

    private static final TradeRequests REQUESTS = new TradeRequests();
    /** Both players of a trade map to it. */
    private static final Map<UUID, LiveTrade> LIVE = new HashMap<>();
    private static int orphanCountdown = ORPHAN_SWEEP_TICKS;

    private DirectTrades() { }

    /**
     * Milliseconds on a clock that only moves forward. A request's half minute and a trade's
     * three-second freeze are short waits within one session, not deadlines that are saved, so
     * they are measured the way the packet limiter measures: not in economy time.
     */
    public static long clock() { return System.nanoTime() / 1_000_000L; }

    /** The trade a player has open, or null. */
    public static LiveTrade tradeOf(UUID player) { return player == null ? null : LIVE.get(player); }

    public static boolean busy(UUID player) { return tradeOf(player) != null; }

    private static void tell(ServerPlayer player, String key, Object... args) {
        if (player != null && !player.hasDisconnected()) player.sendSystemMessage(Component.translatable(key, args));
    }

    // --- who may trade with whom -------------------------------------------------------------

    /** The economy terminal whose screen a player has open, or null. */
    private static BlockEntity terminalOf(ServerPlayer player) {
        if (!(player.containerMenu instanceof TerminalAnchored menu)) return null;
        BlockEntity anchor = menu.terminalAnchor();
        return anchor != null && IntentGuard.anchored(player, anchor) ? anchor : null;
    }

    private static TradePresence.Party party(ServerPlayer player) {
        boolean online = !player.hasDisconnected()
                && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player;
        return new TradePresence.Party(online, player.isAlive(), player.isSpectator(), terminalOf(player) != null);
    }

    private static TradePresence.Verdict presence(ServerPlayer first, ServerPlayer second) {
        boolean sameLevel = first.level() == second.level();
        return TradePresence.check(party(first), party(second), sameLevel,
                sameLevel ? first.distanceToSqr(second) : Double.NaN, EconomyConfig.TRADE_RANGE_BLOCKS.get());
    }

    /** Tells {@code asker} why they cannot trade with the other player right now. */
    private static void refuse(ServerPlayer asker, TradePresence.Verdict verdict, boolean askerAtFault) {
        switch (verdict) {
            case TOO_FAR -> tell(asker, "command.neroeconomy.trade.request.too_far", EconomyConfig.TRADE_RANGE_BLOCKS.get());
            case DEAD, SPECTATING -> tell(asker, askerAtFault ? "command.neroeconomy.trade.not_now"
                    : "command.neroeconomy.trade.request.not_accepting");
            default -> tell(asker, "command.neroeconomy.trade.request.not_accepting");
        }
    }

    // --- requests ----------------------------------------------------------------------------

    /**
     * {@code from} asks {@code to} for a trade.
     *
     * @param quiet true for the sneak-use gesture, which a player may repeat without meaning to:
     *              a request that is already waiting, or one sent a moment ago, is then passed
     *              over without a word
     */
    public static void request(ServerPlayer from, ServerPlayer to, boolean quiet) {
        if (from == null || to == null) return;
        if (EconomyHealth.frozen()) {
            if (!quiet) tell(from, EconomyHealth.FROZEN_KEY);
            return;
        }
        if (za.co.neroland.neroeconomy.economy.EconomyStore.get(from.level().getServer()).marketClosed()) {
            if (!quiet) tell(from, EconomyHealth.MARKET_CLOSED_KEY);
            return;
        }
        if (from.getUUID().equals(to.getUUID())) {
            if (!quiet) tell(from, "command.neroeconomy.trade.request.self");
            return;
        }
        if (busy(from.getUUID())) {
            if (!quiet) tell(from, "command.neroeconomy.trade.request.busy");
            return;
        }
        TradePresence.Verdict verdict = presence(from, to);
        if (!verdict.ok()) {
            if (!quiet || verdict != TradePresence.Verdict.TOO_FAR)
                refuse(from, verdict, !from.isAlive() || from.isSpectator());
            return;
        }
        MinecraftServer server = from.level().getServer();
        // Muted, or already trading with somebody: the requester is told the same either way.
        boolean accepting = !OfferStacks.muted(server, to.getUUID()) && !busy(to.getUUID());
        switch (REQUESTS.request(from.getUUID(), to.getUUID(), accepting, clock())) {
            case SENT -> {
                tell(from, "command.neroeconomy.trade.request.sent", TradeRequests.TIMEOUT_MILLIS / 1_000L);
                prompt(to, from);
            }
            // The other player had already asked: both want this trade, so it starts.
            case CROSSED -> start(to, from);
            case NOT_ACCEPTING -> tell(from, "command.neroeconomy.trade.request.not_accepting");
            case PAIR_PENDING -> {
                if (!quiet) tell(from, "command.neroeconomy.trade.request.pending");
            }
            case COOLDOWN -> {
                if (!quiet) tell(from, "command.neroeconomy.trade.too_fast");
            }
            case SELF -> {
                if (!quiet) tell(from, "command.neroeconomy.trade.request.self");
            }
        }
    }

    /** The chat prompt: the requester's name — they addressed this player themselves — and two things to click. */
    private static void prompt(ServerPlayer to, ServerPlayer from) {
        String name = from.getScoreboardName();
        to.sendSystemMessage(Component.translatable("command.neroeconomy.trade.request.prompt", from.getName(),
                TradeRequests.TIMEOUT_MILLIS / 1_000L));
        to.sendSystemMessage(Component.translatable("command.neroeconomy.trade.request.choices",
                clickable("command.neroeconomy.trade.request.accept", "command.neroeconomy.trade.request.accept.tooltip",
                        "/trade accept " + name, ChatFormatting.GREEN),
                clickable("command.neroeconomy.trade.request.decline", "command.neroeconomy.trade.request.decline.tooltip",
                        "/trade decline " + name, ChatFormatting.RED)));
    }

    private static Component clickable(String label, String tooltip, String command, ChatFormatting colour) {
        return Component.translatable(label).withStyle(style -> style.withColor(colour).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(tooltip))));
    }

    /**
     * {@code target} accepts a request: the one from {@code from}, or the newest one waiting for
     * them when {@code from} is null.
     */
    public static void accept(ServerPlayer target, ServerPlayer from) {
        if (target == null) return;
        if (EconomyHealth.frozen()) {
            tell(target, EconomyHealth.FROZEN_KEY);
            return;
        }
        if (za.co.neroland.neroeconomy.economy.EconomyStore.get(target.level().getServer()).marketClosed()) {
            tell(target, EconomyHealth.MARKET_CLOSED_KEY);
            return;
        }
        if (busy(target.getUUID())) {
            tell(target, "command.neroeconomy.trade.request.busy");
            return;
        }
        TradeRequests.Request request = REQUESTS.answer(target.getUUID(), from == null ? null : from.getUUID(), clock());
        if (request == null) {
            tell(target, "command.neroeconomy.trade.request.none");
            return;
        }
        ServerPlayer requester = target.level().getServer().getPlayerList().getPlayer(request.from());
        if (requester == null || busy(requester.getUUID())) {
            tell(target, "command.neroeconomy.trade.request.gone");
            return;
        }
        TradePresence.Verdict verdict = presence(requester, target);
        if (!verdict.ok()) {
            refuse(target, verdict, !target.isAlive() || target.isSpectator());
            tell(requester, "command.neroeconomy.trade.request.failed");
            return;
        }
        start(requester, target);
    }

    /** {@code target} declines a request; see {@link #accept} for {@code from}. */
    public static void decline(ServerPlayer target, ServerPlayer from) {
        if (target == null) return;
        TradeRequests.Request request = REQUESTS.answer(target.getUUID(), from == null ? null : from.getUUID(), clock());
        if (request == null) {
            tell(target, "command.neroeconomy.trade.request.none");
            return;
        }
        tell(target, "command.neroeconomy.trade.request.declined");
        // The requester is told their request was declined, not by whom: they know whom they asked.
        tell(target.level().getServer().getPlayerList().getPlayer(request.from()),
                "command.neroeconomy.trade.request.declined_requester");
    }

    /**
     * The names of the players with a request waiting for {@code target}: tab-completion for the
     * player they asked, who was shown those names already.
     */
    public static List<String> askerNames(ServerPlayer target) {
        List<String> names = new ArrayList<>();
        if (target == null) return names;
        for (UUID asker : REQUESTS.askers(target.getUUID(), clock())) {
            ServerPlayer online = target.level().getServer().getPlayerList().getPlayer(asker);
            if (online != null) names.add(online.getScoreboardName());
        }
        return names;
    }

    private static void start(ServerPlayer first, ServerPlayer second) {
        MinecraftServer server = first.level().getServer();
        // Where each of them is standing is noted before a window opens and takes the terminal's place.
        LiveTrade trade = new LiveTrade(server, first, terminalOf(first), second, terminalOf(second));
        LIVE.put(first.getUUID(), trade);
        LIVE.put(second.getUUID(), trade);
        REQUESTS.forget(first.getUUID());
        REQUESTS.forget(second.getUUID());
        trade.open();
    }

    /** A trade ended, by a swap or a cancel: it is no longer anybody's open trade. */
    static void ended(LiveTrade trade) {
        LIVE.values().removeIf(open -> open == trade);
    }

    // --- what the loaders deliver ------------------------------------------------------------

    /**
     * A player used another entity. Sneaking, with an empty main hand, on another player is a
     * trade request. The interaction itself is never consumed or cancelled here, whatever
     * happens: every other mod, and the game, sees it exactly as it was.
     */
    public static void useEntity(Player player, Entity target, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer from)
                || !(target instanceof ServerPlayer to) || from == to) return;
        if (!from.isShiftKeyDown() || !from.getMainHandItem().isEmpty()) return;
        // One click arrives as more than one packet; the budget and the request list absorb the rest.
        if (!IntentGuard.withinBudget(from, RateLimiter.Budget.QUERY)) return;
        request(from, to, true);
    }

    /** A player is leaving the game: their requests go, and a trade they had open ends. */
    public static void playerLeft(ServerPlayer player) {
        if (player == null) return;
        REQUESTS.forget(player.getUUID());
        LiveTrade trade = LIVE.get(player.getUUID());
        if (trade == null) return;
        Side side = trade.session().sideOf(Accounts.of(player.getUUID()));
        // Their goods go to their vault, not to an inventory that is about to be written out.
        trade.cancel(CancelReason.DISCONNECTED, side);
    }

    // --- lifecycle ---------------------------------------------------------------------------

    /** The server tick: requests lapse, open trades are checked, and a swap that is due is made. */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long now = clock();
        for (TradeRequests.Request lapsed : REQUESTS.expire(now))
            tell(server.getPlayerList().getPlayer(lapsed.from()), "command.neroeconomy.trade.request.expired");
        if (!LIVE.isEmpty()) for (LiveTrade trade : new LinkedHashSet<>(LIVE.values())) trade.tick(now);
        if (--orphanCountdown > 0) return;
        orphanCountdown = ORPHAN_SWEEP_TICKS;
        returnOrphans(server);
    }

    /**
     * Returns escrow that belongs to no open trade to its owner's vault. Every way a trade ends
     * empties its escrow, so there should never be any; this makes sure of it.
     */
    private static void returnOrphans(MinecraftServer server) {
        if (EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(server);
        TradeEscrowSection escrow = store.tradeEscrow();
        if (escrow.isEmpty()) return;
        for (AccountId owner : escrow.owners())
            if (!busy(Accounts.player(owner))) escrow.returnToVault(Trading.env(store), owner);
    }

    /**
     * Erasure: a trade the player has open ends first, so the other side gets its goods back;
     * whatever the erased player still has in escrow is then destroyed with the rest of their goods.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        REQUESTS.forget(player);
        LiveTrade trade = LIVE.get(player);
        if (trade != null) trade.cancel(CancelReason.NOT_ALLOWED, trade.session().sideOf(Accounts.of(player)));
        EconomyStore.get(server).tradeEscrow().forgetPlayer(player);
    }

    /**
     * Server stop. Every player has left by now and their trades ended with them; anything still
     * recorded in escrow is returned by the next load. Nothing in memory outlives the world.
     */
    public static void clear() {
        REQUESTS.clear();
        LIVE.clear();
        orphanCountdown = ORPHAN_SWEEP_TICKS;
    }
}
