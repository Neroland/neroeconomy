package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.CommodityApi;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.block.MerchantStallBlockEntity;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BookStacks;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.economy.SubjectLedgerRow;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;
import za.co.neroland.neroeconomy.menu.BankTransferRules;
import za.co.neroland.neroeconomy.menu.TradeMessages;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RuleStacks;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.trade.ItemCatalog;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * One player's open Economy Hub on the server: which tab and page they are on, what they searched,
 * the last quote, and the server calls every intent ends in. It is the edge between the pure hub
 * rules ({@link HubRouter}, {@link HubPages}, {@link HubQuotes}) and the economy's own entry points,
 * which check everything again. Everything it reads is the viewer's own: their balance, their
 * history, their vault, their orders and rules. Sellers are only ever told apart as "a player",
 * "a shop" or a Merchant House, and a counterparty is never named.
 */
public final class HubSession implements HubRouter.HubServices {
    private static final long TICKS_PER_MINUTE = 1_200L;

    private final ServerPlayer player;
    private final BlockEntity anchor;
    private final Map<HubTab, Integer> pages = new EnumMap<>(HubTab.class);
    private HubTab tab;
    private String query = "";
    private HubAction.MarketMode mode = HubAction.MarketMode.BROWSE;
    private HubQuotes.Quote quote;
    private HubAction quoteFor;
    private Map<UUID, HubPages.RowRef> rows = Map.of();
    /** Whether the Wallet shows the "My data" summary instead of the wallet. */
    private boolean myData;
    private final HubInvest invest;
    private final HubTrade trade;
    private final HubJobs jobs;
    private final HubAuctions auctions;
    private final HubShops shops;
    private final HubAdmin adminTab;

    public HubSession(ServerPlayer player, BlockEntity anchor, HubTab tab) {
        this.player = player;
        this.anchor = anchor;
        this.tab = tab == null || !tab.visible(admin()) ? HubTab.WALLET : tab;
        this.invest = new HubInvest(player);
        this.trade = new HubTrade(player);
        this.jobs = new HubJobs(player);
        this.auctions = new HubAuctions(player);
        this.shops = new HubShops(player);
        this.adminTab = new HubAdmin(player);
    }

    public HubTab tab() { return tab; }

    /** What the rows of the page last built stand for. */
    public Map<UUID, HubPages.RowRef> rows() { return rows; }

    public boolean admin() { return Commands.LEVEL_ADMINS.check(player.permissions()); }

    private MinecraftServer server() { return player.level().getServer(); }

    private EconomyStore store() { return EconomyStore.get(server()); }

    private UUID self() { return player.getUUID(); }

    private AccountId account() { return new PlayerAccountId(self()); }

    private void say(String key, Object... args) { player.sendSystemMessage(Component.translatable(key, args)); }

    private void clearQuote() {
        quote = null;
        quoteFor = null;
    }

    // --- building the page -------------------------------------------------------------------

    /** Builds the page of the current tab, with the header every tab carries, and keeps its rows. */
    public HubPage build() {
        // A player who lost level 3 with the Admin tab open is moved off it.
        if (!tab.visible(admin())) tab = HubTab.WALLET;
        int page = pages.getOrDefault(tab, 0);
        HubPages.Built built = switch (tab) {
            case WALLET -> myData ? DataPages.build(dataLines()) : HubPages.wallet(walletView(), page);
            case VAULT -> HubPages.vault(vaultView(), page);
            case MARKET -> marketPage(page);
            case ORDERS -> HubPages.orders(ordersView(), page);
            case INVEST -> invest.build(page);
            case TRADE -> trade.build(page, vaultLines());
            case JOBS -> jobs.build(page);
            case AUCTIONS -> auctions.build(page);
            case SHOP -> shops.build(page);
            case ADMIN -> adminTab.build(page);
        };
        rows = built.refs();
        pages.put(tab, built.page().page());
        java.util.LinkedHashMap<String, Long> numbers = new java.util.LinkedHashMap<>(built.page().numbers());
        java.util.LinkedHashMap<String, String> keys = new java.util.LinkedHashMap<>(built.page().keys());
        numbers.put(HubPages.ADMIN, admin() ? 1L : 0L);
        numbers.put(HubPages.AT_BANK, anchor instanceof za.co.neroland.neroeconomy.block.BankTerminalBlockEntity ? 1L : 0L);
        numbers.put(HubPages.STALL, anchor instanceof MerchantStallBlockEntity ? 1L : 0L);
        String house = houseFilter();
        if (house != null) {
            HouseDefinition definition = HouseData.forServer(server()).get(house);
            if (definition != null) keys.put(HubPages.HOUSE, definition.nameKey());
        }
        HubPage source = built.page();
        return new HubPage(0, 0, source.tab(), source.page(), source.pageCount(), source.rows(), numbers, keys,
                source.series());
    }

