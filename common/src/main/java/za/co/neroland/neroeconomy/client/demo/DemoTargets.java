package za.co.neroland.neroeconomy.client.demo;

import static za.co.neroland.neroeconomy.client.demo.DemoTarget.area;
import static za.co.neroland.neroeconomy.client.demo.DemoTarget.widget;

/**
 * Where each tab's controls and readouts are, in body coordinates, taken from the tab view's own
 * layout (the table in each {@code Hub*View} class comment). A widget target is the exact bounds the
 * view gives the button or box; a test checks every one against the view's source. Pure Java.
 */
public final class DemoTargets {
    private DemoTargets() { }

    /** {@code HubWalletView}. */
    public static final class Wallet {
        public static final DemoTarget BALANCE = area(8, 19, 240, 22);
        public static final DemoTarget COLLECT = widget(192, 40, 56, 14);
        public static final DemoTarget RECIPIENT = widget(8, 68, 110, 14);
        public static final DemoTarget AMOUNT = widget(122, 68, 58, 14);
        public static final DemoTarget PAY = widget(184, 68, 64, 14);
        public static final DemoTarget CONFIRM = area(8, 85, 240, 10);
        public static final DemoTarget HISTORY = area(8, 97, 240, 88);
        public static final DemoTarget LOGGING = widget(150, 188, 98, 14);

        private Wallet() { }
    }

    /** {@code HubVaultView}. */
    public static final class Vault {
        public static final DemoTarget USED = area(8, 19, 240, 22);
        public static final DemoTarget LIST = area(8, 42, 240, 144);
        public static final DemoTarget MODIFIED = area(8, 60, 240, 18);
        public static final DemoTarget OPEN = widget(8, 190, 84, 14);
        public static final DemoTarget COLLECT = widget(96, 190, 96, 14);

        private Vault() { }
    }

    /** {@code HubMarketView}. */
    public static final class Market {
        public static final DemoTarget SEARCH = widget(8, 20, 118, 14);
        public static final DemoTarget MODES = area(130, 19, 118, 16);
        public static final DemoTarget LIST = area(8, 38, 240, 100);
        public static final DemoTarget FIRST_ROW = area(8, 38, 240, 20);
        public static final DemoTarget QUANTITY = widget(8, 158, 48, 14);
        public static final DemoTarget QUOTE = widget(8, 174, 60, 14);
        public static final DemoTarget QUOTE_LINES = area(8, 191, 240, 20);
        public static final DemoTarget CONFIRM = widget(72, 174, 76, 14);
        public static final DemoTarget CONFIRM_LINE = area(8, 211, 240, 10);

        private Market() { }
    }

    /** {@code HubOrdersView}. */
    public static final class Orders {
        public static final DemoTarget LIST = area(8, 32, 240, 120);
        public static final DemoTarget PRIMARY = widget(8, 174, 60, 14);
        public static final DemoTarget CONFIRM_LINE = area(8, 191, 240, 22);
        public static final DemoTarget PAUSE_ALL = widget(136, 174, 56, 14);
        public static final DemoTarget NEW_RULE = widget(196, 174, 52, 14);
        public static final DemoTarget TARGET = widget(8, 38, 180, 14);
        public static final DemoTarget LIMIT = widget(120, 70, 80, 14);
        public static final DemoTarget PER_DAY = widget(120, 88, 80, 14);
        public static final DemoTarget TOTAL = widget(120, 106, 80, 14);
        public static final DemoTarget NUMBERS = area(8, 68, 240, 54);
        public static final DemoTarget CREATE = widget(8, 126, 80, 14);
        public static final DemoTarget FORM_CONFIRM = area(8, 145, 240, 22);

        private Orders() { }
    }

