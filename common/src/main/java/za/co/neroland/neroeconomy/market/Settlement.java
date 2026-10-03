package za.co.neroland.neroeconomy.market;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * The credit movements behind every market operation, as ledger plans. Nothing here touches the
 * game, an item or a store: each method only says which accounts pay which, so the rules — who pays
 * the fee, who pays for a discount, what may come out of an unvested starting balance — are unit
 * tested directly against the ledger.
 *
 * <p>The escrow account holds every credit that has been promised and not yet delivered. A plan
 * that puts credits into it is matched by a number recorded in a store (a buy offer's escrow, a
 * credit claim, a job payout, a standing bid), and a plan that takes credits out of it by that
 * number going down by the same amount.
 */
public final class Settlement {
    private Settlement() { }

    /**
     * One sale at the seller's own price.
     *
     * @param base     the seller's price for the quantity sold
     * @param fee      the fee on it, worked out on the whole order
     * @param proceeds what the seller receives: {@code base − fee}
     */
    public record Sale(long base, long fee, long proceeds) { }

    /** The fee and proceeds of a sale of {@code base} credits at {@code feePercent}. */
    public static Sale sale(long base, double feePercent) {
        long total = Math.max(0L, base);
        long fee = Fees.fee(total, feePercent);
        return new Sale(total, fee, total - fee);
    }