    /**
     * What the current tab's page depends on, as one number: when it is unchanged the page would be
     * the same, so nothing is sent. Cheap reads only: revisions, a balance, a claim.
     */
    public long signature() {
        EconomyStore store = store();
        return switch (tab) {
            case WALLET -> {
                EconomySavedData ledger = NeroEconomyCommon.CURRENCY_PROVIDER.activeData();
                List<SubjectLedgerRow> newest = ledger.subjectHistory(self(), 1);
                yield Objects.hash(CurrencyApi.getBalance(self(), NeroEconomyCommon.CREDITS), ledger.locked(self()),
                        store.market().creditClaimTotal(self()), ledger.loggingOptOut(self()),
                        newest.isEmpty() ? 0L : newest.get(0).timestamp(), newest.size(), myData,
                        myData ? store.vault().revision(account()) : 0L, myData ? store.book().revision() : 0L);
            }
            case INVEST -> invest.signature();
            case TRADE -> trade.signature(store.vault().revision(account()));
            case JOBS -> jobs.signature();
            case AUCTIONS -> auctions.signature();
            case SHOP -> shops.signature();
            case ADMIN -> Objects.hash(adminTab.signature(), pages.getOrDefault(HubTab.ADMIN, 0));
            case VAULT -> Objects.hash(store.vault().revision(account()), store.vault().stackCount(self()));
            case MARKET -> Objects.hash(store.book().revision(),
                    mode == HubAction.MarketMode.VAULT ? store.vault().revision(account()) : 0L);
            case ORDERS -> {
                int rules = 0;
                for (TradeRule rule : store.rules().rulesOf(account()))
                    rules = rules * 31 + Objects.hash(rule.status(), rule.filledToday(), rule.done(), rule.paused(),
                            rule.budget());
                yield Objects.hash(store.book().revision(), rules, store.market().listingsOf(self()).size(),
                        store.rules().pausedAll(account()));
            }
            default -> 0L;
        };
    }

    /** How often, in ticks, the signature of the current tab is read when nothing asked for a push. */
    public int signatureInterval() {
        return switch (tab) {
            case MARKET -> 1;
            case VAULT -> 10;
            case WALLET, ORDERS, TRADE, JOBS, AUCTIONS -> 20;
            case INVEST, SHOP, ADMIN -> 40;
            default -> Integer.MAX_VALUE;
        };
    }

