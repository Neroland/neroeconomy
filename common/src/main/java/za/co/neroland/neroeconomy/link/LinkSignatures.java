package za.co.neroland.neroeconomy.link;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.exchange.ExchangeViews;
import za.co.neroland.neroeconomy.exchange.Investing;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.offers.Offer;
import za.co.neroland.neroeconomy.rules.TradeRule;

/**
 * One number per live topic that changes whenever what the topic's section would show changes,
 * read from the player's own rows only. {@link LinkTopics} compares them to decide when an event
 * is due; the numbers themselves are never sent anywhere.
 */
final class LinkSignatures {
    private LinkSignatures() { }

    static long orders(EconomyStore store, UUID player) {
        AccountId account = new PlayerAccountId(player);
        long hash = 1L;
        for (SellOrder order : store.book().sellsOf(account)) hash = hash * 31L + Objects.hash(order.id(), order.lot());
        for (BuyOrder order : store.book().buysOf(account))
            hash = hash * 31L + Objects.hash(order.id(), order.remaining(), order.escrow());
        for (MarketListing listing : store.market().listingsOf(player))
            hash = hash * 31L + Objects.hash(listing.listingId(), listing.availableQuantity());
        for (TradeRule rule : store.rules().rulesOf(account))
            hash = hash * 31L + Objects.hash(rule.id(), rule.status(), rule.paused(), rule.filledToday(), rule.done(),
                    rule.budget());
        return hash * 31L + (store.rules().pausedAll(account) ? 1L : 0L);
    }

    static long offers(EconomyStore store, UUID player) {
        AccountId account = new PlayerAccountId(player);
        long hash = 1L;
        for (Offer offer : store.offers().receivedBy(account)) hash = hash * 31L + offer.id().hashCode();
        hash = hash * 31L + 7L;
        for (Offer offer : store.offers().sentBy(account)) hash = hash * 31L + offer.id().hashCode();
        return hash;
    }

    static long portfolio(MinecraftServer server, UUID player) {
        ExchangeViews.PortfolioView view = Investing.portfolio(server, player);
        long hash = Objects.hash(view.cost(), view.value(), view.bondPrincipal(), view.lastDividend());
        for (ExchangeViews.HoldingView holding : view.holdings())
            hash = hash * 31L + Objects.hash(holding.stockId(), holding.units(), holding.sellableUnits());
        for (ExchangeViews.BondView bond : view.bonds()) hash = hash * 31L + Objects.hash(bond.id(), bond.matured());
        return hash;
    }
}
