package za.co.neroland.neroeconomy.hub;

/**
 * Everything a hub intent can ask for. The constants are sent by ordinal, so they are only ever
 * appended. Each action says which tab it belongs to, whether it moves value (the tighter packet
 * budget and the frozen check), whether its target must be a row of the page the player was last
 * sent, and who records its request id: the hub itself, or the economy call it ends in (which
 * records it there, so it is not recorded twice). Pure Java.
 */
public enum HubAction {
    /** Show another tab. {@code a} is the tab's ordinal. */
    OPEN_TAB(null, false, Target.NONE, Request.NONE, false),
    /** Show another page of the current tab's list. {@code a} is the page, from 0. */
    PAGE(null, false, Target.NONE, Request.NONE, false),
    /** Pay out the credits waiting to be collected. */
    WALLET_COLLECT(HubTab.WALLET, true, Target.NONE, Request.HUB, false),
    /** Pay another player. {@code text} is the recipient's name or UUID, {@code a} the amount. */
    WALLET_PAY(HubTab.WALLET, true, Target.NONE, Request.CALL, false),
    /** The activity-log opt-out switch. {@code a} is 1 to opt out, 0 to opt back in. */
    WALLET_LOGGING(HubTab.WALLET, true, Target.NONE, Request.HUB, false),
    /** Open the vault's own window (only at a Bank Terminal). */
    VAULT_OPEN(HubTab.VAULT, true, Target.NONE, Request.NONE, false),
    /** Move as much of the vault into the inventory as fits. */
    VAULT_COLLECT(HubTab.VAULT, true, Target.NONE, Request.HUB, false),
    /** Search the market. {@code text} is the search. */
    MARKET_SEARCH(HubTab.MARKET, false, Target.NONE, Request.NONE, false),
    /** What the market list shows. {@code a} is a {@link MarketMode} ordinal. */
    MARKET_MODE(HubTab.MARKET, false, Target.NONE, Request.NONE, false),
    /** At a Merchant Stall: show the next Merchant House. */
    MARKET_HOUSE(HubTab.MARKET, false, Target.NONE, Request.NONE, false),
    /** Price buying {@code a} of a row. */
    MARKET_QUOTE(HubTab.MARKET, false, Target.ROW, Request.NONE, false),
    /** Buy {@code a} of a row at no more than {@code b} each. */
    MARKET_BUY(HubTab.MARKET, true, Target.ROW, Request.CALL, false),
    /** Price selling {@code a} at {@code b} each, from the main hand (no target) or a vault row. */
    MARKET_SELL_QUOTE(HubTab.MARKET, false, Target.OPTIONAL_ROW, Request.NONE, false),
    /** Sell {@code a} at {@code b} each, from the main hand (no target) or a vault row. */
    MARKET_SELL(HubTab.MARKET, true, Target.OPTIONAL_ROW, Request.CALL, true),
    /** Price a buy order: {@code text} the item id, {@code a} the quantity, {@code b} the price each. */
    MARKET_BID_QUOTE(HubTab.MARKET, false, Target.NONE, Request.NONE, false),
    /** Place a buy order: {@code text} the item id, {@code a} the quantity, {@code b} the price each. */
    MARKET_BID(HubTab.MARKET, true, Target.NONE, Request.CALL, false),
    /** Cancel one of the player's own orders. */
    ORDER_CANCEL(HubTab.ORDERS, true, Target.ROW, Request.HUB, false),
    /** Pause ({@code a} = 1) or resume ({@code a} = 0) one of the player's rules. */
    RULE_PAUSE(HubTab.ORDERS, true, Target.ROW, Request.HUB, false),
    /** Delete one of the player's rules; its budget comes back. */
    RULE_DELETE(HubTab.ORDERS, true, Target.ROW, Request.HUB, false),
    /** Pause ({@code a} = 1) or resume ({@code a} = 0) all of the player's rules. */
    RULE_PAUSE_ALL(HubTab.ORDERS, true, Target.NONE, Request.HUB, false),
    /** Create a rule: {@code text} is a {@link RuleForm}, {@code a} the limit price, {@code b} the total. */
    RULE_ADD(HubTab.ORDERS, true, Target.NONE, Request.CALL, false),

