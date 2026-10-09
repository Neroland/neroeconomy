package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One page of the Economy Hub as the server sends it: which tab, a bounded list of rows and a small
 * header of numbers and lang keys. It carries no player name and no player id: rows are identified
 * by the id of the order, rule or listing they show (or a number made up for the page), and every
 * string in it is a lang key, an item id, or a tag id written with its leading {@code #}.
 *
 * <p>Everything is bounded: {@link #capped()} cuts a page down to the limits below, and the payload
 * only ever writes a capped page, so a page that grew too large can never break the connection.
 * Pure Java.
 *
 * @param revision  rises with every page sent to the menu; the client ignores anything older
 * @param page      which page of the tab's list this is, from 0
 * @param pageCount how many pages the list has; at least 1
 * @param series    a list of numbers a tab draws as a chart (the Invest tab's price line); empty for none
 */
public record HubPage(int containerId, int revision, HubTab tab, int page, int pageCount, List<Row> rows,
        Map<String, Long> numbers, Map<String, String> keys, long[] series) {
    public static final int MAX_ROWS = 12;
    public static final int MAX_ARGS = 4;
    public static final int MAX_NUMS = 6;
    public static final int MAX_STRING = 128;
    public static final int MAX_ICON = 256;
    public static final int MAX_HEADER_NUMBERS = 40;
    public static final int MAX_HEADER_KEYS = 12;
    public static final int MAX_HEADER_NAME = 32;
    public static final int MAX_SERIES = 96;

    /** Row flag bits; their meaning per tab is set by the tab's page builder. */
    public static final int FLAG_A = 1;
    public static final int FLAG_B = 2;
    public static final int FLAG_C = 4;
    public static final int FLAG_D = 8;

    /**
     * One row.
     *
     * @param id    what an intent names to act on this row
     * @param icon  item id whose icon and own name the row shows; empty for none
     * @param title lang key of the row's main text
     * @param args  lang keys (or {@code #tag} ids) the row's text uses
     * @param nums  numbers the row shows: prices, counts, times
     * @param flags tab-defined bits
     */
    public record Row(UUID id, String icon, String title, List<String> args, long[] nums, int flags) {
        public Row {
            if (id == null) id = new UUID(0L, 0L);
            icon = icon == null ? "" : icon;
            title = title == null ? "" : title;
            args = args == null ? List.of() : List.copyOf(args);
            nums = nums == null ? new long[0] : nums.clone();
        }

        /** The number at {@code index}, or 0 when the row has fewer. */
        public long num(int index) { return index >= 0 && index < nums.length ? nums[index] : 0L; }

        /** The argument at {@code index}, or empty when the row has fewer. */
        public String arg(int index) { return index >= 0 && index < args.size() ? args.get(index) : ""; }

        public boolean flag(int bit) { return (flags & bit) != 0; }

        @Override public long[] nums() { return nums.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof Row row && id.equals(row.id) && icon.equals(row.icon) && title.equals(row.title)
                    && args.equals(row.args) && Arrays.equals(nums, row.nums) && flags == row.flags;
        }

        @Override public int hashCode() {
            return ((id.hashCode() * 31 + icon.hashCode()) * 31 + title.hashCode()) * 31 + args.hashCode()
                    + Arrays.hashCode(nums) * 7 + flags;
        }

        Row capped() {
            List<String> kept = new ArrayList<>(Math.min(MAX_ARGS, args.size()));
            for (int index = 0; index < args.size() && kept.size() < MAX_ARGS; index++)
                kept.add(clip(args.get(index), MAX_STRING));
            return new Row(id, clip(icon, MAX_ICON), clip(title, MAX_STRING), kept,
                    Arrays.copyOf(nums, Math.min(MAX_NUMS, nums.length)), flags);
        }
    }

    public HubPage {
        if (tab == null) tab = HubTab.WALLET;
        page = Math.max(0, page);
        pageCount = Math.max(1, pageCount);
        rows = rows == null ? List.of() : List.copyOf(rows);
        numbers = numbers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(numbers));
        keys = keys == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        series = series == null ? new long[0] : series.clone();
    }

    /** A page with no chart. */
    public HubPage(int containerId, int revision, HubTab tab, int page, int pageCount, List<Row> rows,
            Map<String, Long> numbers, Map<String, String> keys) {
        this(containerId, revision, tab, page, pageCount, rows, numbers, keys, null);
    }

    /** An empty page of {@code tab}. */
    public static HubPage empty(int containerId, HubTab tab) {
        return new HubPage(containerId, 0, tab, 0, 1, List.of(), Map.of(), Map.of());
    }

    @Override public long[] series() { return series.clone(); }

    /** The same page with a chart. */
    public HubPage withSeries(long[] next) {
        return new HubPage(containerId, revision, tab, page, pageCount, rows, numbers, keys, next);
    }

    @Override public boolean equals(Object other) {
        return other instanceof HubPage that && containerId == that.containerId && revision == that.revision
                && tab == that.tab && page == that.page && pageCount == that.pageCount && rows.equals(that.rows)
                && numbers.equals(that.numbers) && keys.equals(that.keys) && Arrays.equals(series, that.series);
    }

    @Override public int hashCode() {
        return ((((containerId * 31 + revision) * 31 + tab.hashCode()) * 31 + page) * 31 + pageCount) * 31
                + rows.hashCode() * 7 + numbers.hashCode() * 3 + keys.hashCode() + Arrays.hashCode(series);
    }

    /** A header number, or {@code fallback} when the page has none by that name. */
    public long number(String name, long fallback) {
        Long value = numbers.get(name);
        return value == null ? fallback : value;
    }

    /** A header key, or empty when the page has none by that name. */
    public String key(String name) {
        String value = keys.get(name);
        return value == null ? "" : value;
    }

    /** The same page addressed to a menu, at a revision. */
    public HubPage at(int nextContainerId, int nextRevision) {
        return new HubPage(nextContainerId, nextRevision, tab, page, pageCount, rows, numbers, keys, series);
    }

    /** The page cut down to every bound: what is ever written to the wire. */
    public HubPage capped() {
        List<Row> keptRows = new ArrayList<>(Math.min(MAX_ROWS, rows.size()));
        for (int index = 0; index < rows.size() && keptRows.size() < MAX_ROWS; index++)
            keptRows.add(rows.get(index).capped());
        Map<String, Long> keptNumbers = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : numbers.entrySet()) {
            if (keptNumbers.size() >= MAX_HEADER_NUMBERS) break;
            if (entry.getKey() != null && entry.getValue() != null)
                keptNumbers.put(clip(entry.getKey(), MAX_HEADER_NAME), entry.getValue());
        }
        Map<String, String> keptKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (keptKeys.size() >= MAX_HEADER_KEYS) break;
            if (entry.getKey() != null && entry.getValue() != null)
                keptKeys.put(clip(entry.getKey(), MAX_HEADER_NAME), clip(entry.getValue(), MAX_STRING));
        }
        return new HubPage(containerId, revision, tab, page, pageCount, keptRows, keptNumbers, keptKeys,
                Arrays.copyOf(series, Math.min(MAX_SERIES, series.length)));
    }

    /** Cuts a string to {@code max} characters; null becomes empty. */
    public static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max));
    }

    /** How many pages a list of {@code total} rows makes at {@code perPage} rows a page; at least 1. */
    public static int pages(long total, int perPage) {
        if (perPage <= 0 || total <= 0L) return 1;
        return (int) Math.min(Integer.MAX_VALUE, (total + perPage - 1L) / perPage);
    }

    /** {@code requested} kept inside {@code 0 .. pageCount - 1}. */
    public static int clampPage(long requested, int pageCount) {
        return (int) Math.max(0L, Math.min(Math.max(1, pageCount) - 1L, requested));
    }
}
