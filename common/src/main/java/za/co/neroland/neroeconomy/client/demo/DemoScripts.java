package za.co.neroland.neroeconomy.client.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;
import za.co.neroland.neroeconomy.hub.HubTab;

import static za.co.neroland.neroeconomy.client.demo.DemoAction.click;
import static za.co.neroland.neroeconomy.client.demo.DemoAction.press;
import static za.co.neroland.neroeconomy.client.demo.DemoAction.type;
import static za.co.neroland.neroeconomy.client.demo.DemoAction.typeText;
import static za.co.neroland.neroeconomy.client.demo.DemoStep.at;
import static za.co.neroland.neroeconomy.client.demo.DemoStep.on;

/**
 * The walkthrough of every tab: which sample page each step shows, what it does to the view and
 * what it points at. The words are lang keys {@code gui.neroeconomy.hub.help.<tab>.<n>}; the sample
 * pages come from {@link DemoPages}; the places pointed at from {@link DemoTargets}. Pure Java: data
 * in code, nothing read from anywhere.
 */
final class DemoScripts {
    private DemoScripts() { }

    static DemoScript of(HubTab tab) {
        return switch (tab) {
            case WALLET -> wallet();
            case VAULT -> vault();
            case MARKET -> market();
            case ORDERS -> orders();
            case INVEST -> invest();
            case TRADE -> trade();
            case JOBS -> jobs();
            case AUCTIONS -> auctions();
            case SHOP -> shop();
            case ADMIN -> admin();
        };
    }

    private static DemoScript script(HubTab tab, List<HubPage> pages, DemoStep... steps) {
        return new DemoScript(tab, pages, List.of(steps), List.of());
    }

    /** A copy of {@code page} with one more header number. */
    static HubPage with(HubPage page, String name, long value) {
        Map<String, Long> numbers = new LinkedHashMap<>(page.numbers());
        numbers.put(name, value);
        return new HubPage(page.containerId(), page.revision(), page.tab(), page.page(), page.pageCount(),
                new ArrayList<>(page.rows()), numbers, page.keys(), page.series());
    }

    // --- wallet ------------------------------------------------------------------------------

    private static DemoScript wallet() {
        return script(HubTab.WALLET, List.of(DemoPages.wallet()),
                at(DemoTargets.Wallet.BALANCE),
                at(DemoTargets.Wallet.COLLECT),
                at(DemoTargets.Wallet.RECIPIENT, typeText(DemoTargets.Wallet.RECIPIENT, DemoPages.NAME_A)),
                at(DemoTargets.Wallet.AMOUNT, type(DemoTargets.Wallet.AMOUNT, "25")),
                at(DemoTargets.Wallet.CONFIRM, press(DemoTargets.Wallet.PAY)),
                at(DemoTargets.Wallet.HISTORY),
                at(DemoTargets.Wallet.LOGGING));
    }

    // --- vault -------------------------------------------------------------------------------

    private static DemoScript vault() {
        return script(HubTab.VAULT, List.of(with(DemoPages.vault(), HubPages.AT_BANK, 1L)),
                at(DemoTargets.Vault.USED),
                at(DemoTargets.Vault.LIST),
                at(DemoTargets.Vault.MODIFIED),
                at(DemoTargets.Vault.OPEN),
                at(DemoTargets.Vault.COLLECT));
    }

    // --- market ------------------------------------------------------------------------------

    /** Search, pick, quantity, the quote with its fee and where the goods go, then the two-press confirm. */
    private static DemoScript market() {
        return script(HubTab.MARKET, DemoPages.market(),
                at(DemoTargets.Market.SEARCH, typeText(DemoTargets.Market.SEARCH, DemoPages.SEARCH)),
                on(1, DemoTargets.Market.LIST),
                at(DemoTargets.Market.FIRST_ROW, click(20, 42)),
                at(DemoTargets.Market.QUANTITY, type(DemoTargets.Market.QUANTITY, "16")),
                at(DemoTargets.Market.QUOTE),
                on(2, DemoTargets.Market.QUOTE_LINES, press(DemoTargets.Market.QUOTE)),
                at(DemoTargets.Market.CONFIRM_LINE, press(DemoTargets.Market.CONFIRM)),
                at(DemoTargets.Market.MODES));
    }

    // --- my orders ---------------------------------------------------------------------------

    /** Cancel an order (two presses), pause rules, and a new auto-trade rule. */
    private static DemoScript orders() {
        return script(HubTab.ORDERS, List.of(DemoPages.orders()),
                at(DemoTargets.Orders.LIST),
                at(DemoTargets.Orders.PRIMARY, click(20, 36)),
                at(DemoTargets.Orders.CONFIRM_LINE, press(DemoTargets.Orders.PRIMARY)),
                at(DemoTargets.Orders.PAUSE_ALL, click(20, 96)),
                at(DemoTargets.Orders.NEW_RULE),
                at(DemoTargets.Orders.TARGET, press(DemoTargets.Orders.NEW_RULE),
                        typeText(DemoTargets.Orders.TARGET, DemoPages.RULE_ITEM)),
                at(DemoTargets.Orders.NUMBERS, type(DemoTargets.Orders.LIMIT, "4"),
                        type(DemoTargets.Orders.PER_DAY, "32"), type(DemoTargets.Orders.TOTAL, "320")),
                at(DemoTargets.Orders.FORM_CONFIRM, press(DemoTargets.Orders.CREATE)));
    }

