package za.co.neroland.neroeconomy.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
import za.co.neroland.neroeconomy.block.MarketBoardBlockEntity;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BookStacks;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.market.FillOutcome;
import za.co.neroland.neroeconomy.market.MarketBuyOffer;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.MarketBuyOfferPayload;
import za.co.neroland.neroeconomy.network.MarketBuyPayload;
import za.co.neroland.neroeconomy.network.MarketFillBuyOfferPayload;
import za.co.neroland.neroeconomy.network.MarketPagePayload;
import za.co.neroland.neroeconomy.network.MarketPostPayload;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.network.OpportunityIntentPayload;
import za.co.neroland.neroeconomy.network.OpportunityPagePayload;
import za.co.neroland.neroeconomy.obligation.JobTargets;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/** Market browser menu with real balance/claim data slots and payload-backed pages. */
public final class TradingTerminalMenu extends AbstractContainerMenu implements TerminalAnchored {
    // Data slots. The balance is 64-bit and the claim count 32-bit; a slot carries 16 (see Packed64).
    private static final int BALANCE_SLOT = 0;
    private static final int CLAIMS_SLOT = BALANCE_SLOT + Packed64.SLOTS;
    private static final int BOARD_SLOT = CLAIMS_SLOT + Packed64.INT_SLOTS;
    private static final int DATA_COUNT = BOARD_SLOT + 1;
    private static final int PAGE_SIZE = 8;
    /** The claim count is refreshed once a second; it is a map lookup, not a scan. */
    private static final int REFRESH_TICKS = 20;
    /**
     * How often the open jobs/auctions tab is pushed again. The client counts every timer down from
     * the page it last received, so this only has to pick up what other players did in the meantime.
     */
    private static final int OPPORTUNITY_REFRESH_TICKS = 100;
    private final BlockEntity terminal;
    private final ServerPlayer serverPlayer;
    private final boolean board;
    private final ContainerData data;
    private int revision;
    private boolean initialPageSent;
    private int ticks;
    private int opportunityTicks;
    /** Last query the player asked for; every action re-sends this page instead of resetting it. */
    private String query = "";
    private int page;
    private OpportunityPagePayload.Tab tab = OpportunityPagePayload.Tab.CONTRACTS;
    private boolean opportunitiesActive;
    private int claimCountCache;
    /** The order book's revision when the market page was last sent; see {@link #pushIfBookChanged}. */
    private long bookRevision;

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
    public long balance() { return Packed64.read(data::get, BALANCE_SLOT); }
    public int claimCount() { return Packed64.readInt(data::get, CLAIMS_SLOT); }
    public boolean isBoard() { return data.get(BOARD_SLOT) != 0; }
    @Override public BlockEntity terminalAnchor() { return terminal; }
    @Override public boolean stillValid(Player player) { return IntentGuard.anchored(player, terminal); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public void broadcastChanges() {
        if (serverPlayer != null) {
            if (!initialPageSent) {
                initialPageSent = true;
                refreshClaimCount();
                super.broadcastChanges();
                sendPage(query, page);
                return;
            }
            if (++ticks >= REFRESH_TICKS) {
                ticks = 0;
                refreshClaimCount();
            }
            if (opportunitiesActive && ++opportunityTicks >= OPPORTUNITY_REFRESH_TICKS) sendOpportunity(tab);
            else if (!opportunitiesActive) pushIfBookChanged();
        }
        super.broadcastChanges();
    }
    /**
     * Live updates: once a tick this compares the order book's revision with the one this menu
     * last showed. Only when the book changed for an item the page's search matches is a fresh page
     * sent — at most one a tick, only while the menu is open, and nothing at all when nothing changed.
     */
    private void pushIfBookChanged() {
        BookSection book = EconomyStore.get(serverPlayer.level().getServer()).book();
        long current = book.revision();
        if (current == bookRevision) return;
        String needle = query.toLowerCase(Locale.ROOT).trim();
        boolean affected = book.changedSince(bookRevision,
                item -> needle.isEmpty() || item.toLowerCase(Locale.ROOT).contains(needle));
        bookRevision = current;
        if (affected) sendPage(query, page);
    }
    private void refreshClaimCount() {
        if (serverPlayer == null) return;
        // What is "ready" for the player in goods is what their vault holds.
        claimCountCache = VaultStacks.stackCount(EconomyStore.get(serverPlayer.level().getServer()),
                serverPlayer.getUUID());
    }
    public void sendPage(String query, int page) {
        if (serverPlayer == null) return;
        this.query = query == null ? "" : query;
        this.page = Math.max(0, page);
        this.opportunitiesActive = false;
        EconomyStore economy = EconomyStore.get(serverPlayer.level().getServer());
        MarketSavedData store = economy.market();
        // Whatever the book does from here on is compared with what this page shows.
        bookRevision = economy.book().revision();
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        // The gallery's demo rows are only ever sent to a creative-mode level-3 operator.
        boolean demo = MarketSavedData.seesDemo(serverPlayer);
        // One store call: one expiry pass, one filter of each collection, page and totals together.
        MarketSavedData.SearchPage found = store.searchPage(this.query, this.page, PAGE_SIZE, now, demo, board);
        List<MarketPagePayload.Line> lines = new ArrayList<>(found.listings().size() + found.buyOffers().size());
        for (MarketListing listing : found.listings()) {
            long quote = MarketSavedData.buyerUnitPrice(listing, serverPlayer.getUUID());
            lines.add(new MarketPagePayload.Line(listing.listingId(), listing.itemId(), listing.availableQuantity(),
                    quote, false));
        }
        for (MarketBuyOffer offer : found.buyOffers())
            lines.add(new MarketPagePayload.Line(offer.offerId(), offer.itemId(), offer.remaining(), offer.unitPrice(),
                    true));
        NeroEconomyNetwork.sendToPlayer(serverPlayer, new MarketPagePayload(containerId, ++revision, this.page,
                (int) Math.min(Integer.MAX_VALUE, (long) found.listingTotal() + found.buyOfferTotal()), lines));
        refreshClaimCount();
    }
    public void buy(ServerPlayer buyer, MarketBuyPayload intent) {
        PurchaseOutcome outcome = MarketSavedData.get(buyer.level().getServer()).purchaseOutcome(buyer,
                intent.listingId(), intent.quantity(), intent.quotedUnitPrice(), intent.requestId(), intent.itemId());
        // The buyer is told what they paid, and what a price modifier added or took off.
        TradeMessages.purchase(buyer, outcome, intent.quantity(), intent.itemId(),
                "gui.neroeconomy.market.purchase_success", "gui.neroeconomy.market.purchase_failed");
        sendPage(query, page);
    }
    public void post(ServerPlayer seller, MarketPostPayload intent) {
        if (!board) return;
        // A sell order on the book: what crosses an open buy order sells at once, the rest is
        // listed. The seller is told both, the listing fee they paid, and why a post was refused.
        BookStacks.HandSale sale = BookStacks.sellFromHand(seller, intent.quantity(), intent.unitPrice(),
                intent.requestId());
        TradeMessages.sellOrder(seller, sale.placed(), sale.offered().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(sale.offered().getItem()).toString(), intent.unitPrice());
        sendPage(query, page);
    }
    public void postBuyOffer(ServerPlayer buyer, MarketBuyOfferPayload intent) {
        if (!board) return;
        // A buy order on the book: what crosses an open sell order is bought at once, the rest
        // waits with its credits in escrow.
        Placed placed = BookStacks.buyOrder(buyer.level().getServer(), buyer.getUUID(), buyer,
                intent.itemId().toString(), intent.quantity(), intent.unitPrice(), intent.requestId());
        TradeMessages.buyOrder(buyer, placed, intent.itemId().toString(), intent.unitPrice());
        sendPage(query, page);
    }
    public void fillBuyOffer(ServerPlayer seller, MarketFillBuyOfferPayload intent) {
        if (!board) return;
        // The seller is told why a fill was refused: a modified stack, the wrong item, a full vault.
        FillOutcome filled = MarketSavedData.get(seller.level().getServer()).fillBuyOfferOutcome(seller,
                intent.offerId(), intent.quantity(), intent.requestId());
        // A sale restates what it was worth, the market fee taken, and what is ready to collect.
        seller.sendSystemMessage(Component.translatable(filled.result().translationKey(), filled.gross(),
                filled.fee(), filled.net()));
        sendPage(query, page);
    }
    public void handleOpportunity(ServerPlayer player, OpportunityIntentPayload intent) {
        if (intent.action().moves()) act(player, intent);
        sendOpportunity(intent.tab());
    }
    /**
     * Claim, fulfil, release or bid. The request id goes through the shared request ledger first, so
     * a replayed packet acts once; the replay still gets the fresh page that follows.
     */
    private static void act(ServerPlayer player, OpportunityIntentPayload intent) {
        UUID id;
        try { id = UUID.fromString(intent.objectId()); }
        catch (IllegalArgumentException invalid) { return; }
        if (!MarketSavedData.get(player.level().getServer()).rememberRequest(player.getUUID(), intent.requestId()))
            return;
        ObligationSavedData store = ObligationSavedData.get(player.level().getServer());
        String failed = "gui.neroeconomy.opportunity.action_failed";
        Component message = switch (intent.action()) {
            // Fulfilment says why it was refused: missing or modified items, the wrong place.
            case FULFILL -> Component.translatable(store.fulfill(player, id).translationKey());
            case BID -> {
                // The amount is the minimum the player was shown. It is never raised here: if the
                // auction has moved on, the bid is refused with the new minimum and nothing is
                // escrowed, so nobody bids more than the figure they pressed.
                long minimum = store.minimumNextBid(id);
                if (minimum > 0L && intent.amount() < minimum)
                    yield Component.translatable("command.neroeconomy.auction.bid_too_low", minimum);
                // A bid is offered to another player, so unvested starting credits cannot fund it.
                if (NeroEconomyCommon.CURRENCY_PROVIDER.lockBlocks(player.getUUID(), intent.amount()))
                    yield Component.translatable("command.neroeconomy.funds_locked",
                            NeroEconomyCommon.CURRENCY_PROVIDER.spendable(player.getUUID()),
                            EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get());
                yield store.bid(player, id, intent.amount())
                        ? Component.translatable("gui.neroeconomy.opportunity.bid_placed", intent.amount())
                        : Component.translatable(failed);
            }
            case RELEASE -> Component.translatable(store.release(id, player.getUUID())
                    ? "command.neroeconomy.job.released" : "command.neroeconomy.job.release_failed");
            default -> Component.translatable(store.claim(id, player.getUUID())
                    ? "gui.neroeconomy.opportunity.action_success" : failed);
        };
        player.sendSystemMessage(message);
    }
    private void sendOpportunity(OpportunityPagePayload.Tab tab) {
        if (serverPlayer == null) return;
        this.tab = tab;
        this.opportunitiesActive = true;
        this.opportunityTicks = 0;
        ObligationSavedData store = ObligationSavedData.get(serverPlayer.level().getServer());
        // Deadlines are stored in economy time; what leaves the server is the time left, so the
        // client never compares a server timestamp with its own clock.
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        List<OpportunityPagePayload.Line> lines;
        if (tab == OpportunityPagePayload.Tab.AUCTIONS) {
            // Viewer-filtered: interplanetary lots are hidden from players who have not reached orbit.
            lines = store.auctions(serverPlayer).stream().limit(20).map(value -> new OpportunityPagePayload.Line(
                    value.id(), BuiltInRegistries.ITEM.getKey(value.lot().getItem()).toString(), value.highestBid(),
                    Math.max(0L, store.minimumNextBid(value.id())), Math.max(0L, value.endsAt() - now),
                    value.state())).toList();
        } else {
            JobType type = switch (tab) {
                case CONTRACTS -> JobType.CONTRACT; case DELIVERIES -> JobType.DELIVERY;
                case BOUNTIES -> JobType.BOUNTY; default -> JobType.CONTRACT;
            };
            // A player-target bounty never leaves the server with the target's id in it: the viewer
            // gets a fixed token instead (see JobTargets), whoever they are.
            UUID viewer = serverPlayer.getUUID();
            lines = store.jobs(type).stream().limit(20).map(value -> new OpportunityPagePayload.Line(value.id(),
                    JobTargets.visibleTo(value.target(), value.issuer(), viewer), value.payout(), 0L,
                    Math.max(0L, value.expiresAt() - now), value.state())).toList();
        }
        NeroEconomyNetwork.sendToPlayer(serverPlayer, new OpportunityPagePayload(containerId, ++revision, tab, lines));
    }
    private final class TerminalData implements ContainerData {
        private final TickCache<Long> balance = new TickCache<>();
        @Override public int get(int index) {
            if (index >= BALANCE_SLOT && index < CLAIMS_SLOT) {
                // Four slots carry the balance; the ledger is asked once per tick, not once per slot.
                long value = balance.get(serverPlayer.level().getServer().getTickCount(),
                        () -> CurrencyApi.getBalance(serverPlayer.getUUID(), NeroEconomyCommon.CREDITS));
                return Packed64.get(value, index - BALANCE_SLOT);
            }
            if (index >= CLAIMS_SLOT && index < BOARD_SLOT) return Packed64.get(claimCountCache, index - CLAIMS_SLOT);
            return index == BOARD_SLOT && board ? 1 : 0;
        }
        @Override public void set(int index, int value) { }
        @Override public int getCount() { return DATA_COUNT; }
    }
}
