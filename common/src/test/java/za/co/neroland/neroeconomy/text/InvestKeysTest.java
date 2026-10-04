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

import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeViews;
import za.co.neroland.neroeconomy.exchange.IndexState;
import za.co.neroland.neroeconomy.exchange.TradeResult;

/**
 * Everything the exchange's trading rules can tell a player has wording: every result, every line
 * the invest commands print, the risk labels and the operator's Invariant C line.
 */
class InvestKeysTest {
    private static JsonObject lang() throws IOException {
        try (InputStream stream = InvestKeysTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            // The lang file reaches the test classpath through the loader node's resources.
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void everyResultRiskLabelAndCommandLineHasAnEnglishString() throws IOException {
        JsonObject lang = lang();
        List<String> keys = new ArrayList<>();
        for (TradeResult value : TradeResult.values()) keys.add(value.translationKey());
        for (BondTerm term : BondTerm.values()) {
            keys.add(term.translationKey());
            keys.add(term.descriptionKey());
        }
        keys.addAll(List.of(ExchangeViews.RISK_STEADY, ExchangeViews.RISK_RISKY, ExchangeViews.RISK_INDEX,
                ExchangeViews.RISK_BOND, IndexState.NAME_KEY, IndexState.DESCRIPTION_KEY));
        String command = "command.neroeconomy.invest.";
        for (String line : List.of("practice.line", "practice.balance", "practice.reset", "list.header", "list.row",
                "list.row_halted", "list.delisted", "list.bond", "dividends_scaled", "bought", "hold", "sellable",
                "sold_gain", "sold_loss", "sold_reduced", "portfolio.empty", "portfolio.header", "portfolio.holding",
                "portfolio.waiting", "portfolio.bond", "portfolio.bond_matured", "portfolio.limit", "portfolio.dividend",
                "bond.unknown_term", "bond.bought", "bond.paid_out", "bond.redeemed_early")) keys.add(command + line);
        keys.addAll(List.of("command.neroeconomy.supply.exchange_held", "command.neroeconomy.supply.check_exchange",
                "command.neroeconomy.trade.id_ambiguous", "command.neroeconomy.player_only"));
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }

    @Test void theReducedGainMessagesSayWhatCanBePaidAndOfWhat() throws IOException {
        JsonObject lang = lang();
        for (TradeResult result : List.of(TradeResult.GAIN_REDUCED, TradeResult.GAIN_CHANGED)) {
            String text = lang.get(result.translationKey()).getAsString();
            assertTrue(text.contains("%1$s") && text.contains("%2$s"), result.name());
        }
        assertEquals("The exchange can pay only %1$s of your %2$s gain today. Sell fewer shares, wait for tomorrow, "
                + "or accept the lower amount.", lang.get(TradeResult.GAIN_REDUCED.translationKey()).getAsString());
        assertEquals("Savings Bonds are sold out for today. Try again tomorrow, or buy a smaller one.",
                lang.get(TradeResult.BONDS_SOLD_OUT.translationKey()).getAsString());
        assertTrue(lang.get(TradeResult.SETTLING.translationKey()).getAsString()
                .startsWith("Wait for your recent purchase to settle"));
        // No other result takes an argument, so a caller may always pass the two numbers.
        for (TradeResult result : TradeResult.values()) {
            if (result == TradeResult.GAIN_REDUCED || result == TradeResult.GAIN_CHANGED) continue;
            assertTrue(!lang.get(result.translationKey()).getAsString().contains("%"), result.name());
        }
    }

    @Test void aFrozenEconomyIsReportedInTheSameWordsAndTheIndexHasOneId() {
        assertEquals(EconomyHealth.FROZEN_KEY, TradeResult.FROZEN.translationKey());
        assertEquals("message.neroeconomy.invest.gain_reduced", TradeResult.GAIN_REDUCED.translationKey());
        assertEquals(ExchangeApi.INDEX_ID, IndexState.ID);
    }
}