    // --- appended by F1b: Invest, Trade, Jobs, Auctions, Shop, and the Wallet's "My data" ----
    /** The Wallet's "My data" summary ({@code a} = 1) or the wallet itself ({@code a} = 0). */
    WALLET_MY_DATA(HubTab.WALLET, false, Target.NONE, Request.NONE, false),
    /** What the Invest tab shows. {@code a} is an {@link InvestView} ordinal. */
    INVEST_VIEW(HubTab.INVEST, false, Target.NONE, Request.NONE, false),
    /** Practice mode ({@code a} = 1) or real credits ({@code a} = 0). */
    INVEST_PRACTICE(HubTab.INVEST, false, Target.NONE, Request.NONE, false),
    /** Pick a product or a Savings Bond term: its chart and the next quote are for it. */
    INVEST_PICK(HubTab.INVEST, false, Target.ROW, Request.NONE, false),
    /** Price putting {@code a} credits into the picked product, with the week's outlook. */
    INVEST_QUOTE(HubTab.INVEST, false, Target.NONE, Request.NONE, false),
    /** Put {@code a} credits into the picked product (at most that much leaves the balance). */
    INVEST_BUY(HubTab.INVEST, true, Target.NONE, Request.CALL, false),
    /** Price selling {@code a} units of a holding; 0 means all of it. */
    INVEST_SELL_QUOTE(HubTab.INVEST, false, Target.ROW, Request.NONE, false),
    /**
     * Sell {@code a} units of a holding (0: all of it). {@code b} is the reduced gain accepted, or
     * -1 to accept only the full gain.
     */
    INVEST_SELL(HubTab.INVEST, true, Target.ROW, Request.CALL, false),
    /** Cash in a Savings Bond: at maturity with its interest, before it with the principal only. */
    INVEST_REDEEM(HubTab.INVEST, true, Target.ROW, Request.CALL, false),
    /** Start the practice portfolio afresh (once per economy-day). */
    INVEST_RESET(HubTab.INVEST, true, Target.NONE, Request.CALL, false),
    /** What the Trade tab shows. {@code a} is a {@link TradeView} ordinal. */
    TRADE_VIEW(HubTab.TRADE, false, Target.NONE, Request.NONE, false),
    /** Accept an offer sent to the viewer. */
    OFFER_ACCEPT(HubTab.TRADE, true, Target.ROW, Request.CALL, false),
    /** Decline an offer sent to the viewer; everything goes back to its sender. */
    OFFER_DECLINE(HubTab.TRADE, true, Target.ROW, Request.HUB, false),
    /** Take back an offer the viewer sent. */
    OFFER_CANCEL(HubTab.TRADE, true, Target.ROW, Request.HUB, false),
    /**
     * Send an offer. {@code text} is an {@link OfferForm}; the target is the vault line given, or
     * none when the form gives from the hand or gives goods not at all.
     */
    OFFER_SEND(HubTab.TRADE, true, Target.OPTIONAL_ROW, Request.CALL, false),
    /** Mute ({@code a} = 1) or unmute ({@code a} = 0) offers and trade requests. */
    OFFER_MUTE(HubTab.TRADE, false, Target.NONE, Request.NONE, false),
    /** Ask a player standing near for a direct trade. {@code text} is the name the viewer addressed. */
    TRADE_REQUEST(HubTab.TRADE, true, Target.NONE, Request.HUB, false),
    /** What the Jobs tab shows. {@code a} is a {@link JobsView} ordinal. */
    JOBS_VIEW(HubTab.JOBS, false, Target.NONE, Request.NONE, false),
    /** Claim an open job. */
    JOB_CLAIM(HubTab.JOBS, true, Target.ROW, Request.HUB, false),
    /** Give a claimed job back. */
    JOB_RELEASE(HubTab.JOBS, true, Target.ROW, Request.HUB, false),
    /** Hand in a claimed contract or delivery from the inventory. */
    JOB_FULFIL(HubTab.JOBS, true, Target.ROW, Request.HUB, false),
    /** Withdraw one of the viewer's own unclaimed jobs; the payout comes back. */
    JOB_CANCEL(HubTab.JOBS, true, Target.ROW, Request.HUB, false),
    /** Post a job: {@code text} is a {@link JobForm}, {@code a} the payout. */
    JOB_POST(HubTab.JOBS, true, Target.NONE, Request.HUB, false),
    /** What the Auctions tab shows. {@code a} is an {@link AuctionsView} ordinal. */
    AUCTION_VIEW(HubTab.AUCTIONS, false, Target.NONE, Request.NONE, false),
    /** Bid {@code a} credits on an auction. */
    AUCTION_BID(HubTab.AUCTIONS, true, Target.ROW, Request.HUB, false),
    /** Cancel one of the viewer's own auctions while nobody has bid. */
    AUCTION_CANCEL(HubTab.AUCTIONS, true, Target.ROW, Request.HUB, false),
    /**
     * Auction {@code a} of the main-hand stack with a minimum bid of {@code b}. {@code text} is
     * {@code <minutes>:<1 for interplanetary, else 0>}.
     */
    AUCTION_CREATE(HubTab.AUCTIONS, true, Target.NONE, Request.HUB, true),
    /** Set the price of one of the viewer's Player Shops to {@code a}. */
    SHOP_PRICE(HubTab.SHOP, true, Target.ROW, Request.HUB, false),
    /** Open the shop's own window, for a Player Shop within reach. */
    SHOP_OPEN(HubTab.SHOP, false, Target.ROW, Request.NONE, false),

