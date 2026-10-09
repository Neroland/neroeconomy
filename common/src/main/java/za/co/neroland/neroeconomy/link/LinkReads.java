package za.co.neroland.neroeconomy.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.exchange.ExchangeViews;
import za.co.neroland.neroeconomy.exchange.Investing;
import za.co.neroland.neroeconomy.exchange.TradingTerms;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.obligation.AuctionRecord;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.JobTargets;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.offers.Offer;
import za.co.neroland.neroeconomy.offers.OfferStacks;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * Reads one player's own state out of the stores into the plain records of {@link LinkViews}. The
 * edge between the game and the pure JSON builders: it runs on the server thread, it only ever
 * reads, and everything it reads is the requesting player's own — their orders, rules, vault,
 * holdings, offers, jobs and bids. Rows that belong to somebody else (a public job, an auction)
 * carry no seller, issuer or bidder, only whether the row is the viewer's own.
 */
final class LinkReads {
    private LinkReads() { }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    static long now() { return NeroEconomyCommon.CURRENCY_PROVIDER.now(); }

    static long wallNow() { return NeroEconomyCommon.CURRENCY_PROVIDER.toWallMillis(now()); }

    /** What the daily remote limit allows this player now. */
    static LinkViews.Remote remote(EconomyStore store, UUID player) {
        long day = EconomySavedData.economyDay(now());
        return new LinkViews.Remote(EconomyConfig.REMOTE_TRADE_ENABLED.get(),
                store.remoteSpend().spentToday(player, day), EconomyConfig.REMOTE_DAILY_SPEND_LIMIT.get());
    }

    // --- names -------------------------------------------------------------------------------

    /** The item's own name, never a name typed on an anvil. */
    static String itemName(ItemStack stack) {
        try {
            return stack == null || stack.isEmpty() ? "" : stack.getItemName().getString();
        } catch (RuntimeException unresolved) {
            return "";
        }
    }

