package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.market.FillResult;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.market.PurchaseResult;

/** How the trade action reads what a client sends, and what it says back when it refuses. */
class LinkPayloadsRequestTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void requestIdMustBePresentAndACanonicalUuid() {
        UUID id = UUID.fromString("3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b");
        assertEquals(id, LinkPayloads.requestId(json("{\"requestId\":\"3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b\"}")));
        assertEquals(id, LinkPayloads.requestId(json("{\"requestId\":\"3F2B8C1E-5A4D-4E6F-9B7A-1C2D3E4F5A6B\"}")));
        assertNull(LinkPayloads.requestId(json("{}")));
        assertNull(LinkPayloads.requestId(null));
        assertNull(LinkPayloads.requestId(json("{\"requestId\":\"\"}")));
        assertNull(LinkPayloads.requestId(json("{\"requestId\":null}")));
        assertNull(LinkPayloads.requestId(json("{\"requestId\":\"not-a-uuid\"}")));
        assertNull(LinkPayloads.requestId(json("{\"requestId\":12345}")));
        assertNull(LinkPayloads.requestId(json("{\"requestId\":{\"id\":1}}")));
        // UUID.fromString accepts short groups; an id like this is malformed, not a request id.
        assertNull(LinkPayloads.requestId(json("{\"requestId\":\"1-1-1-1-1\"}")));
        assertNull(LinkPayloads.requestId(json("{\"requestId\":\"3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b \"}")));
        assertEquals("requestId is required", LinkPayloads.REQUEST_ID_REQUIRED);
    }

    @Test void quantityDefaultsToOneAndIsBoundedToOneThroughSixtyFour() {
        assertEquals(1, LinkPayloads.quantity(json("{}")));
        assertEquals(1, LinkPayloads.quantity(json("{\"qty\":null}")));
        assertEquals(1, LinkPayloads.quantity(json("{\"qty\":1}")));
        assertEquals(64, LinkPayloads.quantity(json("{\"qty\":64}")));
        assertEquals(16, LinkPayloads.quantity(json("{\"qty\":16.0}")));
        assertEquals(8, LinkPayloads.quantity(json("{\"qty\":\"8\"}")));
        for (String bad : List.of("0", "-1", "65", "1000000000000", "2.5", "\"many\"", "true", "[2]", "{}", "1e400"))
            assertEquals(-1, LinkPayloads.quantity(json("{\"qty\":" + bad + "}")), bad);
        // The refusal states the bound.
        assertTrue(LinkPayloads.QUANTITY_OUT_OF_RANGE.contains("1") && LinkPayloads.QUANTITY_OUT_OF_RANGE.contains("64"));
    }

    @Test void wholeNumbersFallBackOnlyWhenAbsent() {
        assertEquals(0L, LinkPayloads.wholeNumber(json("{}"), "unitPrice", 0L));
        assertEquals(0L, LinkPayloads.wholeNumber(json("{\"unitPrice\":null}"), "unitPrice", 0L));
        assertEquals(25L, LinkPayloads.wholeNumber(json("{\"unitPrice\":25}"), "unitPrice", 0L));
        assertEquals(-1L, LinkPayloads.wholeNumber(json("{\"unitPrice\":-25}"), "unitPrice", 0L));
        assertEquals(-1L, LinkPayloads.wholeNumber(json("{\"unitPrice\":2.5}"), "unitPrice", 0L));
        assertEquals(-1L, LinkPayloads.wholeNumber(json("{\"unitPrice\":\"cheap\"}"), "unitPrice", 0L));
        assertEquals(-1L, LinkPayloads.wholeNumber(json("{\"unitPrice\":1e30}"), "unitPrice", 0L));
    }

    @Test void textAndUuidMembersTolerateAnyShape() {
        JsonObject params = new JsonObject();
        params.add("op", JsonNull.INSTANCE);
        assertEquals("", LinkPayloads.text(params, "op"));
        assertEquals("", LinkPayloads.text(params, "missing"));
        assertEquals("", LinkPayloads.text(null, "op"));
        assertEquals("sell", LinkPayloads.text(json("{\"op\":\"sell\"}"), "op"));
        assertNull(LinkPayloads.uuid(json("{\"listingId\":\"mkt-starsteel\"}"), "listingId"));
    }

    @Test void pagingParametersAreClampedAndUnreadableOnesFallBack() {
        assertEquals(0, LinkPayloads.bounded(null, 0, 0, 100));
        assertEquals(7, LinkPayloads.bounded("7", 0, 0, 100));
        assertEquals(7, LinkPayloads.bounded(" 7 ", 0, 0, 100));
        assertEquals(100, LinkPayloads.bounded("5000", 0, 0, 100));
        assertEquals(0, LinkPayloads.bounded("-1", 25, 0, 100));
        assertEquals(25, LinkPayloads.bounded("cursor-100469", 25, 0, 100));
        assertEquals(25, LinkPayloads.bounded("", 25, 0, 100));
    }

    @Test void everyRefusalIsAPlainSentenceWithNothingToFillIn() {
        List<String> messages = new ArrayList<>();
        for (PurchaseResult result : PurchaseResult.values()) messages.add(LinkPayloads.purchaseMessage(result));
        for (FillResult result : FillResult.values()) messages.add(LinkPayloads.fillMessage(result));
        for (PostResult result : PostResult.values()) messages.add(LinkPayloads.postMessage(result));
        messages.addAll(List.of(LinkPayloads.DUPLICATE_REQUEST, LinkPayloads.QUANTITY_OUT_OF_RANGE,
                LinkPayloads.TOO_MANY_REQUESTS, LinkPayloads.TRADE_DISABLED, LinkPayloads.UNKNOWN_OPERATION,
                LinkPayloads.LISTING_ID_REQUIRED, LinkPayloads.SELL_NEEDS_BUY_OFFER, LinkPayloads.SELL_NEEDS_PLAYER,
                LinkPayloads.BUY_NEEDS_LISTING));
        for (String message : messages) {
            // No format placeholder and no enum constant: the app shows the string exactly as sent.
            assertTrue(message.matches("[A-Za-z0-9 ,.:'-]{10,160}"), message);
            assertTrue(message.endsWith("."), message);
            assertNotEquals(message.toUpperCase(java.util.Locale.ROOT), message);
        }
    }

    @Test void aReplayedRequestIsReportedAsADuplicateByEveryStore() {
        assertTrue(LinkPayloads.DUPLICATE_REQUEST.toLowerCase(java.util.Locale.ROOT).startsWith("duplicate request"));
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, LinkPayloads.purchaseMessage(PurchaseResult.STALE_REQUEST));
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, LinkPayloads.fillMessage(FillResult.STALE_REQUEST));
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, LinkPayloads.postMessage(PostResult.STALE_REQUEST));
        // Selling with nothing to fill says what selling from the app needs.
        assertTrue(LinkPayloads.SELL_NEEDS_BUY_OFFER.contains("buy offer"));
    }
}