    // --- appended by F3: the Admin tab. Every one needs a level-3 operator, checked by the router
    // and again by the server call; the ones in {@link #adminMutation()} also need adminMutationsEnabled.
    /** What the Admin tab shows. {@code a} is an {@link AdminView} ordinal. */
    ADMIN_VIEW(HubTab.ADMIN, false, Target.NONE, Request.NONE, false),
    /** Run {@code a} simulation steps now (1 to {@link HubRouter#MAX_ADMIN_STEPS}). */
    ADMIN_SIM_STEP(HubTab.ADMIN, true, Target.NONE, Request.HUB, false),
    /** Close ({@code a} = 1) or open ({@code a} = 0) the market. */
    ADMIN_MARKET_FREEZE(HubTab.ADMIN, false, Target.NONE, Request.HUB, false),
    /** Run the data-pack reload check now and show its counts and failures. */
    ADMIN_RELOAD_CHECK(HubTab.ADMIN, false, Target.NONE, Request.NONE, false),
    /** Accept a fresh start for a frozen economy. Not a value move: it is only possible while frozen. */
    ADMIN_ACCEPT_FRESH(HubTab.ADMIN, false, Target.NONE, Request.HUB, false),
    /** Look up the account {@code text} names (a name or a UUID), for the player data tools. */
    ADMIN_TARGET(HubTab.ADMIN, false, Target.NONE, Request.NONE, false),
    /** Write the export file of the looked-up account. */
    ADMIN_EXPORT(HubTab.ADMIN, false, Target.ROW, Request.HUB, false),
    /** Erase everything the economy keeps about the looked-up account. */
    ADMIN_ERASE(HubTab.ADMIN, false, Target.ROW, Request.HUB, false),
    /** Grant {@code a} credits to the looked-up account (ADMIN_GRANT). */
    ADMIN_GRANT(HubTab.ADMIN, true, Target.ROW, Request.HUB, false),
    /** Take {@code a} credits from the looked-up account (ADMIN_TAKE). */
    ADMIN_TAKE(HubTab.ADMIN, true, Target.ROW, Request.HUB, false),
    /** Set the looked-up account's balance to {@code a} (ADMIN_SET). */
    ADMIN_SET(HubTab.ADMIN, true, Target.ROW, Request.HUB, false);

    /** What the Admin tab shows. */
    public enum AdminView {
        /** Where every credit is, the invariants and today's budgets. */
        SUPPLY,
        /** The simulation's clock, forced steps and the market freeze switch. */
        SIM,
        /** Every commodity: reference, the Houses' ask and bid, today's volume, switch-offs. */
        COMMODITIES,
        /** Every listed company. */
        STOCKS,
        /** Every Merchant House. */
        HOUSES,
        /** The reload check's last result. */
        CHECK,
        /** Frozen or healthy, schema, sections, invariants, accepting a fresh start. */
        HEALTH,
        /** Export, erase, grant, take and set for one account the operator types. */
        PLAYER;

