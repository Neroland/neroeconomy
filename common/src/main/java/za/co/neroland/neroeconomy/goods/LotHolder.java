package za.co.neroland.neroeconomy.goods;

import java.util.function.Consumer;

/**
 * A section of the economy store that holds lots. The prototype table keeps a prototype for as
 * long as any holder reports a lot that names it, so every section that stores lots — the vault,
 * and later the order book, offers, auto-trade rules and auctions — implements this and is listed
 * in {@code EconomyStore.sections()}.
 *
 * <p>Reporting too little loses goods: a prototype nobody reports is dropped, and a lot that names
 * a dropped prototype can no longer be turned back into an item stack. Report every lot held, in
 * whatever state (escrowed, pending, expired but not yet refunded).
 */
@FunctionalInterface
public interface LotHolder {
    /** Hands every lot this section holds to {@code visitor}. Must not change the section. */
    void forEachLot(Consumer<Lot> visitor);
}