    static String itemName(String itemId) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        if (id == null) return "";
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> itemName(new ItemStack(item))).orElse("");
    }

    static LinkViews.Goods goods(EconomyStore store, Lot lot) {
        String name = lot.pristine() ? itemName(lot.item()) : itemName(store.goods().stackOf(lot.withCount(1)));
        return new LinkViews.Goods(lot.item(), name, lot.count(), !lot.pristine());
    }

    /** A lang key's English text, or a name built from {@code id} when the lang file has none. */
    static String named(String key, String id) {
        return LinkText.has(key) ? LinkText.get(key) : LinkPayloads.displayName("", id);
    }

    // --- summary -----------------------------------------------------------------------------

    static LinkViews.Summary summary(MinecraftServer server, UUID player) {
        EconomyStore store = EconomyStore.get(server);
        AccountId account = account(player);
        long value = 0L;
        long cost = 0L;
        int bonds = 0;
        if (!EconomyHealth.frozen()) {
            ExchangeViews.PortfolioView real = Investing.portfolio(server, player);
            value = real.value();
            cost = real.cost();
            bonds = real.bonds().size();
        }
        return new LinkViews.Summary(NeroEconomyCommon.CREDITS.id().toString(),
                CurrencyApi.getBalance(player, NeroEconomyCommon.CREDITS), store.market().creditClaimTotal(player),
                store.vault().stackCount(player), VaultStacks.cap(), store.book().sellsOf(account).size(),
                store.book().buysOf(account).size(), store.rules().count(account),
                EconomyConfig.MAX_RULES_PER_PLAYER.get(), store.offers().receivedBy(account).size(),
                store.offers().sentBy(account).size(), value, cost, bonds, remote(store, player), !store.marketClosed(),
                EconomyHealth.frozen(), wallNow());
    }

    // --- orders ------------------------------------------------------------------------------

    static LinkViews.Orders orders(MinecraftServer server, UUID player) {
        EconomyStore store = EconomyStore.get(server);
        AccountId account = account(player);
        long now = now();
        List<LinkViews.SellOrderRow> sell = new ArrayList<>();
        for (SellOrder order : store.book().sellsOf(account)) {
            if (sell.size() >= LinkViews.MAX_ROWS) break;
            sell.add(new LinkViews.SellOrderRow(order.id().toString(), goods(store, order.lot()), order.unitPrice(),
                    millis(order.expiresAt() - now)));
        }
        List<LinkViews.BuyOrderRow> buy = new ArrayList<>();
        for (BuyOrder order : store.book().buysOf(account)) {
            if (buy.size() >= LinkViews.MAX_ROWS) break;
            buy.add(new LinkViews.BuyOrderRow(order.id().toString(), new LinkViews.Goods(order.item(),
                    itemName(order.item()), order.remaining(), false), order.unitPrice(), order.escrow(),
                    millis(order.expiresAt() - now)));
        }
        List<LinkViews.ShopRow> shops = new ArrayList<>();
        for (MarketListing listing : store.market().listingsOf(player)) {
            if (shops.size() >= LinkViews.MAX_ROWS) break;
            if (listing.source() != ListingSource.PLAYER_SHOP) continue;
            ItemStack stock = listing.stock();
            boolean modified = !za.co.neroland.neroeconomy.market.MarketSavedData.pristine(stock);
            shops.add(new LinkViews.ShopRow(listing.listingId().toString(), new LinkViews.Goods(
                    listing.itemId().toString(), itemName(stock), listing.availableQuantity(), modified),
                    listing.baseUnitPrice()));
        }
        List<LinkViews.RuleRow> rules = new ArrayList<>();
        for (TradeRule rule : store.rules().rulesOf(account)) {
            if (rules.size() >= LinkViews.MAX_ROWS) break;
            rules.add(rule(rule));
        }
        return new LinkViews.Orders(sell, buy, shops, rules, EconomyConfig.MAX_RULES_PER_PLAYER.get(),
                store.rules().valueToday(account, now), EconomyConfig.RULE_DAILY_VALUE_CAP.get(),
                store.rules().pausedAll(account), wallNow());
    }

    static LinkViews.RuleRow rule(TradeRule rule) {
        String name = rule.tag() ? "#" + rule.target() : LinkPayloads.displayName(itemName(rule.target()), rule.target());
        return new LinkViews.RuleRow(rule.id().toString(), rule.side().name().toLowerCase(Locale.ROOT), rule.tag(),
                rule.target(), name, rule.limitPrice(), rule.perDay(), rule.filledToday(), rule.total(), rule.done(),
                rule.budget(), rule.paused(), RulesSection.VAULT.equals(rule.binding()),
                rule.status().name().toLowerCase(Locale.ROOT), LinkText.sentence(rule.status().translationKey()));
    }

    private static long millis(long left) { return Math.max(0L, left); }

    // --- vault -------------------------------------------------------------------------------

    static LinkViews.VaultView vault(MinecraftServer server, UUID player) {
        EconomyStore store = EconomyStore.get(server);
        List<Lot> lots = store.vault().stacks(player);
        List<LinkViews.VaultRow> rows = new ArrayList<>();
        for (int index = 0; index < lots.size() && rows.size() < LinkViews.MAX_VAULT_ROWS; index++) {
            Lot lot = lots.get(index);
            rows.add(new LinkViews.VaultRow(index, goods(store, lot), lot.payload()));
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return new LinkViews.VaultView(rows, store.vault().stackCount(player), VaultStacks.cap(), online != null,
                liveVaultActions(online), wallNow());
    }

    /** Whether the two vault moves could run for this player now; the same checks they make themselves. */
    static boolean liveVaultActions(ServerPlayer online) {
        return online != null && online.isAlive() && !online.isSpectator() && EconomyConfig.REMOTE_TRADE_ENABLED.get()
                && !EconomyHealth.frozen();
    }

    // --- exchange ----------------------------------------------------------------------------

    /**
     * One item's order book, read from the same lists the Economy Hub's market tab shows its best
     * bid and ask from, so the app and the game agree. {@code item} is a normalised item id, or
     * empty for none.
     */
    static LinkViews.ItemBook book(MinecraftServer server, String item) {
        if (item == null || item.isEmpty()) return new LinkViews.ItemBook("", "", List.of(), List.of(), wallNow());
        EconomyStore store = EconomyStore.get(server);
        List<LinkViews.BookOrder> bids = new ArrayList<>();
        for (BuyOrder order : store.book().bids(item)) bids.add(new LinkViews.BookOrder(order.unitPrice(), order.remaining()));
        List<LinkViews.BookOrder> asks = new ArrayList<>();
        for (SellOrder order : store.book().asks(item))
            asks.add(new LinkViews.BookOrder(order.unitPrice(), order.lot().count()));
        return new LinkViews.ItemBook(item, itemName(item), LinkViews.levels(bids), LinkViews.levels(asks), wallNow());
    }

    /** An item id as the book keys it ({@code minecraft:} when no namespace is given); empty when it is not one. */
    static String itemId(String typed) {
        if (typed == null) return "";
        String strip = typed.strip().replace(' ', '_');
        if (strip.isEmpty() || strip.length() > Lot.MAX_ITEM_LENGTH) return "";
        Identifier id = Identifier.tryParse(strip);
        return id == null ? "" : id.toString();
    }

    static LinkViews.Stocks stocks(MinecraftServer server) {
        EconomyStore store = EconomyStore.get(server);
        List<LinkViews.Stock> stocks = new ArrayList<>();
        for (ExchangeViews.Product product : Investing.products(server)) {
            if (stocks.size() >= LinkViews.MAX_STOCKS) break;
            Optional<ExchangeApi.History> history = store.exchange().history(product.id());
            stocks.add(new LinkViews.Stock(product.id(), named(product.nameKey(), product.id()), product.index(),
                    product.type().name().toLowerCase(Locale.ROOT), product.sector(), product.priceCents(),
                    product.askCents(), product.bidCents(), product.dayChangeBasisPoints(),
                    product.statusKey().isEmpty() ? "" : LinkText.sentence(product.statusKey()),
                    LinkText.get(product.riskKey()), product.dividendBasisPoints(),
                    history.map(ExchangeApi.History::recentCents).orElse(List.of()),
                    history.map(ExchangeApi.History::dailyCents).orElse(List.of())));
        }
        List<LinkViews.BondOffer> bonds = new ArrayList<>();
        for (ExchangeViews.BondOffer bond : Investing.bondOffers())
            bonds.add(new LinkViews.BondOffer(bond.id(), named(bond.nameKey(), bond.id()), bond.days(),
                    bond.rateBasisPoints()));
        TradingTerms terms = TradingTerms.fromConfig();
        boolean open = terms.enabled() && !store.marketClosed() && !EconomyHealth.frozen();
        return new LinkViews.Stocks(open, stocks, bonds, terms.feeBps(), terms.holdMillis() / 60_000L, wallNow());
    }

    /** The {@code stocks} section with no products, for a throttled read: the trading terms are still the real ones. */
    static LinkViews.Stocks noStocks(long asOf) {
        TradingTerms terms = TradingTerms.fromConfig();
        return new LinkViews.Stocks(false, List.of(), List.of(), terms.feeBps(), terms.holdMillis() / 60_000L, asOf);
    }

    static LinkViews.Portfolio portfolio(MinecraftServer server, UUID player) {
        LinkViews.Book empty = new LinkViews.Book(List.of(), List.of(), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 100,
                false, 0L, false);
        if (EconomyHealth.frozen()) return new LinkViews.Portfolio(empty, empty, wallNow());
        return new LinkViews.Portfolio(book(Investing.portfolio(server, player)),
                book(Investing.practicePortfolio(server, player)), wallNow());
    }

    private static LinkViews.Book book(ExchangeViews.PortfolioView view) {
        long now = now();
        List<LinkViews.Holding> holdings = new ArrayList<>();
        for (ExchangeViews.HoldingView holding : view.holdings())
            holdings.add(new LinkViews.Holding(holding.stockId(), named(holding.nameKey(), holding.stockId()),
                    holding.units(), holding.cost(), holding.value(), holding.difference(), holding.sellableUnits(),
                    holding.earliestSellAt() <= 0L ? 0L : millis(holding.earliestSellAt() - now),
                    holding.statusKey().isEmpty() ? "" : LinkText.sentence(holding.statusKey())));
        List<LinkViews.BondRow> bonds = new ArrayList<>();
        for (ExchangeViews.BondView bond : view.bonds())
            bonds.add(new LinkViews.BondRow(bond.id().toString(), bond.term(), named(bond.nameKey(), bond.term()),
                    bond.days(), bond.principal(), bond.interest(), millis(bond.maturesAt() - now), bond.matured()));
        return new LinkViews.Book(holdings, bonds, view.cost(), view.value(), view.difference(), view.bondPrincipal(),
                view.bondInterest(), view.invested(), view.portfolioLimit(), view.positionLimit(), view.bondLimit(),
                view.lastDividend(), view.dividends().percent(), view.dividends().scaled(), view.playBalance(),
                view.canReset());
    }

    // --- offers ------------------------------------------------------------------------------

    static LinkViews.Offers offers(MinecraftServer server, UUID player) {
        EconomyStore store = EconomyStore.get(server);
        AccountId account = account(player);
        long now = now();
        List<LinkViews.OfferRow> inbox = new ArrayList<>();
        for (Offer offer : store.offers().receivedBy(account)) inbox.add(offer(store, offer, now));
        List<LinkViews.OfferRow> outbox = new ArrayList<>();
        for (Offer offer : store.offers().sentBy(account)) outbox.add(offer(store, offer, now));
        return new LinkViews.Offers(inbox, outbox, OfferStacks.muted(server, player),
                EconomyConfig.MAX_OFFERS_PER_PLAYER.get(), wallNow());
    }

    private static LinkViews.OfferRow offer(EconomyStore store, Offer offer, long now) {
        List<LinkViews.Goods> given = new ArrayList<>();
        for (Lot lot : offer.lots()) given.add(goods(store, lot));
        LinkViews.Goods ask = offer.asksItems()
                ? new LinkViews.Goods(offer.askItem(), itemName(offer.askItem()), offer.askCount(), false) : null;
        return new LinkViews.OfferRow(offer.id().toString(), given, offer.credits(), ask, offer.askCredits(),
                millis(offer.expiresAt() - now));
    }

    // --- jobs and auctions -------------------------------------------------------------------

    /** @param view {@code open} (every open job), {@code contracts}, {@code deliveries}, {@code bounties} or {@code mine} */
    static LinkViews.Jobs jobs(MinecraftServer server, UUID player, String view) {
        ObligationSavedData store = ObligationSavedData.get(server);
        long now = now();
        List<EconomyJob> listed = switch (view) {
            case "contracts" -> store.jobs(JobType.CONTRACT);
            case "deliveries" -> store.jobs(JobType.DELIVERY);
            case "bounties" -> store.jobs(JobType.BOUNTY);
            case "mine" -> {
                List<EconomyJob> mine = new ArrayList<>(store.jobsIssuedBy(player));
                for (EconomyJob job : store.jobs(null))
                    if (player.equals(job.claimantId()) && !mine.contains(job)) mine.add(job);
                yield mine;
            }
            default -> store.jobs(null);
        };
        List<LinkViews.JobRow> rows = new ArrayList<>();
        for (EconomyJob job : listed) {
            if (rows.size() >= LinkViews.MAX_ROWS) break;
            String shown = JobTargets.visibleTo(job.target(), job.issuer(), player);
            String label = JobTargets.playerLabelKey(shown);
            String target;
            String name;
            if (label != null) {
                // A player target is never sent as an id: the viewer's own phrase for it, as in game.
                target = "player";
                name = LinkText.get(label);
            } else if (JobTargets.isTag(shown)) {
                target = shown;
                name = shown;
            } else {
                target = shown;
                String item = job.type() == JobType.BOUNTY ? "" : itemName(shown);
                name = LinkPayloads.displayName(item, shown);
            }
            rows.add(new LinkViews.JobRow(job.id().toString(), job.type().name().toLowerCase(Locale.ROOT), target, name,
                    job.quantity(), job.payout(), millis(job.expiresAt() - now),
                    job.state().name().toLowerCase(Locale.ROOT), job.issuer().equals(player),
                    player.equals(job.claimantId()), ObligationSavedData.SYSTEM_ACCOUNT.equals(job.issuer())));
        }
        return new LinkViews.Jobs(view, rows, store.jobsIssuedBy(player).size(), EconomyConfig.MAX_JOBS_PER_PLAYER.get(),
                wallNow());
    }

    /**
     * @param view   {@code all} or {@code mine}
     * @param online the player when in game; a player who is away sees no lot behind the orbit gate,
     *               because whether they have reached orbit can only be read in game
     */
    static LinkViews.Auctions auctions(MinecraftServer server, UUID player, ServerPlayer online, String view) {
        ObligationSavedData store = ObligationSavedData.get(server);
        long now = now();
        List<AuctionRecord> listed;
        if ("mine".equals(view)) listed = store.auctionsBy(player);
        else if (online != null) listed = store.auctions(online);
        else listed = store.auctions(null).stream().filter(auction -> !auction.interplanetary()).toList();
        java.util.Set<UUID> houses = new java.util.HashSet<>();
        store.houseAuctions(true).forEach(house -> houses.add(house.id()));
        List<LinkViews.AuctionRow> rows = new ArrayList<>();
        for (AuctionRecord auction : listed) {
            if (rows.size() >= LinkViews.MAX_ROWS) break;
            ItemStack lot = auction.lot();
            rows.add(new LinkViews.AuctionRow(auction.id().toString(), new LinkViews.Goods(
                    BuiltInRegistries.ITEM.getKey(lot.getItem()).toString(), itemName(lot), lot.getCount(),
                    !za.co.neroland.neroeconomy.market.MarketSavedData.pristine(lot)), auction.highestBid(),
                    store.minimumNextBid(auction.id()), millis(auction.endsAt() - now), auction.seller().equals(player),
                    player.equals(auction.bidderId()), houses.contains(auction.id()), auction.interplanetary()));
        }
        return new LinkViews.Auctions("mine".equals(view) ? "mine" : "all", rows, wallNow());
    }
}
