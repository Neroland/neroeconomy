package za.co.neroland.neroeconomy.text;

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

import za.co.neroland.neroeconomy.direct.TradeSession;
import za.co.neroland.neroeconomy.direct.TradeSwap;
import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Everything a direct trade can tell a player has wording: every reason a trade ends, every reason
 * a swap is refused (for the side it is about and for the other side), every line of the request
 * commands and every label and tooltip of the trade window.
 */
class DirectTradeKeysTest {
    @Test void everyReasonMessageLabelAndTooltipHasAnEnglishString() throws IOException {
        JsonObject lang;
        try (InputStream stream = DirectTradeKeysTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            // The lang file reaches the test classpath through the loader node's resources.
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>();
        for (TradeSession.CancelReason value : TradeSession.CancelReason.values()) keys.add(value.translationKey());
        for (TradeSwap.Result value : TradeSwap.Result.values())
            for (TradeSession.Side viewer : TradeSession.Side.values()) keys.add(value.translationKey(viewer));
        for (String word : List.of("accept", "accept.tooltip", "busy", "choices", "decline", "decline.tooltip",
                "declined", "declined_requester", "expired", "failed", "gone", "none", "not_accepting", "offline",
                "pending", "prompt", "self", "sent", "too_far")) keys.add("command.neroeconomy.trade.request." + word);
        for (String word : List.of("cancel", "cancel.tooltip", "confirm", "confirm.tooltip", "countdown", "credits",
                "credits.tooltip", "credits_hint", "frozen", "get", "get_taxed", "give", "set", "set.tooltip",
                "their_credits", "them", "they_give", "tooltip.count", "tooltip.durability", "tooltip.enchantments",
                "tooltip.id", "tooltip.renamed", "unconfirm", "unconfirm.tooltip", "you", "you_give"))
            keys.add("gui.neroeconomy.direct_trade." + word);
        for (String word : List.of("cancelled", "changed", "credits.invalid", "credits.too_many", "done", "done.tax",
                "locked", "swap.refused", "to_vault")) keys.add("message.neroeconomy.direct_trade." + word);
        keys.addAll(List.of("container.neroeconomy.direct_trade", "command.neroeconomy.trade.not_now",
                "command.neroeconomy.trade.too_fast", "command.neroeconomy.offer.muted",
                "command.neroeconomy.offer.unmuted", "gui.neroeconomy.wallet"));
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }

    @Test void aRefusalIsWordedForTheSideReadingIt() {
        assertEquals(EconomyHealth.FROZEN_KEY, TradeSwap.Result.FROZEN.translationKey(TradeSession.Side.FIRST));
        assertEquals("message.neroeconomy.direct_trade.swap.short.you",
                TradeSwap.Result.SECOND_SHORT.translationKey(TradeSession.Side.SECOND));
        assertEquals("message.neroeconomy.direct_trade.swap.short.them",
                TradeSwap.Result.SECOND_SHORT.translationKey(TradeSession.Side.FIRST));
        assertEquals("message.neroeconomy.direct_trade.swap.escrow_changed",
                TradeSwap.Result.ESCROW_CHANGED.translationKey(TradeSession.Side.FIRST));
    }
}