        public static AdminView byOrdinal(long ordinal) {
            AdminView[] all = values();
            return ordinal >= 0L && ordinal < all.length ? all[(int) ordinal] : SUPPLY;
        }
    }

    /** Whether the action changes the economy, so it also needs {@code adminMutationsEnabled}. */
    public boolean adminMutation() {
        return switch (this) {
            case ADMIN_SIM_STEP, ADMIN_MARKET_FREEZE, ADMIN_ACCEPT_FRESH, ADMIN_GRANT, ADMIN_TAKE, ADMIN_SET -> true;
            default -> false;
        };
    }

    /** Whether only a level-3 operator may send it: every action of the Admin tab. */
    public boolean adminOnly() { return tab == HubTab.ADMIN; }

    /** What the Invest tab shows. */
    public enum InvestView {
        /** Pick a product, say how much, confirm. */
        PRODUCTS,
        /** What the viewer holds. */
        PORTFOLIO;

        public static InvestView byOrdinal(long ordinal) { return ordinal == 1L ? PORTFOLIO : PRODUCTS; }
    }

    /** What the Trade tab shows. */
    public enum TradeView {
        /** Offers sent to the viewer. */
        INBOX,
        /** Offers the viewer sent. */
        OUTBOX,
        /** The "Send offer" form, with the vault to give from. */
        SEND,
        /** Start a direct trade with a player standing near. */
        DIRECT;

        public static TradeView byOrdinal(long ordinal) {
            TradeView[] all = values();
            return ordinal >= 0L && ordinal < all.length ? all[(int) ordinal] : INBOX;
        }
    }

    /** What the Jobs tab shows. */
    public enum JobsView {
        CONTRACTS, DELIVERIES, BOUNTIES,
        /** The viewer's own posted and claimed jobs. */
        MINE,
        /** The "Post" form. */
        POST;

        public static JobsView byOrdinal(long ordinal) {
            JobsView[] all = values();
            return ordinal >= 0L && ordinal < all.length ? all[(int) ordinal] : CONTRACTS;
        }
    }

    /** What the Auctions tab shows. */
    public enum AuctionsView {
        /** Every running auction the viewer may see. */
        ALL,
        /** The viewer's own auctions. */
        MINE,
        /** The "Create" form. */
        CREATE;

        public static AuctionsView byOrdinal(long ordinal) {
            AuctionsView[] all = values();
            return ordinal >= 0L && ordinal < all.length ? all[(int) ordinal] : ALL;
        }
    }

    /** Whether the action names a row of the last page sent. */
    public enum Target { NONE, ROW, OPTIONAL_ROW }

    /** Who records the request id. */
    public enum Request {
        /** A read: the request id is not recorded. */
        NONE,
        /** The hub records it before acting. */
        HUB,
        /** The economy call the action ends in records it. */
        CALL
    }

    /** What the market list shows. */
    public enum MarketMode {
        /** Everything on offer, as found by the search. */
        BROWSE,
        /** The viewer's own vault, to pick something to sell. */
        VAULT;

        public static MarketMode byOrdinal(long ordinal) {
            return ordinal == 1L ? VAULT : BROWSE;
        }
    }

    private final HubTab tab;
    private final boolean movesValue;
    private final Target target;
    private final Request request;
    private final boolean takesGoods;

    HubAction(HubTab tab, boolean movesValue, Target target, Request request, boolean takesGoods) {
        this.tab = tab;
        this.movesValue = movesValue;
        this.target = target;
        this.request = request;
        this.takesGoods = takesGoods;
    }

    /** The tab the action belongs to; null when it is valid on any tab. */
    public HubTab tab() { return tab; }

    /** Whether the action moves credits or goods (or could): the tight budget and the frozen check. */
    public boolean movesValue() { return movesValue; }

    public Target target() { return target; }

    public Request request() { return request; }

    /**
     * Whether goods may leave a live inventory for the economy, so a creative-mode or spectating
     * player is refused. Only the hand sale does; a sale from the vault takes nothing from an inventory.
     */
    public boolean takesGoods() { return takesGoods; }

    /** The action at {@code ordinal}; null when it is out of range. */
    public static HubAction byOrdinal(int ordinal) {
        HubAction[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }
}
