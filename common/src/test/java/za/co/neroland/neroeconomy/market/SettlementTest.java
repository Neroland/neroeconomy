package za.co.neroland.neroeconomy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * Who pays what in every market operation, checked against the ledger. The stores themselves need
 * the game to run; the credit movements they make are these plans, and the numbers they record are
 * modelled here with the same {@link CreditClaims} the market store uses.
 */
class SettlementTest {
    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-00000000005e");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a7");
    private static final long RESERVE = 250_000L;

    private EconomySavedData ledger;

    @BeforeEach void openAccounts() {
        ledger = new EconomySavedData();
        ledger.seedReserve(RESERVE);
        for (UUID player : List.of(BUYER, SELLER, OTHER)) {
            ledger.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
            ledger.markVested(player);
            assertTrue(ledger.deposit(player, NeroEconomyCommon.CREDITS, 900L, TransactionType.ADMIN_GRANT));
        }
    }

    private long balance(UUID player) { return ledger.getBalance(player, NeroEconomyCommon.CREDITS); }
    private long reserve() { return ledger.balance(SystemAccountId.RESERVE); }
    private long escrow() { return ledger.balance(SystemAccountId.ESCROW); }

    @Test void aSaleSplitsIntoTheFeeAndWhatTheSellerReceives() {
        assertEquals(new Settlement.Sale(500L, 10L, 490L), Settlement.sale(500L, 2.0D));
        assertEquals(new Settlement.Sale(501L, 11L, 490L), Settlement.sale(501L, 2.0D));
        assertEquals(new Settlement.Sale(1L, 1L, 0L), Settlement.sale(1L, 2.0D));
        assertEquals(new Settlement.Sale(500L, 0L, 500L), Settlement.sale(500L, 0.0D));
        assertEquals(new Settlement.Sale(0L, 0L, 0L), Settlement.sale(-5L, 2.0D));
        Settlement.Sale auction = Settlement.sale(1_000L, 5.0D);
        assertEquals(50L, auction.fee());
        assertEquals(950L, auction.proceeds());
    }

    @Test void aBuyerIsOnlyQuotedADiscountTheReserveCanPay() {
        // No modifier, or a surcharge: the modified price is the price.
        assertEquals(100L, Settlement.buyerUnitPrice(100L, 100L, 0L));
        assertEquals(130L, Settlement.buyerUnitPrice(100L, 130L, 0L));
        // A discount of 25 the Reserve can pay is shown; one it cannot pay is not.
        assertEquals(75L, Settlement.buyerUnitPrice(100L, 75L, 25L));
        assertEquals(75L, Settlement.buyerUnitPrice(100L, 75L, 1_000_000L));
        assertEquals(100L, Settlement.buyerUnitPrice(100L, 75L, 24L));
        assertEquals(100L, Settlement.buyerUnitPrice(100L, 75L, 0L));
        // So the price shown is always one the purchase can settle at.
        assertEquals(LedgerResult.APPLIED, ledger.apply(LedgerPlan.of(SystemAccountId.RESERVE, SystemAccountId.EXCHANGE,
                reserve() - 24L, TransactionType.FEE_EXCHANGE)));
        long shown = Settlement.buyerUnitPrice(100L, 75L, reserve());
        assertEquals(100L, shown);
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(BUYER, SELLER, true, 100L, shown, 2L)));
        assertEquals(900L, balance(BUYER));
    }

    @Test void aDiscountIsCutBackToTheFeeTheSameSalePaysTheReserve() {
        // The fee on one unit, rounded down, is all the Reserve pays towards it.
        assertEquals(20L, Settlement.discountRoom(1_000L, 2.0D));
        assertEquals(1L, Settlement.discountRoom(50L, 2.0D));
        assertEquals(0L, Settlement.discountRoom(49L, 2.0D));
        assertEquals(0L, Settlement.discountRoom(1_000L, 0.0D));
        assertEquals(0L, Settlement.discountRoom(0L, 2.0D));
        assertEquals(Long.MAX_VALUE / 2L, Settlement.discountRoom(Long.MAX_VALUE, 50.0D));
        // A surcharge and an untouched price pass through; a discount stops at the room.
        assertEquals(1_300L, Settlement.cappedUnitPrice(1_000L, 1_300L, 2.0D));
        assertEquals(1_000L, Settlement.cappedUnitPrice(1_000L, 1_000L, 2.0D));
        assertEquals(990L, Settlement.cappedUnitPrice(1_000L, 990L, 2.0D));
        assertEquals(980L, Settlement.cappedUnitPrice(1_000L, 500L, 2.0D));
        assertEquals(10L, Settlement.cappedUnitPrice(10L, 1L, 2.0D));
        assertEquals(1_000L, Settlement.cappedUnitPrice(1_000L, 500L, 0.0D));
        // For every price and quantity the discounts of a sale never add up to more than its fee.
        Random random = new Random(11L);
        for (int round = 0; round < 20_000; round++) {
            long base = 1L + random.nextInt(round % 3 == 0 ? 20_000 : 200);
            int quantity = 1 + random.nextInt(64);
            double percent = new double[] {0.0D, 0.01D, 0.5D, 2.0D, 5.0D, 12.5D, 50.0D}[random.nextInt(7)];
            long unit = Settlement.cappedUnitPrice(base, 1L, percent);
            assertTrue(unit >= 1L && unit <= base);
            long discount = (base - unit) * quantity;
            assertTrue(discount <= Settlement.sale(base * quantity, percent).fee(),
                    base + " x " + quantity + " at " + percent + "%");
        }
        // The price shown is the capped one, and then only when the Reserve can pay the difference.
        assertEquals(980L, Settlement.buyerUnitPrice(1_000L, 500L, 20L, 2.0D));
        assertEquals(1_000L, Settlement.buyerUnitPrice(1_000L, 500L, 19L, 2.0D));
        assertEquals(1_300L, Settlement.buyerUnitPrice(1_000L, 1_300L, 0L, 2.0D));
    }

    @Test void aBoardSaleAtTheSellersPricePutsTheProceedsInEscrowAndTheFeeInTheReserve() {
        Settlement.Sale sale = Settlement.sale(500L, 2.0D);
        long reserve = reserve();
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(BUYER, SELLER, true, 500L, 500L, sale.fee())));
        assertEquals(500L, balance(BUYER));
        // The seller is paid when they collect; until then the proceeds sit in escrow.
        assertEquals(1_000L, balance(SELLER));
        assertEquals(490L, escrow());
        assertEquals(reserve + 10L, reserve());
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.collect(SELLER, sale.proceeds(), TransactionType.MARKET_PAYOUT)));
        assertEquals(1_490L, balance(SELLER));
        assertEquals(0L, escrow());
        assertTrue(ledger.supplyBalanced());
    }

    @Test void aShopSaleAtTheSellersPricePaysTheSellerAtOnceLessTheFee() {
        long reserve = reserve();
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(BUYER, SELLER, false, 500L, 500L, 10L)));
        assertEquals(500L, balance(BUYER));
        assertEquals(1_490L, balance(SELLER));
        assertEquals(0L, escrow());
        assertEquals(reserve + 10L, reserve());
        assertTrue(ledger.supplyBalanced());
    }

    @Test void aDiscountIsPaidByTheReserveAndNeverByTheSeller() {
        for (boolean board : new boolean[] {true, false}) {
            openAccounts();
            long reserve = reserve();
            // The buyer's price is 400 for goods the seller priced at 500.
            assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(BUYER, SELLER, board, 500L, 400L, 10L)));
            assertEquals(600L, balance(BUYER));
            assertEquals(490L, board ? escrow() : balance(SELLER) - 1_000L);
            // The Reserve paid 100 and took the fee of 10.
            assertEquals(reserve - 90L, reserve());
            assertTrue(ledger.supplyBalanced());
            // The buyer's own rows say what happened: the adjustment in, the seller's price out.
            assertTrue(ledger.history(BUYER, 100).stream().anyMatch(row -> row.type() == TransactionType.PRICE_ADJUSTMENT
                    && row.amount() == 100L && row.sourceAccount().equals("system:reserve")));
            assertTrue(ledger.history(BUYER, 100).stream().anyMatch(row -> row.amount() == 500L && row.type()
                    == (board ? TransactionType.MARKET_PURCHASE : TransactionType.SHOP_PURCHASE)));
        }
    }

    @Test void aSurchargeGoesToTheReserveAndNeverToTheSeller() {
        for (boolean board : new boolean[] {true, false}) {
            openAccounts();
            long reserve = reserve();
            // The buyer's price is 650 for goods the seller priced at 500.
            assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(BUYER, SELLER, board, 500L, 650L, 10L)));
            assertEquals(350L, balance(BUYER));
            assertEquals(490L, board ? escrow() : balance(SELLER) - 1_000L);
            assertEquals(reserve + 160L, reserve());
            assertTrue(ledger.supplyBalanced());
        }
    }

    @Test void aDiscountTheReserveCannotPayRefusesThePlanSoTheCallerSettlesAtTheSellersPrice() {
        // Drain the Reserve to less than the discount.
        assertEquals(LedgerResult.APPLIED, ledger.apply(LedgerPlan.of(SystemAccountId.RESERVE, SystemAccountId.EXCHANGE,
                reserve() - 30L, TransactionType.FEE_EXCHANGE)));
        long buyer = balance(BUYER);
        assertEquals(LedgerResult.RESERVE_SHORT, ledger.apply(Settlement.purchase(BUYER, SELLER, true, 500L, 400L, 10L)));
        assertEquals(buyer, balance(BUYER));
        assertEquals(0L, escrow());
        assertEquals(30L, reserve());
        // The same purchase at the seller's own price goes through.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(BUYER, SELLER, true, 500L, 500L, 10L)));
        assertEquals(buyer - 500L, balance(BUYER));
        assertEquals(490L, escrow());
        assertEquals(40L, reserve());
        assertTrue(ledger.supplyBalanced());
    }

    @Test void aBuyerWhoCannotPayMovesNothingNotEvenTheDiscount() {
        long reserve = reserve();
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, ledger.apply(Settlement.purchase(BUYER, SELLER, true, 5_000L,
                4_900L, 100L)));
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, ledger.apply(Settlement.purchase(BUYER, SELLER, false, 5_000L,
                5_100L, 100L)));
        assertEquals(1_000L, balance(BUYER));
        assertEquals(1_000L, balance(SELLER));
        assertEquals(reserve, reserve());
        assertEquals(0L, escrow());
    }

    @Test void aNewPlayersLockedCreditsCannotBuyFromAnotherPlayerEvenWithASurcharge() {
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        ledger.withdraw(fresh, NeroEconomyCommon.CREDITS, 0L);
        assertEquals(100L, balance(fresh));
        for (boolean board : new boolean[] {true, false}) {
            assertEquals(LedgerResult.FUNDS_LOCKED, ledger.apply(Settlement.purchase(fresh, SELLER, board, 50L, 50L, 1L)));
            assertEquals(LedgerResult.FUNDS_LOCKED, ledger.apply(Settlement.purchase(fresh, SELLER, board, 50L, 60L, 1L)));
        }
        assertEquals(LedgerResult.FUNDS_LOCKED, ledger.apply(Settlement.buyOffer(fresh, 50L, 1L)));
        assertEquals(LedgerResult.FUNDS_LOCKED, ledger.apply(Settlement.escrow(fresh, 50L, TransactionType.AUCTION_BID)));
        assertEquals(LedgerResult.FUNDS_LOCKED, ledger.apply(Settlement.escrow(fresh, 50L, TransactionType.MARKET_ESCROW)));
        // A fee alone is a system counterparty, so a board listing can still be posted.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.playerFee(fresh, 1L, TransactionType.FEE_LISTING)));
        assertEquals(99L, balance(fresh));
        // A discount paid in by the Reserve is spendable, but it does not unlock the grant behind it.
        assertEquals(LedgerResult.FUNDS_LOCKED, ledger.apply(Settlement.purchase(fresh, SELLER, true, 50L, 20L, 1L)));
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.purchase(fresh, SELLER, true, 50L, 0L, 1L)));
        assertEquals(99L, balance(fresh));
        assertTrue(ledger.supplyBalanced());
    }

    @Test void aBuyOfferEscrowsItsCreditsAndPaysTheListingFeeAsOneOperation() {
        long reserve = reserve();
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.buyOffer(BUYER, 640L, 1L)));
        assertEquals(359L, balance(BUYER));
        assertEquals(640L, escrow());
        assertEquals(reserve + 1L, reserve());
        // One credit short of escrow plus fee: neither moves.
        assertEquals(LedgerResult.INSUFFICIENT_FUNDS, ledger.apply(Settlement.buyOffer(BUYER, 359L, 1L)));
        assertEquals(359L, balance(BUYER));
        assertEquals(640L, escrow());
        // A seller fills 10 of 16 at 40 each: the fee comes out of escrow, the rest is their claim.
        Settlement.Sale fill = Settlement.sale(400L, 2.0D);
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.feeFromEscrow(fill.fee(), TransactionType.FEE_MARKET)));
        CreditClaims claims = new CreditClaims();
        assertTrue(claims.add(SELLER, TransactionType.MARKET_PAYOUT, fill.proceeds()));
        long offerEscrow = EscrowLifecycle.fill(640L, 10, 40L);
        // Invariant B: what escrow holds is the offer's remaining escrow plus the uncollected claim.
        assertEquals(offerEscrow + claims.grandTotal(), escrow());
        // The offer is cancelled: its remaining escrow becomes the buyer's claim, and nothing moves.
        assertTrue(claims.add(BUYER, TransactionType.ESCROW_REFUND, EscrowLifecycle.expire(offerEscrow)));
        assertEquals(claims.grandTotal(), escrow());
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.collect(BUYER, claims.settle(BUYER, TransactionType.ESCROW_REFUND),
                TransactionType.ESCROW_REFUND)));
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.collect(SELLER, claims.settle(SELLER, TransactionType.MARKET_PAYOUT),
                TransactionType.MARKET_PAYOUT)));
        assertEquals(0L, escrow());
        assertEquals(0L, claims.grandTotal());
        assertEquals(359L + 240L, balance(BUYER));
        assertEquals(1_000L + 392L, balance(SELLER));
        assertEquals(reserve + 1L + 8L, reserve());
        assertTrue(ledger.supplyBalanced());
    }

    @Test void anAuctionHoldsOneBidInEscrowAndTakesItsFeeFromTheWinningOne() {
        long reserve = reserve();
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.escrow(BUYER, 200L, TransactionType.AUCTION_BID)));
        // Outbid: the new bid goes in, and the old one is owed back to its bidder.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.escrow(OTHER, 300L, TransactionType.AUCTION_BID)));
        CreditClaims claims = new CreditClaims();
        assertTrue(claims.add(BUYER, TransactionType.ESCROW_REFUND, 200L));
        assertEquals(300L + claims.grandTotal(), escrow());
        // The same bidder raising pays only the difference.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.escrow(OTHER, 50L, TransactionType.AUCTION_BID)));
        assertEquals(350L + claims.grandTotal(), escrow());
        // Settlement: 5 % of 350 is 17.5, rounded up to 18.
        Settlement.Sale won = Settlement.sale(350L, 5.0D);
        assertEquals(18L, won.fee());
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.feeFromEscrow(won.fee(), TransactionType.FEE_AUCTION)));
        assertTrue(claims.add(SELLER, TransactionType.AUCTION_PAYOUT, won.proceeds()));
        assertEquals(claims.grandTotal(), escrow());
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.collect(SELLER, 332L, TransactionType.AUCTION_PAYOUT)));
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.collect(BUYER, 200L, TransactionType.ESCROW_REFUND)));
        assertEquals(0L, escrow());
        assertEquals(1_000L, balance(BUYER));
        assertEquals(650L, balance(OTHER));
        assertEquals(1_332L, balance(SELLER));
        assertEquals(reserve + 18L, reserve());
        assertTrue(ledger.supplyBalanced());
    }

    @Test void aSystemJobIsFundedByTheReserveWithinItsDailyBudgetAndRefundedToIt() {
        long reserve = reserve();
        // The default systemJobDailyPayoutCap is 5000.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.systemJobEscrow(4_000L)));
        assertEquals(4_000L, escrow());
        assertEquals(reserve - 4_000L, reserve());
        assertEquals(LedgerResult.BUDGET_SPENT, ledger.apply(Settlement.systemJobEscrow(1_001L)));
        assertEquals(4_000L, escrow());
        // Fulfilled: the payout is the worker's, with no fee. Expired: it goes back to the Reserve.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.collect(OTHER, 1_500L, TransactionType.CONTRACT_PAYOUT)));
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.escrowToReserve(2_500L, TransactionType.ESCROW_REFUND)));
        assertEquals(0L, escrow());
        assertEquals(2_500L, balance(OTHER));
        assertEquals(reserve - 1_500L, reserve());
        // The budget counts what was posted, so the refund did not give any of it back.
        assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.systemJobEscrow(1_000L)));
        assertEquals(LedgerResult.BUDGET_SPENT, ledger.apply(Settlement.systemJobEscrow(1L)));
        // A job the Reserve cannot fund is refused, whatever the budget says.
        EconomySavedData poor = new EconomySavedData();
        poor.seedReserve(10L);
        assertEquals(LedgerResult.RESERVE_SHORT, poor.apply(Settlement.systemJobEscrow(11L)));
        assertEquals(10L, poor.balance(SystemAccountId.RESERVE));
        assertTrue(ledger.supplyBalanced());
    }

    @Test void escrowAlwaysEqualsWhatIsRecordedAsOwedThroughARandomRunOfMarketOperations() {
        Random random = new Random(20_261_003L);
        UUID[] players = {BUYER, SELLER, OTHER};
        CreditClaims claims = new CreditClaims();
        long offers = 0L;   // escrow behind open buy offers
        long jobs = 0L;     // payouts of open jobs
        long bids = 0L;     // standing bids
        for (int step = 0; step < 20_000; step++) {
            UUID actor = players[random.nextInt(players.length)];
            UUID counterparty = players[random.nextInt(players.length)];
            long amount = 1L + random.nextInt(120);
            switch (random.nextInt(11)) {
                case 0 -> {
                    if (ledger.apply(Settlement.buyOffer(actor, amount, 1L)).ok()) offers += amount;
                }
                case 1 -> {
                    // A fill takes part of what the offers hold: fee to the Reserve, the rest a claim.
                    long gross = Math.min(offers, amount);
                    Settlement.Sale sale = Settlement.sale(gross, 2.0D);
                    if (gross > 0L && ledger.apply(Settlement.feeFromEscrow(sale.fee(), TransactionType.FEE_MARKET)).ok()) {
                        offers -= gross;
                        if (sale.proceeds() > 0L) assertTrue(claims.add(counterparty, TransactionType.MARKET_PAYOUT, sale.proceeds()));
                    }
                }
                case 2 -> {
                    // An offer expires or is cancelled: its escrow becomes a refund claim.
                    long refund = Math.min(offers, amount);
                    if (refund > 0L && claims.add(actor, TransactionType.ESCROW_REFUND, refund)) offers -= refund;
                }
                case 3 -> {
                    long base = amount;
                    long total = Math.max(1L, base + random.nextInt(41) - 20);
                    Settlement.Sale sale = Settlement.sale(base, 2.0D);
                    if (!actor.equals(counterparty)
                            && ledger.apply(Settlement.purchase(actor, counterparty, true, base, total, sale.fee())).ok()
                            && sale.proceeds() > 0L)
                        assertTrue(claims.add(counterparty, TransactionType.MARKET_PAYOUT, sale.proceeds()));
                }
                case 4 -> {
                    if (!actor.equals(counterparty)) ledger.apply(Settlement.purchase(actor, counterparty, false, amount,
                            Math.max(1L, amount + random.nextInt(41) - 20), Settlement.sale(amount, 2.0D).fee()));
                }
                case 5 -> {
                    for (TransactionType reason : List.copyOf(claims.buckets(actor).keySet())) {
                        long owed = claims.buckets(actor).get(reason);
                        if (ledger.apply(Settlement.collect(actor, owed, reason)).ok()) claims.settle(actor, reason);
                    }
                }
                case 6 -> {
                    if (ledger.apply(Settlement.escrow(actor, amount, TransactionType.MARKET_ESCROW)).ok()) jobs += amount;
                }
                case 7 -> {
                    // A job is fulfilled (payout to the worker's claim) or expires (back to the issuer's).
                    long payout = Math.min(jobs, amount);
                    if (payout > 0L && claims.add(counterparty, random.nextBoolean() ? TransactionType.CONTRACT_PAYOUT
                            : TransactionType.ESCROW_REFUND, payout)) jobs -= payout;
                }
                case 8 -> {
                    if (ledger.apply(Settlement.escrow(actor, amount, TransactionType.AUCTION_BID)).ok()) bids += amount;
                }
                case 9 -> {
                    // An auction settles: the fee leaves escrow, the rest is the seller's claim.
                    long winning = Math.min(bids, amount);
                    Settlement.Sale sale = Settlement.sale(winning, 5.0D);
                    if (winning > 0L && ledger.apply(Settlement.feeFromEscrow(sale.fee(), TransactionType.FEE_AUCTION)).ok()) {
                        bids -= winning;
                        if (sale.proceeds() > 0L) assertTrue(claims.add(counterparty, TransactionType.AUCTION_PAYOUT, sale.proceeds()));
                    }
                }
                default -> {
                    // A system job is posted and, as often as not, expires straight back.
                    if (ledger.apply(Settlement.systemJobEscrow(amount)).ok()) {
                        if (random.nextBoolean()) jobs += amount;
                        else assertEquals(LedgerResult.APPLIED, ledger.apply(Settlement.escrowToReserve(amount, TransactionType.ESCROW_REFUND)));
                    }
                }
            }
            long owed = offers + jobs + bids + claims.grandTotal();
            assertEquals(owed, escrow(), "step " + step);
            assertTrue(ledger.supplyReport(owed).healthy(), "step " + step);
        }
        assertFalse(ledger.history(BUYER, 10).isEmpty());
    }
}
