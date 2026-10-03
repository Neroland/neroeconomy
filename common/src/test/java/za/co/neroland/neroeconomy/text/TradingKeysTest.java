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
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.book.BookResult;
import za.co.neroland.neroeconomy.book.OrderSource;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.market.FillResult;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.obligation.FulfillResult;
import za.co.neroland.neroeconomy.offers.OfferResult;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleStatus;

/**
 * Everything the order book, offers and auto-trade rules can tell a player has wording: every
 * result, every status and every line the trading commands print.
 */
class TradingKeysTest {
    @Test void everyResultStatusAndCommandLineHasAnEnglishString() throws IOException {
        JsonObject lang;
        try (InputStream stream = TradingKeysTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            // The lang file reaches the test classpath through the loader node's resources.
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>();
        for (BookResult value : BookResult.values()) keys.add(value.translationKey());
        for (OfferResult value : OfferResult.values()) keys.add(value.translationKey());
        for (RuleResult value : RuleResult.values()) keys.add(value.translationKey());
        for (RuleStatus value : RuleStatus.values()) keys.add(value.translationKey());
        for (PostResult value : PostResult.values()) keys.add(value.translationKey());
        for (FillResult value : FillResult.values()) keys.add(value.translationKey());
        for (FulfillResult value : FulfillResult.values()) keys.add(value.translationKey());
        for (OrderSource value : OrderSource.values())
            keys.add("command.neroeconomy.order.source." + value.name().toLowerCase(Locale.ROOT));
        keys.add(GoodsSource.REFUSED_KEY);
        keys.addAll(List.of("message.neroeconomy.order.refused", "message.neroeconomy.order.sold_now",
                "message.neroeconomy.order.sell_listed", "message.neroeconomy.order.listing_fee",
                "message.neroeconomy.order.bought_now", "message.neroeconomy.order.buy_listed",
                "message.neroeconomy.order.refunded", "command.neroeconomy.trade.not_now",
                "command.neroeconomy.trade.too_fast", "command.neroeconomy.trade.id_ambiguous",
                "command.neroeconomy.trade.id_unknown", "command.neroeconomy.trade.modified",
                "command.neroeconomy.trade.items", "command.neroeconomy.trade.credits",
                "command.neroeconomy.trade.nothing", "command.neroeconomy.order.rule_owned",
                "command.neroeconomy.order.mine.empty", "command.neroeconomy.order.mine.header",
                "command.neroeconomy.order.mine.sell", "command.neroeconomy.order.mine.buy",
                "command.neroeconomy.offer.refused", "command.neroeconomy.offer.sent",
                "command.neroeconomy.offer.sent_ask", "command.neroeconomy.offer.received",
                "command.neroeconomy.offer.list.muted", "command.neroeconomy.offer.list.empty",
                "command.neroeconomy.offer.list.header", "command.neroeconomy.offer.list.incoming",
                "command.neroeconomy.offer.list.outgoing", "command.neroeconomy.offer.accepted",
                "command.neroeconomy.offer.accepted_sender", "command.neroeconomy.offer.declined",
                "command.neroeconomy.offer.declined_sender", "command.neroeconomy.offer.cancelled",
                "command.neroeconomy.offer.cancelled_claim", "command.neroeconomy.offer.muted",
                "command.neroeconomy.offer.unmuted", "command.neroeconomy.rule.refused",
                "command.neroeconomy.rule.added_buy", "command.neroeconomy.rule.added_sell",
                "command.neroeconomy.rule.list.paused_all", "command.neroeconomy.rule.list.empty",
                "command.neroeconomy.rule.list.header", "command.neroeconomy.rule.list.buy",
                "command.neroeconomy.rule.list.sell", "command.neroeconomy.rule.paused",
                "command.neroeconomy.rule.resumed", "command.neroeconomy.rule.deleted",
                "command.neroeconomy.rule.deleted_claim", "command.neroeconomy.rule.paused_all",
                "command.neroeconomy.rule.resumed_all"));
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }

    @Test void aFrozenEconomyIsReportedInTheSameWordsEverywhere() {
        assertEquals(EconomyHealth.FROZEN_KEY, BookResult.FROZEN.translationKey());
        assertEquals(EconomyHealth.FROZEN_KEY, OfferResult.FROZEN.translationKey());
        assertEquals(EconomyHealth.FROZEN_KEY, RuleResult.FROZEN.translationKey());
        assertEquals("message.neroeconomy.order.price_moved", BookResult.PRICE_MOVED.translationKey());
        assertEquals("message.neroeconomy.offer.not_accepting", OfferResult.NOT_ACCEPTING.translationKey());
        assertEquals("message.neroeconomy.rule.status.price_not_met", RuleStatus.PRICE_NOT_MET.translationKey());
    }
}
