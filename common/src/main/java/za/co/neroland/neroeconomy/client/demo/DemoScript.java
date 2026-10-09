package za.co.neroland.neroeconomy.client.demo;

import java.util.List;

import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * A tab's "Show me" walkthrough: the sample pages its view is fed and the steps shown on them. The
 * pages are built on the client from fixed, made-up values by the same page builders the server
 * uses, so the view draws them exactly as it draws a real page; nothing is read from the server,
 * a file or the world. A script with no pages is a text-only walkthrough. Pure Java.
 *
 * @param pages       the sample pages; the view starts on the first
 * @param sampleNames lang keys of made-up player names a view lists instead of real ones
 */
public record DemoScript(HubTab tab, List<HubPage> pages, List<DemoStep> steps, List<String> sampleNames) {
    /** The fewest and most steps a walkthrough has. */
    public static final int MIN_STEPS = 4;
    public static final int MAX_STEPS = 8;
    /** Lang keys the demo's frame uses. */
    public static final String BANNER = "gui.neroeconomy.hub.demo.banner";
    public static final String COUNTER = "gui.neroeconomy.hub.demo.counter";
    public static final String BACK = "gui.neroeconomy.hub.demo.back";
    public static final String NEXT = "gui.neroeconomy.hub.demo.next";
    public static final String DONE = "gui.neroeconomy.hub.demo.done";
    public static final String CLOSE = "gui.neroeconomy.hub.demo.close";
    public static final List<String> FRAME_KEYS = List.of(BANNER, COUNTER, BACK, BACK + ".tooltip", NEXT,
            NEXT + ".tooltip", DONE, DONE + ".tooltip", CLOSE, CLOSE + ".tooltip");

    public DemoScript {
        pages = List.copyOf(pages);
        steps = List.copyOf(steps);
        sampleNames = List.copyOf(sampleNames);
    }

    /** Whether the walkthrough is words only, with no sample view. */
    public boolean textOnly() { return pages.isEmpty(); }

    /** The lang key of the words of step {@code index} (from 0). */
    public String stepKey(int index) { return stepKey(tab, index); }

    public static String stepKey(HubTab tab, int index) { return tab.helpKey() + "." + (index + 1); }

    /** The walkthrough of {@code tab}. */
    public static DemoScript of(HubTab tab) { return DemoScripts.of(tab); }
}