    private HubPages.WalletView walletView() {
        EconomySavedData ledger = NeroEconomyCommon.CURRENCY_PROVIDER.activeData();
        // Asking what can be spent first lets an account that has played long enough vest now.
        NeroEconomyCommon.CURRENCY_PROVIDER.spendable(self());
        long locked = ledger.locked(self());
        long unlock = 0L;
        if (locked > 0L) {
            long played = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / TICKS_PER_MINUTE;
            unlock = Math.max(1L, EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get() - played);
        }
        List<HubPages.HistoryLine> history = new ArrayList<>();
        for (SubjectLedgerRow row : ledger.subjectHistory(self(), EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get()))
            history.add(new HubPages.HistoryLine("command.neroeconomy.transaction_type."
                    + row.type().name().toLowerCase(Locale.ROOT), row.amount(), row.incoming(),
                    NeroEconomyCommon.CURRENCY_PROVIDER.toWallMillis(row.timestamp())));
        return new HubPages.WalletView(CurrencyApi.getBalance(self(), NeroEconomyCommon.CREDITS), locked, unlock,
                store().market().creditClaimTotal(self()), ledger.loggingOptOut(self()), history);
    }

    private List<HubPages.VaultLine> vaultLines() {
        List<HubPages.VaultLine> lines = new ArrayList<>();
        for (Lot lot : store().vault().stacks(self()))
            lines.add(new HubPages.VaultLine(lot.item(), lot.count(), lot.payload()));
        return lines;
    }

    private HubPages.VaultView vaultView() {
        return new HubPages.VaultView(store().vault().stackCount(self()), VaultStacks.cap(), vaultLines());
    }

    /** The Merchant House this hub's market shows only, at a Merchant Stall; null elsewhere. */
    private String houseFilter() {
        return anchor instanceof HubAnchor hub ? hub.houseFilter() : null;
    }

    private HubPages.Built marketPage(int page) {
        if (mode == HubAction.MarketMode.VAULT)
            return HubPages.market(new HubPages.MarketView(mode, List.of(), 0L, vaultLines(), quote, quoteFor), page);
        EconomyStore store = store();
        String house = houseFilter();
        Predicate<AccountId> sellers = house == null ? null : SystemAccountId.house(house)::equals;
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        MarketSavedData.OfferedPage found = store.market().searchOffered(query, page, HubPages.MARKET_ROWS, now,
                MarketSavedData.seesDemo(player), sellers);
        // The search may have shrunk under the page asked for: ask again for the last page there is.
        int pagesThere = HubPage.pages(found.total(), HubPages.MARKET_ROWS);
        if (page >= pagesThere && page > 0) {
            page = pagesThere - 1;
            found = store.market().searchOffered(query, page, HubPages.MARKET_ROWS, now,
                    MarketSavedData.seesDemo(player), sellers);
        }
        List<HubPages.MarketLine> lines = new ArrayList<>();
        BookSection book = store.book();
        for (MarketSavedData.OfferedRow row : found.rows()) {
            MarketListing listing = row.listing();
            String item = listing.itemId().toString();
            HubPages.SellerKind kind = HubPages.SellerKind.PLAYER;
            String houseKey = "";
            if (row.shop()) kind = HubPages.SellerKind.SHOP;
            else if (row.seller() instanceof SystemAccountId system && system.isHouse()) {
                kind = HubPages.SellerKind.HOUSE;
                HouseDefinition definition = HouseData.forServer(server()).get(system.value().substring(6));
                houseKey = definition == null ? "" : definition.nameKey();
            }
            List<SellOrder> asks = book.asks(item);
            List<BuyOrder> bids = book.bids(item);
            var left = CommodityApi.remainingToday(server(), self(), item);
            lines.add(new HubPages.MarketLine(listing.listingId(), item, kind, houseKey,
                    MarketSavedData.buyerUnitPrice(listing, self()), listing.availableQuantity(),
                    bids.isEmpty() ? 0L : bids.get(0).unitPrice(), asks.isEmpty() ? 0L : asks.get(0).unitPrice(),
                    left.map(CommodityApi.DailyRemaining::buy).orElse(-1L),
                    left.map(CommodityApi.DailyRemaining::sell).orElse(-1L),
                    listing.seller().equals(self()), !MarketSavedData.pristine(listing.stock())));
        }
        return HubPages.market(new HubPages.MarketView(mode, lines, found.total(), List.of(), quote, quoteFor), page);
    }

