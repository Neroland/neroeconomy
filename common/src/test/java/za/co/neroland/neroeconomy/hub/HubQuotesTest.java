package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.economy.Fees;

/** Quotes are exact, say which line to show, and refuse rather than wrap. */
class HubQuotesTest {
    @Test void aPurchaseCostsUnitTimesQuantityAndSaysWhereTheGoodsGo() {
        HubQuotes.Quote inventory = HubQuotes.buy(12, 10, 64, false, 5, 5.0, true, true);
        assertTrue(inventory.ok());
        assertEquals(HubQuotes.BUY_INVENTORY, inventory.key());
        assertEquals(60L, inventory.total());
        assertEquals(Fees.fee(50L, 5.0), inventory.fee());
        assertFalse(inventory.toVault());
        HubQuotes.Quote vault = HubQuotes.buy(12, 10, 64, false, 5, 5.0, false, true);
        assertEquals(HubQuotes.BUY_VAULT, vault.key());
        assertTrue(vault.toVault());
    }

    @Test void purchaseRefusals() {
        assertEquals(HubQuotes.INVALID, HubQuotes.buy(10, 10, 64, false, 0, 5.0, true, true).key());
        assertEquals(HubQuotes.INVALID, HubQuotes.buy(10, 10, 9999, false, BookSection.MAX_ORDER_QUANTITY + 1L, 5.0,
                true, true).key());
        assertEquals(HubQuotes.OWN_LISTING, HubQuotes.buy(10, 10, 64, true, 1, 5.0, true, true).key());
        assertEquals(HubQuotes.OUT_OF_STOCK, HubQuotes.buy(10, 10, 3, false, 4, 5.0, true, true).key());
        assertEquals(HubQuotes.NO_ROOM, HubQuotes.buy(10, 10, 64, false, 4, 5.0, false, false).key());
        HubQuotes.Quote huge = HubQuotes.buy(Long.MAX_VALUE / 2, 1, 64, false, 64, 5.0, true, true);
        assertFalse(huge.ok());
        assertEquals(HubQuotes.TOO_LARGE, huge.key());
        assertEquals(0L, huge.total());
    }

    @Test void aSaleShowsTheFeeTheListingFeeAndWhatTheSellerGets() {
        HubQuotes.Quote sale = HubQuotes.sell(4, 25, 10, true, 3, 10.0);
        assertTrue(sale.ok());
        assertEquals(HubQuotes.SELL, sale.key());
        assertEquals(100L, sale.total());
        assertEquals(Fees.fee(100L, 10.0), sale.fee());
        assertEquals(100L - sale.fee(), sale.net());
        assertEquals(3L, sale.listingFee());
        assertEquals(HubQuotes.UNTRADEABLE, HubQuotes.sell(4, 25, 10, false, 3, 10.0).key());
        assertEquals(HubQuotes.NOT_HOLDING, HubQuotes.sell(11, 25, 10, true, 3, 10.0).key());
        assertEquals(HubQuotes.INVALID, HubQuotes.sell(4, 0, 10, true, 3, 10.0).key());
        assertEquals(HubQuotes.TOO_LARGE, HubQuotes.sell(64, Long.MAX_VALUE / 2, 64, true, 3, 10.0).key());
    }

    @Test void aBuyOrderHoldsTheEscrowAndChargesTheListingFeeOnTop() {
        HubQuotes.Quote bid = HubQuotes.bid(8, 7, true, true, 5);
        assertTrue(bid.ok());
        assertEquals(HubQuotes.BID, bid.key());
        assertEquals(56L, bid.net());
        assertEquals(5L, bid.listingFee());
        assertEquals(61L, bid.total());
        assertEquals(0L, HubQuotes.bid(8, 7, true, true, -5).listingFee());
        assertEquals(HubQuotes.UNKNOWN_ITEM, HubQuotes.bid(8, 7, false, true, 5).key());
        assertEquals(HubQuotes.UNTRADEABLE, HubQuotes.bid(8, 7, true, false, 5).key());
        assertEquals(HubQuotes.INVALID, HubQuotes.bid(0, 7, true, true, 5).key());
        assertEquals(HubQuotes.TOO_LARGE, HubQuotes.bid(2, Long.MAX_VALUE, true, true, 5).key());
        assertEquals(HubQuotes.TOO_LARGE, HubQuotes.bid(1, Long.MAX_VALUE, true, true, 5).key());
    }

    @Test void everyQuoteKeyIsListedOnceAndLooksLikeAHubKey() {
        assertEquals(12, HubQuotes.KEYS.size());
        assertEquals(HubQuotes.KEYS.size(), new java.util.HashSet<>(HubQuotes.KEYS).size());
        for (String key : HubQuotes.KEYS) assertTrue(key.startsWith("gui.neroeconomy.hub.quote."), key);
    }
}
