package za.co.neroland.neroeconomy.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.market.FillResult;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.market.PurchaseResult;

/** The app's words come from the mod's own lang file, resolved on the server. */
class LinkTextTest {
    @Test void aKeyResolvesAgainstTheModsOwnLangFileAndArgumentsAreFormattedIn() {
        assertEquals("A player", LinkText.get("link.neroeconomy.a_player"));
        assertEquals("Moved 7 items into your inventory.", LinkText.get("link.neroeconomy.withdrew", 7));
        assertEquals("no.such.key", LinkText.get("no.such.key"));
        assertFalse(LinkText.has("no.such.key"));
    }

    @Test void aRefusalFragmentBecomesOneSentence() {
        assertEquals("You do not have enough credits.", LinkText.sentence("message.neroeconomy.order.insufficient_funds"));
        // Already a sentence: left as it is.
        assertEquals(LinkText.get("message.neroeconomy.invest.too_fast"), LinkText.sentence("message.neroeconomy.invest.too_fast"));
    }

    @Test void noAppMessageSendsAPlayerToATerminalTheHubHasReplaced() {
        List<String> messages = new ArrayList<>();
        for (PurchaseResult result : PurchaseResult.values()) messages.add(LinkPayloads.purchaseMessage(result));
        for (FillResult result : FillResult.values()) messages.add(LinkPayloads.fillMessage(result));
        for (PostResult result : PostResult.values()) messages.add(LinkPayloads.postMessage(result));
        messages.add(LinkPayloads.SELL_NEEDS_BUY_OFFER);
        messages.add(LinkActions.reason("message.neroeconomy.offer.vault_full"));
        messages.add(LinkActions.reason("command.neroeconomy.cancel.claims_full"));
        for (String message : messages) {
            assertFalse(message.contains("Trading Terminal"), message);
            assertFalse(message.contains("Bank Terminal"), message);
            assertFalse(message.startsWith("link.neroeconomy."), "unresolved: " + message);
            assertTrue(message.endsWith("."), message);
        }
    }

    @Test void theGameReasonsAreReusedAndTheStaleOnesAreTheDuplicateSentence() {
        assertEquals(LinkPayloads.DUPLICATE_REQUEST, LinkActions.reason("message.neroeconomy.order.stale_request"));
        assertEquals(LinkPayloads.ECONOMY_FROZEN, LinkActions.reason("message.neroeconomy.frozen"));
        assertEquals(LinkPayloads.MARKET_CLOSED, LinkActions.reason("message.neroeconomy.market_closed"));
        assertEquals("That order is no longer open.", LinkActions.reason("message.neroeconomy.order.not_found"));
    }
}