    private HubPages.OrdersView ordersView() {
        EconomyStore store = store();
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        List<HubPages.OrderLine> lines = new ArrayList<>();
        for (SellOrder order : store.book().sellsOf(account()))
            lines.add(new HubPages.OrderLine(HubPages.OrderKind.SELL, order.id(), order.item(), order.item(), false,
                    false, false, "", order.lot().count(), order.unitPrice(), Math.max(0L, order.expiresAt() - now),
                    0L, 0L, 0L));
        for (BuyOrder order : store.book().buysOf(account()))
            lines.add(new HubPages.OrderLine(HubPages.OrderKind.BUY, order.id(), order.item(), order.item(), true,
                    false, false, "", order.remaining(), order.unitPrice(), order.escrow(),
                    Math.max(0L, order.expiresAt() - now), 0L, 0L));
        for (MarketListing listing : store.market().listingsOf(self()))
            if (listing.source() == ListingSource.PLAYER_SHOP)
                lines.add(new HubPages.OrderLine(HubPages.OrderKind.SHOP, listing.listingId(),
                        listing.itemId().toString(), listing.itemId().toString(), false, false, false, "",
                        listing.availableQuantity(), listing.baseUnitPrice(), 0L, 0L, 0L, 0L));
        for (TradeRule rule : store.rules().rulesOf(account()))
            lines.add(new HubPages.OrderLine(HubPages.OrderKind.RULE, rule.id(), ruleIcon(rule), rule.target(),
                    rule.buys(), rule.tag(), rule.paused(), rule.status().translationKey(), rule.limitPrice(),
                    rule.filledToday(), rule.perDay(), rule.done(), rule.total(), rule.budget()));
        return new HubPages.OrdersView(lines, store.rules().count(account()), EconomyConfig.MAX_RULES_PER_PLAYER.get(),
                store.rules().valueToday(account(), now), EconomyConfig.RULE_DAILY_VALUE_CAP.get(),
                store.rules().pausedAll(account()));
    }

