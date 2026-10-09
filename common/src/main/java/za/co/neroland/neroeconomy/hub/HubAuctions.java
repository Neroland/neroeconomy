package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.progression.CoreGates;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.obligation.AuctionRecord;
import za.co.neroland.neroeconomy.obligation.HouseAuction;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/**
 * The Auctions tab of one open hub on the server: every running auction the viewer may see, their
 * own, and starting, bidding on and cancelling auctions through {@link ObligationSavedData}, which
 * checks everything again. A Merchant House's lot carries the House's name; a player's lot names
 * nobody, and a bid never names its bidder.
 */
final class HubAuctions {
    private final ServerPlayer player;
    private HubAction.AuctionsView view = HubAction.AuctionsView.ALL;

    HubAuctions(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() { return player.level().getServer(); }

    private UUID self() { return player.getUUID(); }

    private ObligationSavedData store() { return ObligationSavedData.get(server()); }

    /** Tells the player an operator has closed the market. @return true when it is, so the call must stop */
    private boolean marketClosed() {
        if (!EconomyStore.get(server()).marketClosed()) return false;
        say(EconomyHealth.MARKET_CLOSED_KEY);
        return true;
    }

    private void say(String key, Object... args) { player.sendSystemMessage(Component.translatable(key, args)); }

    private boolean orbit() { return ProgressionGates.isOpen(player, CoreGates.REACHED_ORBIT); }

    private static long feeBasisPoints() { return Math.round(EconomyConfig.AUCTION_FEE_PERCENT.get() * 100.0D); }

    private List<AuctionRecord> listed() {
        return view == HubAction.AuctionsView.MINE ? store().auctionsBy(self())
                : view == HubAction.AuctionsView.ALL ? store().auctions(player) : List.of();
    }

    HubPages.Built build(int page) {
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        ObligationSavedData store = store();
        Map<UUID, String> houses = new HashMap<>();
        for (HouseAuction auction : store.houseAuctions(player)) houses.put(auction.id(), auction.house());
        List<AuctionsPages.AuctionLine> lines = new ArrayList<>();
        for (AuctionRecord auction : listed()) {
            String house = houses.get(auction.id());
            HouseDefinition definition = house == null ? null : HouseData.forServer(server()).get(house);
            String houseKey = house == null ? "" : definition == null ? HubJobs.SERVER_BADGE : definition.nameKey();
            lines.add(new AuctionsPages.AuctionLine(auction.id(),
                    BuiltInRegistries.ITEM.getKey(auction.lot().getItem()).toString(), auction.lot().getCount(),
                    houseKey, auction.highestBid(), store.minimumNextBid(auction.id()),
                    Math.max(0L, auction.endsAt() - now), auction.seller().equals(self()),
                    self().equals(auction.bidderId()), auction.interplanetary()));
        }
        return AuctionsPages.build(new AuctionsPages.AuctionsView(view, lines, feeBasisPoints(),
                EconomyConfig.AUCTION_MIN_INCREMENT_PERCENT.get(), store.auctionsBy(self()).size(),
                EconomyConfig.MAX_AUCTIONS_PER_PLAYER.get(), orbit()), page);
    }

    long signature() {
        List<Long> state = new ArrayList<>();
        for (AuctionRecord auction : store().auctions(player)) {
            state.add(auction.id().getLeastSignificantBits());
            state.add(auction.highestBid());
            state.add(auction.endsAt());
        }
        return Objects.hash(view, state, store().auctionsBy(self()).size());
    }

    HubAction.AuctionsView view() { return view; }

    void view(HubAction.AuctionsView next) { view = next; }

    // --- the calls ---------------------------------------------------------------------------

    void bid(HubPages.RowRef row, long amount) {
        if (marketClosed()) return;
        ObligationSavedData store = store();
        long minimum = store.minimumNextBid(row.id());
        if (minimum < 0L) {
            say("command.neroeconomy.auction.bid_failed");
            return;
        }
        if (amount < minimum) {
            say("command.neroeconomy.auction.bid_too_low", minimum);
            return;
        }
        // A bid is offered to another player, so unvested starting credits cannot fund it.
        if (NeroEconomyCommon.CURRENCY_PROVIDER.lockBlocks(self(), amount)) {
            say("command.neroeconomy.funds_locked", NeroEconomyCommon.CURRENCY_PROVIDER.spendable(self()),
                    EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get());
            return;
        }
        say(store.bid(player, row.id(), amount) ? "command.neroeconomy.auction.bid_placed"
                : "command.neroeconomy.auction.bid_failed", amount);
    }

    void cancel(HubPages.RowRef row) {
        CancelResult result = store().cancelAuction(server(), row.id(), self());
        say(result.translationKey());
    }

    /** Starts an auction of {@code quantity} from the main hand. The fee is restated in the reply. */
    void create(int quantity, long minimumBid, int minutes, boolean interplanetary) {
        if (marketClosed()) return;
        ObligationSavedData store = store();
        int cap = EconomyConfig.MAX_AUCTIONS_PER_PLAYER.get();
        if (store.auctionsBy(self()).size() >= cap) {
            say("command.neroeconomy.auction.limit_reached", cap);
            return;
        }
        long end = NeroEconomyCommon.CURRENCY_PROVIDER.now() + minutes * 60_000L;
        AuctionRecord auction = store.createAuction(player, quantity, minimumBid, end, interplanetary && orbit());
        if (auction == null) {
            say("command.neroeconomy.auction.create_failed");
            return;
        }
        say("gui.neroeconomy.hub.auctions.created", auction.lot().getCount(), auction.minimumBid(),
                za.co.neroland.neroeconomy.exchange.PriceText.percent((int) feeBasisPoints()).replace("+", ""));
        view = HubAction.AuctionsView.MINE;
    }
}
