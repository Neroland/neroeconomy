package za.co.neroland.neroeconomy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.obligation.FulfillResult;

/** Every result a player can be shown has wording, and the wire names never move. */
class ResultKeysTest {
    @Test void purchaseResultsAreOnlyEverAppended() {
        // Link clients receive these names, so the order and spelling of the first ten are frozen.
        PurchaseResult[] values = PurchaseResult.values();
        assertEquals(List.of("SUCCESS", "STALE_REQUEST", "NOT_FOUND", "OWN_LISTING", "INVALID_QUANTITY",
                "OUT_OF_STOCK", "PRICE_MOVED", "INSUFFICIENT_FUNDS", "INVENTORY_FULL", "INTERNAL_ERROR",
                "ITEM_CHANGED", "CLAIMS_FULL", "FUNDS_LOCKED", "FROZEN", "MARKET_CLOSED"),
                java.util.Arrays.stream(values).map(Enum::name).toList());
    }

    @Test void translationKeysFollowTheEnumName() {
        assertEquals("gui.neroeconomy.purchase_result.item_changed", PurchaseResult.ITEM_CHANGED.translationKey());
        assertEquals("gui.neroeconomy.fill_result.modified_item", FillResult.MODIFIED_ITEM.translationKey());
        assertEquals("command.neroeconomy.cancel.not_cancellable", CancelResult.NOT_CANCELLABLE.translationKey());
        assertEquals("command.neroeconomy.fulfill.modified_items", FulfillResult.MODIFIED_ITEMS.translationKey());
        // Every store refuses a frozen economy with the same one message.
        assertEquals(EconomyHealth.FROZEN_KEY, PurchaseResult.FROZEN.translationKey());
        assertEquals(EconomyHealth.FROZEN_KEY, FillResult.FROZEN.translationKey());
        assertEquals(EconomyHealth.FROZEN_KEY, PostResult.FROZEN.translationKey());
        assertEquals(EconomyHealth.FROZEN_KEY, CancelResult.FROZEN.translationKey());
        assertEquals(EconomyHealth.FROZEN_KEY, FulfillResult.FROZEN.translationKey());
    }

    @Test void everyResultHasAnEnglishString() throws IOException {
        JsonObject lang;
        try (InputStream stream = ResultKeysTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            // The lang file reaches the test classpath through the loader node's resources.
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>();
        for (PurchaseResult value : PurchaseResult.values()) keys.add(value.translationKey());
        for (FillResult value : FillResult.values()) keys.add(value.translationKey());
        for (CancelResult value : CancelResult.values()) keys.add(value.translationKey());
        for (FulfillResult value : FulfillResult.values()) keys.add(value.translationKey());
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }
}
