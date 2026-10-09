package za.co.neroland.neroeconomy.handbook;

import java.util.List;

import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * The Trader's Handbook: its chapters, each a few short pages in plain words, and the hub tab whose
 * "Show me" walkthrough each page's Open demo button runs. Data in code; the words are lang keys
 * {@code gui.neroeconomy.handbook.chapter.<id>} (the chapter's short name in the list),
 * {@code ...<id>.title} (its heading) and {@code ...<id>.<n>} (page n, from 1). Nothing here is
 * stored or sent anywhere: the book is read on the client only. Pure Java.
 */
public final class Handbook {
    /** The fewest and most pages a chapter has. */
    public static final int MIN_PAGES = 2;
    public static final int MAX_PAGES = 5;
    /** Lang keys of the screen's frame. */
    public static final String TITLE = "gui.neroeconomy.handbook.title";
    public static final String PAGE = "gui.neroeconomy.handbook.page";
    public static final String PREVIOUS = "gui.neroeconomy.handbook.previous";
    public static final String NEXT = "gui.neroeconomy.handbook.next";
    public static final String DEMO = "gui.neroeconomy.handbook.demo";
    public static final String CHAPTERS = "gui.neroeconomy.handbook.chapters";
    public static final List<String> FRAME_KEYS = List.of(TITLE, PAGE, PREVIOUS, PREVIOUS + ".tooltip", NEXT,
            NEXT + ".tooltip", DEMO, DEMO + ".tooltip", CHAPTERS);
    private static final String CHAPTER = "gui.neroeconomy.handbook.chapter.";

    /**
     * One chapter.
     *
     * @param id    the id in its lang keys
     * @param demos the tab each page's Open demo button shows, one per page
     */
    public record Chapter(String id, List<HubTab> demos) {
        public Chapter {
            demos = List.copyOf(demos);
        }

        public int pages() { return demos.size(); }

        /** Lang key of the chapter's short name, as listed. */
        public String nameKey() { return CHAPTER + id; }

        /** Lang key of the chapter's heading over its pages. */
        public String titleKey() { return CHAPTER + id + ".title"; }

        /** Lang key of page {@code index} (from 0). */
        public String pageKey(int index) { return CHAPTER + id + "." + (index + 1); }

        /** The tab page {@code index} (from 0) demonstrates. */
        public HubTab demo(int index) { return demos.get(index); }
    }

    private static Chapter chapter(String id, HubTab... demos) { return new Chapter(id, List.of(demos)); }

    /** The chapters, in reading order. */
    public static final List<Chapter> CHAPTERS_IN_ORDER = List.of(
            chapter("credits", HubTab.WALLET, HubTab.WALLET, HubTab.WALLET, HubTab.WALLET),
            chapter("vault", HubTab.VAULT, HubTab.VAULT, HubTab.VAULT),
            chapter("market", HubTab.MARKET, HubTab.MARKET, HubTab.MARKET),
            chapter("orders", HubTab.MARKET, HubTab.ORDERS, HubTab.ORDERS),
            chapter("shops", HubTab.SHOP, HubTab.SHOP, HubTab.SHOP),
            chapter("trading", HubTab.TRADE, HubTab.TRADE, HubTab.TRADE, HubTab.TRADE),
            chapter("auto_trade", HubTab.ORDERS, HubTab.ORDERS, HubTab.ORDERS),
            chapter("investing", HubTab.INVEST, HubTab.INVEST, HubTab.INVEST, HubTab.INVEST, HubTab.INVEST),
            chapter("houses", HubTab.MARKET, HubTab.MARKET, HubTab.MARKET),
            chapter("jobs", HubTab.JOBS, HubTab.JOBS, HubTab.AUCTIONS, HubTab.AUCTIONS),
            chapter("fair_play", HubTab.MARKET, HubTab.ORDERS, HubTab.MARKET),
            chapter("data", HubTab.WALLET, HubTab.WALLET, HubTab.WALLET, HubTab.WALLET));

    private Handbook() { }

    /** The chapter at {@code index}, clamped into range. */
    public static Chapter chapter(int index) {
        return CHAPTERS_IN_ORDER.get(Math.max(0, Math.min(index, CHAPTERS_IN_ORDER.size() - 1)));
    }

    /** Page {@code page} clamped into the pages of chapter {@code chapter}. */
    public static int clampPage(int chapter, int page) {
        return Math.max(0, Math.min(page, chapter(chapter).pages() - 1));
    }
}
