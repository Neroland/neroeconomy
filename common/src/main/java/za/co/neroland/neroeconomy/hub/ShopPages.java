package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Shop tab's page: the viewer's own Player Shops, each with where it stands, what it sells,
 * its stock, its price and how many days it has gone without the owner or a sale (a shop idle for
 * too long is closed and its stock returned). A shop's price can be set from here while its chunk
 * is loaded; a shop within reach opens its own window. Only the viewer's own shops are ever
 * listed. Pure Java.
 */
public final class ShopPages {
    public static final int SHOP_ROWS = 6;

    public static final String MAX_IDLE = "max_idle";
    public static final String COUNT = "count";

    public static final String ROW = "gui.neroeconomy.hub.shop.row";
    public static final String EMPTY_ICON = "neroeconomy:player_shop";
    public static final List<String> KEYS = List.of(ROW);

    /** Row flags. */
    public static final int FLAG_IN_REACH = HubPage.FLAG_A;
    public static final int FLAG_LOADED = HubPage.FLAG_B;
    public static final int FLAG_EMPTY = HubPage.FLAG_C;

    private ShopPages() { }

    /**
     * One of the viewer's shops.
     *
     * @param location  its stored {@code dimension|packedPosition} key; never sent
     * @param item      what it sells; empty when it holds no stock
     * @param price     its price each; 0 when not known (an unloaded shop with no stock)
     * @param idleDays  whole days since the owner or a buyer last used it
     * @param inReach   whether the viewer stands close enough to open it
     * @param loaded    whether its chunk is loaded, so its price can be changed now
     */
    public record ShopLine(String location, String item, String dimension, long x, long y, long z, long stock,
            long price, long idleDays, boolean inReach, boolean loaded) { }

    /** @param maxIdleDays after how many idle days a shop is closed; 0 for never */
    public record ShopView(List<ShopLine> shops, int maxIdleDays) {
        public ShopView {
            shops = shops == null ? List.of() : List.copyOf(shops);
        }
    }

    /** The row id of a shop: stable for the same place. */
    public static UUID shopId(String location) { return HubPages.lineId("shop", location == null ? "" : location, 0, 0); }

    public static HubPages.Built build(ShopView view, long requestedPage) {
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        List<ShopLine> lines = view.shops();
        int pages = HubPage.pages(lines.size(), SHOP_ROWS);
        int page = HubPage.clampPage(requestedPage, pages);
        int from = page * SHOP_ROWS;
        for (ShopLine line : lines.subList(Math.min(lines.size(), from), Math.min(lines.size(), from + SHOP_ROWS))) {
            UUID id = shopId(line.location());
            boolean empty = line.item() == null || line.item().isEmpty();
            int flags = (line.inReach() ? FLAG_IN_REACH : 0) | (line.loaded() ? FLAG_LOADED : 0) | (empty ? FLAG_EMPTY : 0);
            rows.add(new HubPage.Row(id, empty ? EMPTY_ICON : line.item(), ROW, List.of(line.dimension()),
                    new long[] { line.x(), line.y(), line.z(), line.stock(), line.price(), line.idleDays() }, flags));
            refs.put(id, new HubPages.RowRef(HubPages.RowRef.Kind.PLAYER_SHOP, null, line.location(), 0));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        numbers.put(MAX_IDLE, (long) view.maxIdleDays());
        numbers.put(COUNT, (long) lines.size());
        return new HubPages.Built(new HubPage(0, 0, HubTab.SHOP, page, pages, rows, numbers, Map.of()), refs);
    }
}
