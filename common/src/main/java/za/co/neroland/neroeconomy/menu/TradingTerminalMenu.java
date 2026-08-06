package za.co.neroland.neroeconomy.menu;

import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PricingEngine;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.network.MarketBuyPayload;
import za.co.neroland.neroeconomy.network.MarketPagePayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.network.MarketPostPayload;
import za.co.neroland.neroeconomy.network.MarketBuyOfferPayload;
import za.co.neroland.neroeconomy.network.MarketFillBuyOfferPayload;
import za.co.neroland.neroeconomy.block.MarketBoardBlockEntity;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.network.OpportunityIntentPayload;
import za.co.neroland.neroeconomy.network.OpportunityPagePayload;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

/** Market browser menu with real balance/listing/page data slots and payload-backed pages. */
public final class TradingTerminalMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 5;
    private static final int PAGE_SIZE = 8;
    private final BlockEntity terminal;
    private final ServerPlayer serverPlayer;
    private final ContainerData data;
    private int revision;
    private boolean initialPageSent;

    public TradingTerminalMenu(int id, Inventory inventory) { this(id, inventory, null); }
    @SuppressWarnings("this-escape")
    public TradingTerminalMenu(int id, Inventory inventory, BlockEntity terminal) {
        super(ModMenuTypes.TRADING_TERMINAL.get(), id);
        this.terminal = terminal;
        this.serverPlayer = inventory.player instanceof ServerPlayer value ? value : null;
        this.data = serverPlayer == null ? new SimpleContainerData(DATA_COUNT)
                : new TerminalData(serverPlayer, terminal instanceof MarketBoardBlockEntity);
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
    }
    public long balance() { return Integer.toUnsignedLong(data.get(0)) | (long) data.get(1) << 32; }
    public int listingCount() { return data.get(2); }
    public int claimCount() { return data.get(3); }
    public boolean isBoard() { return data.get(4) != 0; }
    @Override public boolean stillValid(Player player) {
        return terminal == null || !terminal.isRemoved() && player.distanceToSqr(terminal.getBlockPos().getX() + .5,
                terminal.getBlockPos().getY() + .5, terminal.getBlockPos().getZ() + .5) <= 64D;
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (!initialPageSent && serverPlayer != null) { initialPageSent = true; sendPage("", 0); }
    }
    public void sendPage(String query, int page) {
        if (serverPlayer == null) return;
        MarketSavedData store = MarketSavedData.get(serverPlayer.level().getServer());
        long now = System.currentTimeMillis();
        List<MarketPagePayload.Line> lines = store.search(query, page, PAGE_SIZE, now).stream().map(listing -> {
            long quote = PricingEngine.quote(listing, serverPlayer.getUUID()).unitPrice();
            return new MarketPagePayload.Line(listing.listingId(), listing.itemId(), listing.availableQuantity(), quote, false);
        }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (isBoard()) store.searchBuyOffers(query, page, PAGE_SIZE, now).stream().map(offer ->
                new MarketPagePayload.Line(offer.offerId(), offer.itemId(), offer.remaining(), offer.unitPrice(), true))
                .forEach(lines::add);
        NeroEconomyNetwork.sendToPlayer(serverPlayer, new MarketPagePayload(containerId, ++revision, page,
                store.searchCount(query, now) + (isBoard() ? store.searchBuyOfferCount(query, now) : 0), lines));
    }
    public void buy(ServerPlayer buyer, MarketBuyPayload intent) {
        var result = MarketSavedData.get(buyer.level().getServer()).purchase(buyer, intent.listingId(),
                intent.quantity(), intent.quotedUnitPrice(), intent.requestId());
        buyer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                result == za.co.neroland.neroeconomy.market.PurchaseResult.SUCCESS
                        ? "gui.neroeconomy.market.purchase_success" : "gui.neroeconomy.market.purchase_failed",
                result.name()));
        sendPage("", 0);
    }
    public void post(ServerPlayer seller, MarketPostPayload intent) {
        if (!(terminal instanceof MarketBoardBlockEntity)) return;
        long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        var listing = MarketSavedData.get(seller.level().getServer()).postFromHand(seller, intent.quantity(),
                intent.unitPrice(), expires, ListingSource.MARKET_BOARD,
                seller.level().dimension().identifier() + "|" + terminal.getBlockPos().asLong(), intent.requestId());
        seller.sendSystemMessage(net.minecraft.network.chat.Component.translatable(listing == null
                ? "gui.neroeconomy.market.post_failed" : "gui.neroeconomy.market.post_success"));
        sendPage("", 0);
    }
    public void postBuyOffer(ServerPlayer buyer, MarketBuyOfferPayload intent) {
        if (!(terminal instanceof MarketBoardBlockEntity)) return;
        long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        var offer = MarketSavedData.get(buyer.level().getServer()).postBuyOffer(buyer, intent.itemId(),
                intent.quantity(), intent.unitPrice(), expires, intent.requestId());
        buyer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(offer == null
                ? "gui.neroeconomy.market.buy_offer_failed" : "gui.neroeconomy.market.buy_offer_success"));
        sendPage("", 0);
    }
    public void fillBuyOffer(ServerPlayer seller, MarketFillBuyOfferPayload intent) {
        boolean filled = terminal instanceof MarketBoardBlockEntity
                && MarketSavedData.get(seller.level().getServer()).fillBuyOffer(seller, intent.offerId(),
                        intent.quantity(), intent.requestId());
        seller.sendSystemMessage(net.minecraft.network.chat.Component.translatable(filled
                ? "gui.neroeconomy.market.fill_offer_success" : "gui.neroeconomy.market.fill_offer_failed"));
        sendPage("", 0);
    }
    public void handleOpportunity(ServerPlayer player, OpportunityIntentPayload intent) {
        ObligationSavedData store = ObligationSavedData.get(player.level().getServer());
        boolean result = true;
        if (intent.action() != OpportunityIntentPayload.Action.QUERY) {
            UUID id;
            try { id = UUID.fromString(intent.objectId()); }
            catch (IllegalArgumentException invalid) { return; }
            result = switch (intent.action()) {
                case CLAIM -> store.claim(id, player.getUUID());
                case FULFILL -> store.fulfill(player, id);
                case BID -> store.bid(player, id, intent.amount());
                default -> true;
            };
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(result
                    ? "gui.neroeconomy.opportunity.action_success" : "gui.neroeconomy.opportunity.action_failed"));
        }
        sendOpportunity(intent.tab());
    }
    private void sendOpportunity(OpportunityPagePayload.Tab tab) {
        if (serverPlayer == null) return;
        ObligationSavedData store = ObligationSavedData.get(serverPlayer.level().getServer());
        List<OpportunityPagePayload.Line> lines;
        if (tab == OpportunityPagePayload.Tab.AUCTIONS) {
            lines = store.auctions().stream().limit(20).map(value -> new OpportunityPagePayload.Line(value.id(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(value.lot().getItem()).toString(),
                    Math.max(value.minimumBid(), value.highestBid()), value.endsAt(), value.state().name())).toList();
        } else {
            JobType type = switch (tab) {
                case CONTRACTS -> JobType.CONTRACT; case DELIVERIES -> JobType.DELIVERY;
                case BOUNTIES -> JobType.BOUNTY; default -> JobType.CONTRACT;
            };
            lines = store.jobs(type).stream().limit(20).map(value -> new OpportunityPagePayload.Line(value.id(),
                    value.target(), value.payout(), value.expiresAt(), value.state().name())).toList();
        }
        NeroEconomyNetwork.sendToPlayer(serverPlayer, new OpportunityPagePayload(containerId, ++revision, tab, lines));
    }
    private record TerminalData(ServerPlayer player, boolean board) implements ContainerData {
        @Override public int get(int index) {
            MarketSavedData market = MarketSavedData.get(player.level().getServer());
            long balance = CurrencyApi.getBalance(player.getUUID(), NeroEconomyCommon.CREDITS);
            return switch (index) {
                case 0 -> (int) balance;
                case 1 -> (int) (balance >>> 32);
                case 2 -> market.searchCount("", System.currentTimeMillis())
                        + (board ? market.searchBuyOfferCount("", System.currentTimeMillis()) : 0);
                case 3 -> market.itemClaims(player.getUUID()).size();
                case 4 -> board ? 1 : 0;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
