package za.co.neroland.neroeconomy.api;

import java.util.UUID;
import java.util.function.Consumer;

import za.co.neroland.neroeconomy.sibling.EconomyEventBus;

/**
 * Typed economy events for other mods ({@code compileOnly} listeners). Each fires on the server
 * thread <b>after</b> the ledger applied the operation and the store was marked dirty; never for a
 * refused or rolled-back one, never from the gallery or practice mode, and never for goods from a
 * creative or spectator source.
 *
 * <p>Privacy: an event carries one party only. A buyer and a seller of the same trade are never in
 * one event, a Merchant House is never named as anyone's counterparty, and no event carries a
 * balance, a name or free text. A listener must not keep the player id beyond its own handling
 * (for example, count progress and drop the id).
 *
 * <p>A listener that throws is caught, reported once at warn without the event, and stays
 * registered; it never stops the next listener or the trade.
 */
public final class EconomyEvents {
    /** Where a sale or purchase was settled. */
    public enum Venue { BOARD, SHOP, HOUSE, AUTO_TRADER, AUCTION }

    /** The seller's side of one settled sale: no buyer, no balance, no name. */
    public record Sold(UUID seller, String itemId, long quantity, long unitPrice, Venue venue, long at) { }

    /** The buyer's side of one settled purchase: no seller. */
    public record Bought(UUID buyer, String itemId, long quantity, long unitPrice, Venue venue, long at) { }

    /** A resting order that filled, possibly while its owner was offline. */
    public record Filled(UUID owner, String itemId, long quantity, long unitPrice, boolean buyOrder, long at) { }

    /** One economy day's dividend run, world-level only. */
    public record Dividends(long economyDay, long totalPaid, double factor, int stocks) { }

    /** A payout another mod made through {@link EconomyCaller#pay} that was applied (for "earn N" tracking). */
    public record SiblingPaid(UUID player, String modId, String reason, long amount, long at) { }

    private EconomyEvents() { }

    public static void onSold(Consumer<Sold> listener) { EconomyEventBus.SOLD.add(listener); }
    public static void onBought(Consumer<Bought> listener) { EconomyEventBus.BOUGHT.add(listener); }
    public static void onFilled(Consumer<Filled> listener) { EconomyEventBus.FILLED.add(listener); }
    public static void onDividends(Consumer<Dividends> listener) { EconomyEventBus.DIVIDENDS.add(listener); }
    public static void onSiblingPaid(Consumer<SiblingPaid> listener) { EconomyEventBus.SIBLING_PAID.add(listener); }
}