    // --- invest ------------------------------------------------------------------------------

    /** Pick, How much, the quote and the week strip, Confirm, then practice mode. */
    private static DemoScript invest() {
        return script(HubTab.INVEST, DemoPages.invest(),
                at(DemoTargets.Invest.LIST),
                on(1, DemoTargets.Invest.CHART, click(20, 52)),
                at(DemoTargets.Invest.HOW_MUCH),
                at(DemoTargets.Invest.CREDITS, press(DemoTargets.Invest.HOW_MUCH),
                        type(DemoTargets.Invest.CREDITS, "100")),
                on(2, DemoTargets.Invest.QUOTE_LINES, press(DemoTargets.Invest.QUOTE)),
                at(DemoTargets.Invest.WEEK),
                at(DemoTargets.Invest.CONFIRM_AREA, press(DemoTargets.Invest.CONFIRM)),
                on(3, DemoTargets.Invest.PRACTICE));
    }

    // --- trade -------------------------------------------------------------------------------

    /** The inbox and its two-press accept, sending an offer, and asking a player near for a direct trade. */
    private static DemoScript trade() {
        return new DemoScript(HubTab.TRADE, DemoPages.trade(), List.of(
                at(DemoTargets.Trade.SUBTABS),
                at(DemoTargets.Trade.LIST),
                at(DemoTargets.Trade.CONFIRM_LINES, click(20, 56), press(DemoTargets.Trade.ACCEPT)),
                on(1, DemoTargets.Trade.RECIPIENT, typeText(DemoTargets.Trade.RECIPIENT, DemoPages.NAME_B)),
                at(DemoTargets.Trade.SOURCE, press(DemoTargets.Trade.SOURCE), click(20, 54)),
                at(DemoTargets.Trade.TERMS, type(DemoTargets.Trade.GIVE_COUNT, "32"),
                        type(DemoTargets.Trade.ASK_CREDITS, "40")),
                at(DemoTargets.Trade.SEND_CONFIRM, press(DemoTargets.Trade.SEND)),
                on(2, DemoTargets.Trade.NEARBY, click(20, 54))), DemoPages.NAMES);
    }

    // --- jobs --------------------------------------------------------------------------------

    /** Claim a job, hand it in (two presses), and post one with its payout held. */
    private static DemoScript jobs() {
        return script(HubTab.JOBS, DemoPages.jobs(),
                at(DemoTargets.Jobs.SUBTABS),
                at(DemoTargets.Jobs.LIST),
                at(DemoTargets.Jobs.ACTION, click(20, 54)),
                on(1, DemoTargets.Jobs.CONFIRM, press(DemoTargets.Jobs.ACTION)),
                on(2, DemoTargets.Jobs.KIND),
                at(DemoTargets.Jobs.WHAT, press(DemoTargets.Jobs.FROM_HAND)),
                at(DemoTargets.Jobs.AMOUNTS, type(DemoTargets.Jobs.QUANTITY, "5"), type(DemoTargets.Jobs.PAYOUT, "250")),
                at(DemoTargets.Jobs.POST, press(DemoTargets.Jobs.POST)));
    }

    // --- auctions ----------------------------------------------------------------------------

    /** Bid (two presses), then start an auction of what is in the hand, with its fee. */
    private static DemoScript auctions() {
        return script(HubTab.AUCTIONS, DemoPages.auctions(),
                at(DemoTargets.Auctions.SUBTABS),
                at(DemoTargets.Auctions.LIST),
                at(DemoTargets.Auctions.AMOUNT, click(20, 54), type(DemoTargets.Auctions.AMOUNT, "315")),
                at(DemoTargets.Auctions.CONFIRM, press(DemoTargets.Auctions.BID)),
                on(1, DemoTargets.Auctions.HAND),
                at(DemoTargets.Auctions.FORM, type(DemoTargets.Auctions.QUANTITY, "5"),
                        type(DemoTargets.Auctions.MINIMUM, "100"), type(DemoTargets.Auctions.MINUTES, "60")),
                at(DemoTargets.Auctions.FEE),
                at(DemoTargets.Auctions.CREATE, press(DemoTargets.Auctions.CREATE)));
    }

    // --- shop --------------------------------------------------------------------------------

    /** The player's shops, a new price (two presses), and opening one within reach. */
    private static DemoScript shop() {
        return script(HubTab.SHOP, List.of(DemoPages.shop()),
                at(DemoTargets.Shop.SUMMARY),
                at(DemoTargets.Shop.LIST),
                at(DemoTargets.Shop.IDLE_ROW),
                at(DemoTargets.Shop.PRICE, click(20, 48), type(DemoTargets.Shop.PRICE, "4")),
                at(DemoTargets.Shop.CONFIRM, press(DemoTargets.Shop.SET_PRICE)),
                at(DemoTargets.Shop.OPEN));
    }

    // --- admin -------------------------------------------------------------------------------

    /** Admin is words only: its controls act on the live economy, so it has no sample view. */
    private static DemoScript admin() {
        return script(HubTab.ADMIN, List.of(), DemoStep.text(), DemoStep.text(), DemoStep.text(), DemoStep.text());
    }
}