    /**
     * A purchase from a listing. A price modifier changes the buyer's price only:
     * <ul>
     *   <li>the buyer's {@code MARKET_PURCHASE} or {@code SHOP_PURCHASE} row always carries the
     *       seller's own price, {@code base};</li>
     *   <li>the seller receives {@code base − fee}: into escrow, to be collected, for a board
     *       listing; straight into their balance for a shop;</li>
     *   <li>the fee goes to the Reserve;</li>
     *   <li>when {@code total} (what the buyer pays) is above {@code base}, the surcharge goes to
     *       the Reserve; when it is below, the Reserve pays the discount to the buyer first. Both
     *       are {@code PRICE_ADJUSTMENT} rows.</li>
     * </ul>
     * A plan with a discount is refused as {@code RESERVE_SHORT} when the Reserve cannot pay it;
     * the caller then settles at {@code base} instead. The caller caps a discount with
     * {@link #cappedUnitPrice} first, so {@code base − total} is never more than {@code fee}.
     *
     * @param board true for a Market Board listing (proceeds to the seller's credit claim), false for a shop
     * @param base  the seller's own price for the quantity
     * @param total what the buyer pays
     * @param fee   the market fee on {@code base}
     */
    public static LedgerPlan purchase(UUID buyerId, UUID sellerId, boolean board, long base, long total, long fee) {
        PlayerAccountId buyer = new PlayerAccountId(buyerId);
        LedgerPlan.Builder plan = LedgerPlan.builder();
        // A discount is the Reserve's to pay, and it pays before the buyer does.
        if (total < base) plan.pay(BudgetKind.UNBUDGETED, buyer, base - total, TransactionType.PRICE_ADJUSTMENT);
        if (board) {
            plan.move(buyer, SystemAccountId.ESCROW, base, TransactionType.MARKET_PURCHASE);
            plan.move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee, TransactionType.FEE_MARKET);
        } else {
            PlayerAccountId seller = new PlayerAccountId(sellerId);
            plan.move(buyer, seller, base, TransactionType.SHOP_PURCHASE);
            plan.move(seller, SystemAccountId.RESERVE, fee, TransactionType.FEE_MARKET);
        }
        // A surcharge is part of the price of buying from another player, so it is player-facing too.
        if (total > base) plan.movePlayerFacing(buyer, SystemAccountId.RESERVE, total - base, TransactionType.PRICE_ADJUSTMENT);
        return plan.build();
    }

    /**
     * The most the Reserve pays towards one unit sold at {@code baseUnitPrice}: the market fee on
     * that one unit, rounded <em>down</em>. The fee itself is worked out on the order total and
     * rounded up, so for any quantity the discounts of a sale together are never more than the fee
     * the same sale pays the Reserve — which is what stops two accounts trading the same goods
     * back and forth from drawing on it. Nothing at all when no market fee is charged.
     */
    public static long discountRoom(long baseUnitPrice, double feePercent) {
        int points = Fees.basisPoints(feePercent);
        if (baseUnitPrice <= 0L || points <= 0) return 0L;
        // base × points / 10000, rounded down, without the product ever leaving 64 bits.
        return baseUnitPrice / 10_000L * points + baseUnitPrice % 10_000L * points / 10_000L;
    }

    /**
     * The buyer's unit price once a discount has been cut back to what the Reserve may pay (see
     * {@link #discountRoom}). A surcharge, and a price no modifier touched, pass through unchanged.
     * The result does not depend on the quantity, so the price a buyer is quoted for one unit is
     * the price every unit of the order is charged at.
     *
     * @param baseUnitPrice     the seller's own price per unit
     * @param modifiedUnitPrice the price per unit after shocks and price modifiers
     * @param feePercent        the market fee the sale pays
     */
    public static long cappedUnitPrice(long baseUnitPrice, long modifiedUnitPrice, double feePercent) {
        if (modifiedUnitPrice >= baseUnitPrice) return modifiedUnitPrice;
        return Math.max(modifiedUnitPrice, baseUnitPrice - discountRoom(baseUnitPrice, feePercent));
    }

    /**
     * {@link #buyerUnitPrice(long, long, long)} for a modified price that has not been capped yet:
     * the discount is first cut back to the fee the sale pays, then shown only if the Reserve can
     * pay it.
     */
    public static long buyerUnitPrice(long baseUnitPrice, long modifiedUnitPrice, long reserveBalance,
            double feePercent) {
        return buyerUnitPrice(baseUnitPrice, cappedUnitPrice(baseUnitPrice, modifiedUnitPrice, feePercent),
                reserveBalance);
    }

    /**
     * The unit price to show a buyer. Normally that is the modified price. When the modified price
     * is a discount the Reserve could not pay for even one unit, the purchase would settle at the
     * seller's own price, so that is the price shown: nobody is quoted a price they cannot get.
     *
     * @param baseUnitPrice     the seller's own price per unit
     * @param modifiedUnitPrice the price per unit after shocks and price modifiers
     * @param reserveBalance    what the Reserve holds right now
     */
    public static long buyerUnitPrice(long baseUnitPrice, long modifiedUnitPrice, long reserveBalance) {
        if (modifiedUnitPrice >= baseUnitPrice) return modifiedUnitPrice;
        return baseUnitPrice - modifiedUnitPrice <= reserveBalance ? modifiedUnitPrice : baseUnitPrice;
    }

    /**
     * Posting a buy offer: the escrow moves to the escrow account and the listing fee to the
     * Reserve, or neither does. The escrow is promised to another player, so it cannot come out of
     * an unvested starting balance; the fee can.
     */
    public static LedgerPlan buyOffer(UUID buyerId, long escrow, long listingFee) {
        PlayerAccountId buyer = new PlayerAccountId(buyerId);
        return LedgerPlan.builder().move(buyer, SystemAccountId.ESCROW, escrow, TransactionType.MARKET_ESCROW)
                .move(buyer, SystemAccountId.RESERVE, listingFee, TransactionType.FEE_LISTING).build();
    }

    /** A flat fee a player pays to the Reserve, such as the listing fee of a board listing. */
    public static LedgerPlan playerFee(UUID playerId, long fee, TransactionType reason) {
        return LedgerPlan.of(new PlayerAccountId(playerId), SystemAccountId.RESERVE, fee, reason);
    }

    /** Credits a player promises to somebody else: a job payout, an auction bid. */
    public static LedgerPlan escrow(UUID playerId, long amount, TransactionType reason) {
        return LedgerPlan.of(new PlayerAccountId(playerId), SystemAccountId.ESCROW, amount, reason);
    }

    /** Credits in escrow reaching the player they are owed to: a collected claim, under its own reason. */
    public static LedgerPlan collect(UUID playerId, long amount, TransactionType reason) {
        return LedgerPlan.of(SystemAccountId.ESCROW, new PlayerAccountId(playerId), amount, reason);
    }

    /** A fee taken out of proceeds that are sitting in escrow: a filled buy offer, a won auction. */
    public static LedgerPlan feeFromEscrow(long fee, TransactionType reason) {
        return LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE, fee, reason);
    }

    /** A system job's payout, escrowed from the Reserve within the daily system-job budget. */
    public static LedgerPlan systemJobEscrow(long payout) {
        return LedgerPlan.pay(BudgetKind.SYSTEM_JOB, SystemAccountId.ESCROW, payout, TransactionType.MARKET_ESCROW);
    }

    /** Escrow going back to the Reserve it came from: an expired system job. */
    public static LedgerPlan escrowToReserve(long amount, TransactionType reason) {
        return LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE, amount, reason);
    }
}