    /** The icon of a rule: its item, or the first item of its tag. */
    private static String ruleIcon(TradeRule rule) {
        if (!rule.tag()) return rule.target();
        Identifier id = Identifier.tryParse(rule.target());
        if (id == null) return "minecraft:name_tag";
        return BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id))
                .flatMap(named -> named.stream().findFirst())
                .flatMap(holder -> holder.unwrapKey())
                .map(key -> key.identifier().toString()).orElse("minecraft:name_tag");
    }

    // --- the server calls --------------------------------------------------------------------

    @Override public boolean remember(UUID requestId) {
        return store().market().rememberRequest(self(), requestId);
    }

    @Override public void openTab(HubTab next) {
        tab = next;
        clearQuote();
        invest.clearQuote();
        myData = false;
    }

    @Override public void page(int page) { pages.put(tab, Math.max(0, page)); }

    @Override public void collectCredits() {
        long credits = store().market().collectCreditsTotal(self());
        say(credits > 0L ? "gui.neroeconomy.hub.wallet.collected" : "gui.neroeconomy.hub.wallet.nothing_to_collect",
                credits);
    }

    /**
     * Pays {@code amount} to {@code recipient}, resolved as {@code /eco pay} resolves it. Every refusal
     * comes before the ledger is called, so an unknown recipient or a short balance leaves no account
     * and no audit row; the request id is spent only once the payment is otherwise good.
     */
    @Override public void pay(UUID requestId, String recipient, long amount) {
        MinecraftServer server = server();
        UUID target = KnownAccounts.resolve(server, recipient);
        long spendable = NeroEconomyCommon.CURRENCY_PROVIDER.spendable(self());
        BankTransferRules.Check check = BankTransferRules.check(amount, target != null, self().equals(target),
                CurrencyApi.getBalance(self(), NeroEconomyCommon.CREDITS), spendable);
        if (check != BankTransferRules.Check.OK) {
            if (check == BankTransferRules.Check.FUNDS_LOCKED)
                say(check.translationKey(), spendable, EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get());
            else say(check.translationKey(), amount);
            return;
        }
        if (!remember(requestId)) return;
        boolean paid = CurrencyApi.transfer(self(), target, NeroEconomyCommon.CREDITS, amount);
        say(paid ? "gui.neroeconomy.bank.payment_sent" : "gui.neroeconomy.bank.payment_failed", amount);
        ServerPlayer online = paid ? server.getPlayerList().getPlayer(target) : null;
        if (online != null) online.sendSystemMessage(Component.translatable("command.neroeconomy.payment_received",
                amount));
    }

    @Override public void setLogging(boolean optOut) {
        NeroEconomyCommon.CURRENCY_PROVIDER.activeData().setLoggingOptOut(self(), optOut);
        say(optOut ? "command.neroeconomy.logging.opted_out" : "command.neroeconomy.logging.opted_in");
    }

    @Override public void openVault() {
        if (!HubOpener.openVault(player, anchor)) say("gui.neroeconomy.hub.vault.bank_only");
    }

    @Override public void collectVault() {
        int items = store().market().collectItems(player);
        say(items > 0 ? "gui.neroeconomy.hub.vault.collected" : "gui.neroeconomy.hub.vault.nothing_fits", items);
    }

    @Override public void search(String text) {
        query = text == null ? "" : text;
        pages.put(HubTab.MARKET, 0);
        clearQuote();
    }

    @Override public void marketMode(HubAction.MarketMode next) {
        mode = next;
        pages.put(HubTab.MARKET, 0);
        clearQuote();
    }

    /** Only someone who could take the stall down and put it back up may change what it shows. */
    @Override public void cycleHouse() {
        if (!(anchor instanceof MerchantStallBlockEntity stall) || !(player.level() instanceof ServerLevel level)
                || !player.mayBuild() || !player.mayInteract(level, stall.getBlockPos())) {
            say("gui.neroeconomy.hub.market.house_locked");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (HouseDefinition house : HouseData.forServer(server()).houses()) ids.add(house.id());
        stall.cycleHouse(ids);
        pages.put(HubTab.MARKET, 0);
        clearQuote();
    }

    @Override public void quoteBuy(HubPages.RowRef row, long quantity) {
        MarketSavedData market = store().market();
        MarketListing listing = market.listing(row.id());
        quoteFor = HubAction.MARKET_QUOTE;
        if (listing == null || listing.demo()) {
            quote = HubQuotes.Quote.refused(HubQuotes.OUT_OF_STOCK);
            return;
        }
        int count = (int) Math.min(Integer.MAX_VALUE, quantity);
        ItemStack goods = listing.stock().copyWithCount(Math.max(1, count));
        boolean fits = VaultStacks.fits(player, goods);
        boolean vaultRoom = VaultStacks.canAccept(store(), self(), List.of(goods));
        quote = HubQuotes.buy(MarketSavedData.buyerUnitPrice(listing, self()), listing.baseUnitPrice(),
                listing.availableQuantity(), listing.seller().equals(self()), quantity,
                EconomyConfig.MARKET_FEE_PERCENT.get(), fits, vaultRoom);
    }

    @Override public void buy(UUID requestId, HubPages.RowRef row, long quantity, long ceiling) {
        clearQuote();
        Identifier item = Identifier.tryParse(row.item());
        PurchaseOutcome outcome = store().market().purchaseOutcome(player, row.id(), (int) quantity, ceiling,
                requestId, item);
        TradeMessages.purchase(player, outcome, (int) quantity, item, "gui.neroeconomy.market.purchase_success",
                "gui.neroeconomy.market.purchase_failed");
    }

    @Override public void quoteSell(HubPages.RowRef row, long quantity, long unit) {
        quoteFor = HubAction.MARKET_SELL_QUOTE;
        long available;
        boolean tradeable;
        if (row == null) {
            ItemStack held = player.getMainHandItem();
            available = held.isEmpty() ? 0L : held.getCount();
            tradeable = !held.isEmpty() && MarketSavedData.tradeable(
                    held.copyWithCount((int) Math.max(1L, Math.min(held.getCount(), quantity))), player.registryAccess());
        } else {
            available = store().vault().total(account(), row.item(), row.payload());
            Identifier id = Identifier.tryParse(row.item());
            tradeable = id != null && !MarketSavedData.untradeable(id);
        }
        quote = HubQuotes.sell(quantity, unit, available, tradeable, EconomyConfig.LISTING_FEE.get(),
                EconomyConfig.MARKET_FEE_PERCENT.get());
    }

    @Override public void sell(UUID requestId, HubPages.RowRef row, long quantity, long unit) {
        clearQuote();
        if (row == null) {
            BookStacks.HandSale sale = BookStacks.sellFromHand(player, (int) quantity, unit, requestId);
            TradeMessages.sellOrder(player, sale.placed(), sale.offered().isEmpty() ? ""
                    : BuiltInRegistries.ITEM.getKey(sale.offered().getItem()).toString(), unit);
            return;
        }
        Placed placed = BookStacks.sellFromVault(server(), self(), row.item(), row.payload(), (int) quantity, unit,
                requestId);
        TradeMessages.sellOrder(player, placed, row.item(), unit);
    }

    @Override public void quoteBid(String item, long quantity, long unit) {
        quoteFor = HubAction.MARKET_BID_QUOTE;
        ItemCatalog items = Trading.env(store()).items();
        quote = HubQuotes.bid(quantity, unit, items.known(item), items.tradeable(item), EconomyConfig.LISTING_FEE.get());
    }

    @Override public void bid(UUID requestId, String item, long quantity, long unit) {
        clearQuote();
        Placed placed = BookStacks.buyOrder(server(), self(), player, item, (int) quantity, unit, requestId);
        TradeMessages.buyOrder(player, placed, item, unit);
    }

    @Override public void cancelOrder(HubPages.RowRef row) {
        CancelResult result = BookStacks.cancel(server(), self(), row.id());
        say(result.translationKey());
    }

    @Override public void pauseRule(HubPages.RowRef row, boolean paused) {
        RuleOutcome changed = RuleStacks.setPaused(server(), self(), row.id(), paused);
        if (!changed.ok()) say("command.neroeconomy.rule.refused", Component.translatable(changed.result().translationKey()));
        else say(paused ? "command.neroeconomy.rule.paused" : "command.neroeconomy.rule.resumed");
    }

    @Override public void deleteRule(HubPages.RowRef row) {
        RuleOutcome deleted = RuleStacks.delete(server(), self(), row.id());
        if (!deleted.ok()) say("command.neroeconomy.rule.refused", Component.translatable(deleted.result().translationKey()));
        else say(deleted.refundedToClaim() ? "command.neroeconomy.rule.deleted_claim" : "command.neroeconomy.rule.deleted",
                deleted.refunded());
    }

    @Override public void pauseAll(boolean paused) {
        int rules = RuleStacks.setPausedAll(server(), self(), paused);
        say(paused ? "command.neroeconomy.rule.paused_all" : "command.neroeconomy.rule.resumed_all", rules);
    }

    @Override public void addRule(UUID requestId, RuleForm form, long limitPrice, long total) {
        RuleOutcome added = RuleStacks.add(server(), self(), form.side(), form.tag(), form.target(), limitPrice,
                form.perDay(), total, RulesSection.VAULT, requestId);
        if (!added.ok()) {
            say("command.neroeconomy.rule.refused", Component.translatable(added.result().translationKey()));
            return;
        }
        TradeRule rule = added.rule();
        Component target = rule.tag() ? Component.literal("#" + rule.target()) : TradeMessages.itemName(rule.target());
        say(form.side() == RuleSide.BUY ? "command.neroeconomy.rule.added_buy" : "command.neroeconomy.rule.added_sell",
                TradeMessages.shortId(rule.id()), rule.perDay(), target, rule.limitPrice(), rule.total());
    }

    // --- F1b: the Wallet's "My data" ---------------------------------------------------------

    /** What the economy keeps about the viewer, as counts only. */
    private List<DataPages.DataLine> dataLines() {
        EconomyStore store = store();
        MinecraftServer server = server();
        EconomySavedData ledger = NeroEconomyCommon.CURRENCY_PROVIDER.activeData();
        List<DataPages.DataLine> lines = new ArrayList<>();
        lines.add(new DataPages.DataLine(DataPages.HISTORY, ledger.export(self()).transactions().size()));
        lines.add(new DataPages.DataLine(DataPages.OPT_OUT, ledger.loggingOptOut(self()) ? 1L : 0L));
        lines.add(new DataPages.DataLine(DataPages.WAITING, store.market().creditClaimTotal(self()) > 0L ? 1L : 0L));
        lines.add(new DataPages.DataLine(DataPages.VAULT, store.vault().stackCount(self())));
        lines.add(new DataPages.DataLine(DataPages.ORDERS, (long) store.book().sellsOf(account()).size()
                + store.book().buysOf(account()).size()));
        lines.add(new DataPages.DataLine(DataPages.RULES, store.rules().count(account())));
        lines.add(new DataPages.DataLine(DataPages.SHOPS, store.shopOwners().locationsOf(self()).size()));
        lines.add(new DataPages.DataLine(DataPages.OFFERS, (long) store.offers().sentBy(account()).size()
                + store.offers().receivedBy(account()).size()));
        za.co.neroland.neroeconomy.obligation.ObligationSavedData obligations =
                za.co.neroland.neroeconomy.obligation.ObligationSavedData.get(server);
        long claimed = obligations.jobs(null).stream().filter(job -> self().equals(job.claimantId())).count();
        lines.add(new DataPages.DataLine(DataPages.JOBS, obligations.jobsIssuedBy(self()).size() + claimed));
        lines.add(new DataPages.DataLine(DataPages.AUCTIONS, obligations.auctionsBy(self()).size()));
        var real = za.co.neroland.neroeconomy.exchange.Investing.portfolio(server, self());
        lines.add(new DataPages.DataLine(DataPages.HOLDINGS, (long) real.holdings().size() + real.bonds().size()));
        var practice = za.co.neroland.neroeconomy.exchange.Investing.practicePortfolio(server, self());
        lines.add(new DataPages.DataLine(DataPages.PRACTICE, (long) practice.holdings().size() + practice.bonds().size()));
        return lines;
    }

    @Override public void myData(boolean shown) {
        myData = shown;
        pages.put(HubTab.WALLET, 0);
    }

    // --- F1b: Invest -------------------------------------------------------------------------

    @Override public void investView(HubAction.InvestView view) {
        invest.view(view);
        pages.put(HubTab.INVEST, 0);
    }

    @Override public void investPractice(boolean practice) {
        invest.practice(practice);
        pages.put(HubTab.INVEST, 0);
    }

    @Override public void investPick(HubPages.RowRef row) { invest.pick(row); }

    @Override public void investQuote(long credits) { invest.quote(credits); }

    @Override public void investBuy(UUID requestId, long credits) { invest.buy(requestId, credits); }

    @Override public void investSellQuote(HubPages.RowRef row, long units) { invest.sellQuote(row, units); }

    @Override public void investSell(UUID requestId, HubPages.RowRef row, long units, long acceptedGain) {
        invest.sell(requestId, row, units, acceptedGain);
    }

    @Override public void investRedeem(UUID requestId, HubPages.RowRef row) { invest.redeem(requestId, row); }

    @Override public void investReset(UUID requestId) { invest.reset(requestId); }

    // --- F1b: Trade --------------------------------------------------------------------------

    @Override public void tradeView(HubAction.TradeView view) {
        trade.view(view);
        pages.put(HubTab.TRADE, 0);
    }

    @Override public void offerAccept(UUID requestId, HubPages.RowRef row) { trade.accept(requestId, row); }

    @Override public void offerDecline(HubPages.RowRef row) { trade.decline(row); }

    @Override public void offerCancel(HubPages.RowRef row) { trade.cancel(row); }

    @Override public void offerSend(UUID requestId, HubPages.RowRef row, OfferForm form) {
        trade.send(requestId, row, form);
        pages.put(HubTab.TRADE, 0);
    }

    @Override public void offerMute(boolean mute) { trade.mute(mute); }

    @Override public void tradeRequest(String name) { trade.request(name); }

    // --- F1b: Jobs ---------------------------------------------------------------------------

    @Override public void jobsView(HubAction.JobsView view) {
        jobs.view(view);
        pages.put(HubTab.JOBS, 0);
    }

    @Override public void jobClaim(HubPages.RowRef row) { jobs.claim(row); }

    @Override public void jobRelease(HubPages.RowRef row) { jobs.release(row); }

    @Override public void jobFulfil(HubPages.RowRef row) { jobs.fulfil(row); }

    @Override public void jobCancel(HubPages.RowRef row) { jobs.cancel(row); }

    @Override public void jobPost(JobForm form, long payout) {
        jobs.post(form, payout);
        pages.put(HubTab.JOBS, 0);
    }

    // --- F1b: Auctions -----------------------------------------------------------------------

    @Override public void auctionsView(HubAction.AuctionsView view) {
        auctions.view(view);
        pages.put(HubTab.AUCTIONS, 0);
    }

    @Override public void auctionBid(HubPages.RowRef row, long amount) { auctions.bid(row, amount); }

    @Override public void auctionCancel(HubPages.RowRef row) { auctions.cancel(row); }

    @Override public void auctionCreate(int quantity, long minimumBid, int minutes, boolean interplanetary) {
        auctions.create(quantity, minimumBid, minutes, interplanetary);
        pages.put(HubTab.AUCTIONS, 0);
    }

    // --- F1b: Shop ---------------------------------------------------------------------------

    @Override public void shopPrice(HubPages.RowRef row, long price) { shops.price(row, price); }

    @Override public void shopOpen(HubPages.RowRef row) { shops.open(row); }

    // --- F3: Admin (each call checks level 3 again, and mutations where it changes the economy) --

    @Override public void adminView(HubAction.AdminView view) {
        adminTab.view(view);
        pages.put(HubTab.ADMIN, 0);
    }

    @Override public void adminSimStep(int steps) { adminTab.simStep(steps); }

    @Override public void adminMarketFreeze(boolean closed) { adminTab.marketFreeze(closed); }

    @Override public void adminReloadCheck() {
        adminTab.reloadCheck();
        pages.put(HubTab.ADMIN, 0);
    }

    @Override public void adminAcceptFresh() { adminTab.acceptFresh(); }

    @Override public void adminTarget(String typed) { adminTab.target(typed); }

    @Override public void adminExport(HubPages.RowRef row) { adminTab.export(row); }

    @Override public void adminErase(HubPages.RowRef row) { adminTab.erase(row); }

    @Override public void adminGrant(HubPages.RowRef row, long amount) {
        adminTab.credits(row, amount, za.co.neroland.neroeconomy.economy.TransactionType.ADMIN_GRANT);
    }

    @Override public void adminTake(HubPages.RowRef row, long amount) {
        adminTab.credits(row, amount, za.co.neroland.neroeconomy.economy.TransactionType.ADMIN_TAKE);
    }

    @Override public void adminSet(HubPages.RowRef row, long amount) {
        adminTab.credits(row, amount, za.co.neroland.neroeconomy.economy.TransactionType.ADMIN_SET);
    }
}
