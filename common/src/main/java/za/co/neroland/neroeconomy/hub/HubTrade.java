package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.offers.Offer;
import za.co.neroland.neroeconomy.offers.OfferOutcome;
import za.co.neroland.neroeconomy.offers.OfferStacks;

/**
 * The Trade tab of one open hub on the server: the offers inbox and outbox, sending an offer, the
 * mute switch and asking a player standing near for a direct trade. Every call goes through
 * {@link OfferStacks} or {@link DirectTrades}, which check everything again. Neither side of an
 * offer is ever told who the other is, as with the offer commands: the recipient learns that an
 * offer is waiting, the sender that theirs was answered.
 */
final class HubTrade {
    private final ServerPlayer player;
    private HubAction.TradeView view = HubAction.TradeView.INBOX;

    HubTrade(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() { return player.level().getServer(); }

    private UUID self() { return player.getUUID(); }

    private AccountId account() { return new PlayerAccountId(self()); }

    private EconomyStore store() { return EconomyStore.get(server()); }

    private void say(String key, Object... args) { player.sendSystemMessage(Component.translatable(key, args)); }

    private static TradePages.OfferLine line(Offer offer, long now) {
        String icon = offer.lots().isEmpty() ? "" : offer.lots().get(0).item();
        return new TradePages.OfferLine(offer.id(), icon, offer.itemCount(), offer.lots().size(), offer.credits(),
                offer.askItem(), offer.askCount(), offer.askCredits(), Math.max(0L, offer.expiresAt() - now));
    }

    HubPages.Built build(int page, List<HubPages.VaultLine> vault) {
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        List<TradePages.OfferLine> incoming = new ArrayList<>();
        for (Offer offer : store().offers().receivedBy(account())) incoming.add(line(offer, now));
        List<TradePages.OfferLine> outgoing = new ArrayList<>();
        for (Offer offer : store().offers().sentBy(account())) outgoing.add(line(offer, now));
        long tax = Math.round(EconomyConfig.TRADE_TAX_PERCENT.get() * 100.0D);
        return TradePages.build(new TradePages.TradeView(view, incoming, outgoing,
                view == HubAction.TradeView.SEND ? vault : List.of(), OfferStacks.muted(server(), self()),
                EconomyConfig.MAX_OFFERS_PER_PLAYER.get(), EconomyConfig.TRADE_RANGE_BLOCKS.get(), tax,
                EconomyConfig.OFFER_DURATION_HOURS.get()), page);
    }

    long signature(long vaultRevision) {
        List<UUID> ids = new ArrayList<>();
        for (Offer offer : store().offers().receivedBy(account())) ids.add(offer.id());
        ids.add(null);
        for (Offer offer : store().offers().sentBy(account())) ids.add(offer.id());
        return Objects.hash(view, ids, OfferStacks.muted(server(), self()),
                view == HubAction.TradeView.SEND ? vaultRevision : 0L);
    }

    HubAction.TradeView view() { return view; }

    void view(HubAction.TradeView next) { view = next; }

    /** Accepting an offer: what is received and what is given are restated in the reply. */
    void accept(UUID requestId, HubPages.RowRef row) {
        OfferOutcome accepted = OfferStacks.accept(server(), self(), row.id(), requestId);
        if (!accepted.ok()) {
            say("command.neroeconomy.offer.refused", Component.translatable(accepted.result().translationKey()));
            return;
        }
        Offer offer = accepted.offer();
        say("gui.neroeconomy.hub.trade.accepted", offer.itemCount(), accepted.recipientGot(), offer.askCount(),
                accepted.recipientPaid(), accepted.tax());
        ServerPlayer sender = online(offer.sender());
        if (sender != null) sender.sendSystemMessage(Component.translatable("command.neroeconomy.offer.accepted_sender",
                accepted.senderGets()));
    }

    void decline(HubPages.RowRef row) {
        OfferOutcome declined = OfferStacks.decline(server(), self(), row.id());
        if (!declined.ok()) {
            say("command.neroeconomy.offer.refused", Component.translatable(declined.result().translationKey()));
            return;
        }
        say("command.neroeconomy.offer.declined");
        ServerPlayer sender = online(declined.offer().sender());
        if (sender != null) sender.sendSystemMessage(Component.translatable("command.neroeconomy.offer.declined_sender"));
    }

    void cancel(HubPages.RowRef row) {
        OfferOutcome cancelled = OfferStacks.cancel(server(), self(), row.id());
        if (!cancelled.ok()) {
            say("command.neroeconomy.offer.refused", Component.translatable(cancelled.result().translationKey()));
            return;
        }
        Offer offer = cancelled.offer();
        say(cancelled.refundedToClaim() ? "command.neroeconomy.offer.cancelled_claim"
                : "command.neroeconomy.offer.cancelled", offer.itemCount(), offer.credits());
    }

    /**
     * Sends an offer. The recipient is resolved here as the offer command resolves it; an unknown
     * name is refused by the offer rules before anything moves.
     */
    void send(UUID requestId, HubPages.RowRef row, OfferForm form) {
        UUID recipient = KnownAccounts.resolve(server(), form.recipient());
        List<Lot> vaultLots = row == null || form.source() != OfferForm.Source.VAULT ? List.of()
                : List.of(new Lot(row.item(), form.count(), row.payload()));
        OfferStacks.Terms terms = new OfferStacks.Terms(form.source() == OfferForm.Source.HAND ? form.count() : 0,
                vaultLots, form.credits(), form.askItem().isEmpty() ? null : form.askItem(), form.askCount(),
                form.askCredits());
        OfferOutcome sent = OfferStacks.send(server(), self(), player, recipient, terms, requestId);
        if (!sent.ok()) {
            say("command.neroeconomy.offer.refused", Component.translatable(sent.result().translationKey()));
            return;
        }
        Offer offer = sent.offer();
        say("gui.neroeconomy.hub.trade.sent", offer.itemCount(), offer.credits(), offer.askCount(), offer.askCredits(),
                EconomyConfig.OFFER_DURATION_HOURS.get());
        ServerPlayer online = recipient == null ? null : server().getPlayerList().getPlayer(recipient);
        // The recipient is told an offer is waiting, not who sent it.
        if (online != null) online.sendSystemMessage(Component.translatable("gui.neroeconomy.hub.trade.received"));
        view = HubAction.TradeView.OUTBOX;
    }

    void mute(boolean mute) {
        OfferStacks.mute(server(), self(), mute);
        say(mute ? "command.neroeconomy.offer.muted" : "command.neroeconomy.offer.unmuted");
    }

    /**
     * Asks a player for a direct trade by the name the viewer addressed. Somebody not online gets
     * the answer somebody out of range gets, so the hub cannot be used to find out who is online;
     * everything else (range, terminals, mute, cooldown) is the direct-trade rules' own.
     */
    void request(String name) {
        ServerPlayer target = server().getPlayerList().getPlayerByName(name);
        if (target == null) {
            say("command.neroeconomy.trade.request.too_far", EconomyConfig.TRADE_RANGE_BLOCKS.get());
            return;
        }
        DirectTrades.request(player, target, false);
    }

    private ServerPlayer online(AccountId account) {
        return account instanceof PlayerAccountId id ? server().getPlayerList().getPlayer(id.playerId()) : null;
    }
}
