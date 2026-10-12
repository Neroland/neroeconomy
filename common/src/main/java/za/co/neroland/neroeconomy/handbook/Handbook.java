package za.co.neroland.neroeconomy.handbook;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * The Trader's Handbook: its chapters, each a few short pages in plain words, and the hub tab whose
 * "Show me" walkthrough each page's Open demo button runs. Data in code; the words are lang keys
 * {@code gui.neroeconomy.handbook.chapter.<id>} (the chapter's short name in the list),
 * {@code ...<id>.title} (its heading) and {@code ...<id>.<n>} (page n, from 1). Nothing here is
 * stored or sent anywhere: the book is read on the client only. Pure Java.
 *
 * <p>Four chapters end in a <b>Trader's Note</b> ({@link Note}): an extra page a quest pack's
 * {@code handbook_page} hook opens for a player, one at a time in {@link #NOTES_IN_ORDER}. A note is
 * shown when its Core progression gate ({@code neroeconomy:handbook/<id>}, scope player) is open for
 * the reader, which the client learns through Core's own gate sync; {@link Chapter#visible} is the
 * chapter as that reader sees it. The words are {@code gui.neroeconomy.handbook.note.<id>}.
 */
public final class Handbook {
    /** The fewest and most pages a chapter has, notes included. */
    public static final int MIN_PAGES = 2;
    public static final int MAX_PAGES = 5;
    /** Lang keys of the screen's frame. */
    public static final String TITLE = "gui.neroeconomy.handbook.title";
    public static final String PAGE = "gui.neroeconomy.handbook.page";
    public static final String PREVIOUS = "gui.neroeconomy.handbook.previous";
    public static final String NEXT = "gui.neroeconomy.handbook.next";
    public static final String DEMO = "gui.neroeconomy.handbook.demo";
    public static final List<String> FRAME_KEYS = List.of(TITLE, PAGE, PREVIOUS, PREVIOUS + ".tooltip", NEXT,
            NEXT + ".tooltip", DEMO, DEMO + ".tooltip");
    private static final String CHAPTER = "gui.neroeconomy.handbook.chapter.";
    private static final String NOTE = "gui.neroeconomy.handbook.note.";

    /**
     * A Trader's Note: the last page of its chapter, shown once its gate is open for the reader.
     *
     * @param id   the id in its lang key and gate path
     * @param gate the player-scope Core gate that reveals it, {@code neroeconomy:handbook/<id>}
     * @param demo the tab the page's Open demo button shows
     */
    public record Note(String id, Identifier gate, HubTab demo) {
        /** Lang key of the note's words. */
        public String pageKey() { return NOTE + id; }
    }

    /**
     * One chapter.
     *
     * @param id    the id in its lang keys
     * @param demos the tab each page's Open demo button shows, one per page
     * @param notes the Trader's Notes after those pages, in order; usually none
     */
    public record Chapter(String id, List<HubTab> demos, List<Note> notes) {
        public Chapter {
            demos = List.copyOf(demos);
            notes = List.copyOf(notes);
        }

        /** Pages including the notes this chapter lists. */
        public int pages() { return demos.size() + notes.size(); }

        /** Lang key of the chapter's short name, as listed. */
        public String nameKey() { return CHAPTER + id; }

        /** Lang key of the chapter's heading over its pages. */
        public String titleKey() { return CHAPTER + id + ".title"; }

        /** Lang key of page {@code index} (from 0); past the chapter's own pages, a note's. */
        public String pageKey(int index) {
            return index < demos.size() ? CHAPTER + id + "." + (index + 1) : notes.get(index - demos.size()).pageKey();
        }

        /** The tab page {@code index} (from 0) demonstrates. */
        public HubTab demo(int index) {
            return index < demos.size() ? demos.get(index) : notes.get(index - demos.size()).demo();
        }

        /** This chapter as one reader sees it: its pages, then only the notes {@code open} says are theirs. */
        public Chapter visible(Predicate<Identifier> open) {
            if (notes.isEmpty()) return this;
            return new Chapter(id, demos, notes.stream().filter(note -> open.test(note.gate())).toList());
        }
    }

    private static Note note(String id, HubTab demo) {
        return new Note(id, Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "handbook/" + id), demo);
    }

    private static Chapter chapter(String id, HubTab... demos) { return new Chapter(id, List.of(demos), List.of()); }

    private static Chapter chapter(String id, Note note, HubTab... demos) {
        return new Chapter(id, List.of(demos), List.of(note));
    }

    /** The Trader's Notes, in the order a quest hook opens them: one per completed hook, the first still closed. */
    public static final Note READING_THE_BOOK = note("reading_the_book", HubTab.ORDERS);
    public static final Note STALL_PRICING = note("stall_pricing", HubTab.SHOP);
    public static final Note CONTRACT_TIMING = note("contract_timing", HubTab.JOBS);
    public static final Note HOUSE_CYCLES = note("house_cycles", HubTab.MARKET);
    public static final List<Note> NOTES_IN_ORDER = List.of(READING_THE_BOOK, STALL_PRICING, CONTRACT_TIMING, HOUSE_CYCLES);

    /** The chapters, in reading order. */
    public static final List<Chapter> CHAPTERS_IN_ORDER = List.of(
            chapter("credits", HubTab.WALLET, HubTab.WALLET, HubTab.WALLET, HubTab.WALLET),
            chapter("vault", HubTab.VAULT, HubTab.VAULT, HubTab.VAULT),
            chapter("market", READING_THE_BOOK, HubTab.MARKET, HubTab.MARKET, HubTab.MARKET),
            chapter("orders", HubTab.MARKET, HubTab.ORDERS, HubTab.ORDERS),
            chapter("shops", STALL_PRICING, HubTab.SHOP, HubTab.SHOP, HubTab.SHOP),
            chapter("trading", HubTab.TRADE, HubTab.TRADE, HubTab.TRADE, HubTab.TRADE),
            chapter("auto_trade", HubTab.ORDERS, HubTab.ORDERS, HubTab.ORDERS),
            chapter("investing", HubTab.INVEST, HubTab.INVEST, HubTab.INVEST, HubTab.INVEST, HubTab.INVEST),
            chapter("houses", HOUSE_CYCLES, HubTab.MARKET, HubTab.MARKET, HubTab.MARKET),
            chapter("jobs", CONTRACT_TIMING, HubTab.JOBS, HubTab.JOBS, HubTab.AUCTIONS, HubTab.AUCTIONS),
            chapter("fair_play", HubTab.MARKET, HubTab.ORDERS, HubTab.MARKET),
            chapter("data", HubTab.WALLET, HubTab.WALLET, HubTab.WALLET, HubTab.WALLET));

    private Handbook() { }

    /** The chapter at {@code index}, clamped into range, with every note (see {@link Chapter#visible}). */
    public static Chapter chapter(int index) {
        return CHAPTERS_IN_ORDER.get(Math.max(0, Math.min(index, CHAPTERS_IN_ORDER.size() - 1)));
    }

    /** Page {@code page} clamped into the pages of chapter {@code chapter}, every note counted. */
    public static int clampPage(int chapter, int page) {
        return clampPage(chapter(chapter), page);
    }

    /** Page {@code page} clamped into the pages of {@code chapter} as it stands (see {@link Chapter#visible}). */
    public static int clampPage(Chapter chapter, int page) {
        return Math.max(0, Math.min(page, chapter.pages() - 1));
    }
}
