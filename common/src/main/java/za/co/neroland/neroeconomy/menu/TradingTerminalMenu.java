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
    /** One refresh per second: countdowns stay live without re-walking the store every tick. */
    private static final int REFRESH_TICKS = 20;
    private final BlockEntity terminal;
    private final ServerPlayer serverPlayer;
    private final boolean board;
    private final ContainerData data;
    private int revision;
    private boolean initialPageSent;
    private int ticks;
    /** Last query the player asked for; every action re-sends this page instead of resetting it. */
    private String query = "";
    private int page;
    private OpportunityPagePayload.Tab tab = OpportunityPagePayload.Tab.CONTRACTS;
    private boolean opportunitiesActive;
    private int listingCountCache;
    private int claimCountCache;

    public TradingTerminalMenu(int id, Inventory inventory) { this(id, inventory, null); }
    @SuppressWarnings("this-escape")
    public TradingTerminalMenu(int id, Inventory inventory, BlockEntity terminal) {
        super(ModMenuTypes.TRADING_TERMINAL.get(), id);
        this.terminal = terminal;
        this.serverPlayer = inventory.player instanceof ServerPlayer value ? value : null;
        this.board = terminal instanceof MarketBoardBlockEntity;
        this.data = serverPlayer == null ? new SimpleContainerData(DATA_COUNT) : new TerminalData();
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
        if (serverPlayer != null) {
            long now = System.currentTimeMillis();
            if (!initialPageSent) {
                initialPageSent = true;
                refreshCounts(now);
                super.broadcastChanges();
                sendPage(query, page);
                return;
            }
            if (++ticks >= REFRESH_TICKS) {
                ticks = 0;
                refreshCounts(now);
                // Auctions and jobs run down against the wall clock, so the open tab is re-pushed
                // periodically and the client renders the remaining time from it.
                if (opportunitiesActive) sendOpportunity(tab);
            }
        }
        super.broadcastChanges();
    }
    /** Keeps the store mutation that {@code searchCount} performs off the per-tick gauge read path. */
    private void refreshCounts(long now) {
        if (serverPlayer == null) return;
        MarketSavedData store = MarketSavedData.get(serverPlayer.level().getServer());
        listingCountCache = store.searchCount("", now) + (board ? store.searchBuyOfferCount("", now) : 0);
        claimCountCache = store.itemClaims(serverPlayer.getUUID()).size();
    }
    public void sendPage(String query, int page) {
        if (serverPlayer == null) return;
        this.query = query == null ? "" : query;
        this.page = Math.max(0, page);
        this.opportunitiesActive = false;
        MarketSavedData store = MarketSavedData.get(serverPlayer.level().getServer());
        long now = System.currentTimeMillis();
        List<MarketPagePayload.Line> lines = store.search(this.query, this.page, PAGE_SIZE, now).stream().map(listing -> {
            long quote = PricingEngine.quote(listing, serverPlayer.getUUID()).unitPrice();
            return new MarketPagePayload.Line(listing.listingId(), listing.itemId(), listing.availableQuantity(), quote, false);
        }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (board) store.searchBuyOffers(this.query, this.page, PAGE_SIZE, now).stream().map(offer ->
                new MarketPagePayload.Line(offer.offerId(), offer.itemId(), offer.remaining(), offer.unitPrice(), true))
                .forEach(lines::add);
        NeroEconomyNetwork.sendToPlayer(serverPlayer, new MarketPagePayload(containerId, ++revision, this.page,
                store.searchCount(this.query, now) + (board ? store.searchBuyOfferCount(this.query, now) : 0), lines));
    }
    public void buy(ServerPlayer buyer, MarketBuyPayload intent) {
        var result = MarketSavedData.get(buyer.level().getServer()).purchase(buyer, intent.listingId(),
                intent.quantity(), intent.quotedUnitPrice(), intent.requestId());
        buyer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                result == za.co.neroland.neroeconomy.market.PurchaseResult.SUCCESS
                        ? "gui.neroeconomy.market.purchase_success" : "gui.neroeconomy.market.purchase_failed",
                result.name()));
        sendPage(query, page);
    }
    public void post(ServerPlayer seller, MarketPostPayload intent) {
        if (!board) return;
        long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        var listing = MarketSavedData.get(seller.level().getServer()).postFromHand(seller, intent.quantity(),
                intent.unitPrice(), expires, ListingSource.MARKET_BOARD,
                seller.level().dimension().identifier() + "|" + terminal.getBlockPos().asLong(), intent.requestId());
        seller.sendSystemMessage(net.minecraft.network.chat.Component.translatable(listing == null
                ? "gui.neroeconomy.market.post_failed" : "gui.neroeconomy.market.post_success"));
        sendPage(query, page);
    }
    public void postBuyOffer(ServerPlayer buyer, MarketBuyOfferPayload intent) {
        if (!board) return;
        long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        var offer = MarketSavedData.get(buyer.level().getServer()).postBuyOffer(buyer, intent.itemId(),
                intent.quantity(), intent.unitPrice(), expires, intent.requestId());
        buyer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(offer == null
                ? "gui.neroeconomy.market.buy_offer_failed" : "gui.neroeconomy.market.buy_offer_success"));
        sendPage(query, page);
    }
    public void fillBuyOffer(ServerPlayer seller, MarketFillBuyOfferPayload intent) {
        boolean filled = board && MarketSavedData.get(seller.level().getServer()).fillBuyOffer(seller,
                intent.offerId(), intent.quantity(), intent.requestId());
        seller.sendSystemMessage(net.minecraft.network.chat.Component.translatable(filled
                ? "gui.neroeconomy.market.fill_offer_success" : "gui.neroeconomy.market.fill_offer_failed"));
        sendPage(query, page);
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
        this.tab = tab;
        this.opportunitiesActive = true;
        ObligationSavedData store = ObligationSavedData.get(serverPlayer.level().getServer());
        List<OpportunityPagePayload.Line> lines;
        if (tab == OpportunityPagePayload.Tab.AUCTIONS) {
            // Viewer-filtered: interplanetary lots are hidden from players who have not reached orbit.
            lines = store.auctions(serverPlayer).stream().limit(20).map(value -> new OpportunityPagePayload.Line(value.id(),
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
    private final class TerminalData implements ContainerData {
        @Override public int get(int index) {
            long balance = CurrencyApi.getBalance(serverPlayer.getUUID(), NeroEconomyCommon.CREDITS);
            return switch (index) {
                case 0 -> (int) balance;
                case 1 -> (int) (balance >>> 32);
                case 2 -> listingCountCache;
                case 3 -> claimCountCache;
                case 4 -> board ? 1 : 0;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
