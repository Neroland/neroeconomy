package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Wallet's "My data" page: what the economy keeps about the viewer, as counts only, and how
 * to have it exported or erased. No row carries a value, an id, a name or anything the counts
 * stand for; each is a fixed lang key and one number. Pure Java.
 */
public final class DataPages {
    /** Header flag: the wallet page is the "My data" summary. */
    public static final String MY_DATA = "my_data";

    private static final String PREFIX = "gui.neroeconomy.hub.data.";
    public static final String HISTORY = PREFIX + "history";
    public static final String OPT_OUT = PREFIX + "opt_out";
    public static final String WAITING = PREFIX + "waiting";
    public static final String VAULT = PREFIX + "vault";
    public static final String ORDERS = PREFIX + "orders";
    public static final String RULES = PREFIX + "rules";
    public static final String SHOPS = PREFIX + "shops";
    public static final String OFFERS = PREFIX + "offers";
    public static final String JOBS = PREFIX + "jobs";
    public static final String AUCTIONS = PREFIX + "auctions";
    public static final String HOLDINGS = PREFIX + "holdings";
    public static final String PRACTICE = PREFIX + "practice";
    /** Every line, in the order the page shows them. */
    public static final List<String> KEYS = List.of(HISTORY, OPT_OUT, WAITING, VAULT, ORDERS, RULES, SHOPS, OFFERS,
            JOBS, AUCTIONS, HOLDINGS, PRACTICE);

    private DataPages() { }

    /** One line of the summary. @param key one of {@link #KEYS} */
    public record DataLine(String key, long count) { }

    /** The summary, in the order of {@link #KEYS}; a key not given counts as 0. */
    public static HubPages.Built build(List<DataLine> lines) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (lines != null) for (DataLine line : lines) if (line != null && KEYS.contains(line.key()))
            counts.put(line.key(), Math.max(0L, line.count()));
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        int index = 0;
        for (String key : KEYS) {
            UUID id = new UUID(0L, ++index);
            rows.add(new HubPage.Row(id, "", key, List.of(), new long[] { counts.getOrDefault(key, 0L) }, 0));
            refs.put(id, new HubPages.RowRef(HubPages.RowRef.Kind.DATA, null, "", 0));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(MY_DATA, 1L);
        return new HubPages.Built(new HubPage(0, 0, HubTab.WALLET, 0, 1, rows, numbers, Map.of()), refs);
    }
}