    /** {@code HubInvestView}. */
    public static final class Invest {
        public static final DemoTarget PRACTICE = widget(110, 20, 70, 14);
        public static final DemoTarget LIST = area(8, 50, 240, 96);
        public static final DemoTarget CHART = area(8, 168, 240, 38);
        public static final DemoTarget HOW_MUCH = widget(184, 150, 64, 14);
        public static final DemoTarget CREDITS = widget(8, 118, 70, 14);
        public static final DemoTarget QUOTE = widget(82, 118, 96, 14);
        public static final DemoTarget QUOTE_LINES = area(8, 137, 240, 20);
        public static final DemoTarget WEEK = area(8, 160, 240, 28);
        public static final DemoTarget CONFIRM = widget(8, 202, 120, 14);
        public static final DemoTarget CONFIRM_AREA = area(8, 189, 240, 28);

        private Invest() { }
    }

    /** {@code HubTradeView}. */
    public static final class Trade {
        public static final DemoTarget SUBTABS = area(8, 19, 240, 16);
        public static final DemoTarget LIST = area(8, 52, 240, 120);
        public static final DemoTarget ACCEPT = widget(150, 176, 48, 14);
        public static final DemoTarget CONFIRM_LINES = area(8, 195, 240, 22);
        public static final DemoTarget SOURCE = widget(150, 140, 98, 14);
        public static final DemoTarget RECIPIENT = widget(8, 158, 70, 14);
        public static final DemoTarget GIVE_COUNT = widget(82, 158, 36, 14);
        public static final DemoTarget ASK_CREDITS = widget(166, 176, 82, 14);
        public static final DemoTarget TERMS = area(8, 157, 240, 34);
        public static final DemoTarget SEND = widget(8, 194, 80, 14);
        public static final DemoTarget SEND_CONFIRM = area(8, 193, 240, 28);
        public static final DemoTarget NEARBY = area(8, 52, 240, 112);

        private Trade() { }
    }

    /** {@code HubJobsView}. */
    public static final class Jobs {
        public static final DemoTarget SUBTABS = area(8, 19, 240, 16);
        public static final DemoTarget LIST = area(8, 50, 240, 120);
        public static final DemoTarget ACTION = widget(150, 174, 98, 14);
        public static final DemoTarget CONFIRM = area(8, 193, 138, 22);
        public static final DemoTarget KIND = widget(8, 50, 110, 14);
        public static final DemoTarget WHAT = area(8, 67, 240, 16);
        public static final DemoTarget FROM_HAND = widget(162, 68, 86, 14);
        public static final DemoTarget QUANTITY = widget(8, 86, 50, 14);
        public static final DemoTarget PAYOUT = widget(62, 86, 80, 14);
        public static final DemoTarget AMOUNTS = area(8, 85, 140, 16);
        public static final DemoTarget POST = widget(8, 194, 80, 14);

        private Jobs() { }
    }

    /** {@code HubAuctionsView}. */
    public static final class Auctions {
        public static final DemoTarget SUBTABS = area(8, 19, 240, 16);
        public static final DemoTarget LIST = area(8, 50, 240, 120);
        public static final DemoTarget AMOUNT = widget(92, 174, 54, 14);
        public static final DemoTarget BID = widget(150, 174, 46, 14);
        public static final DemoTarget CONFIRM = area(8, 193, 240, 22);
        public static final DemoTarget HAND = area(8, 47, 240, 18);
        public static final DemoTarget FORM = area(8, 67, 170, 16);
        public static final DemoTarget QUANTITY = widget(8, 68, 40, 14);
        public static final DemoTarget MINIMUM = widget(52, 68, 70, 14);
        public static final DemoTarget MINUTES = widget(126, 68, 50, 14);
        public static final DemoTarget FEE = area(8, 89, 240, 22);
        public static final DemoTarget CREATE = widget(8, 194, 80, 14);

        private Auctions() { }
    }

    /** {@code HubShopView}. */
    public static final class Shop {
        public static final DemoTarget SUMMARY = area(8, 20, 240, 20);
        public static final DemoTarget LIST = area(8, 44, 240, 120);
        public static final DemoTarget IDLE_ROW = area(8, 64, 240, 20);
        public static final DemoTarget PRICE = widget(92, 168, 54, 14);
        public static final DemoTarget SET_PRICE = widget(150, 168, 98, 14);
        public static final DemoTarget CONFIRM = area(8, 203, 240, 18);
        public static final DemoTarget OPEN = widget(150, 186, 98, 14);

        private Shop() { }
    }
}
